package greenecomall.service;

import greenecomall.dto.response.BranchStatsResponse;
import greenecomall.dto.response.TreeNodeResponse;
import greenecomall.dto.response.TreeResponse;
import greenecomall.entity.TreePosition;
import greenecomall.entity.User;
import greenecomall.enums.NotificationType;
import greenecomall.enums.StageStatus;
import greenecomall.repository.TreePositionRepository;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TreeService {

    private final TreePositionRepository treePositionRepo;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // Setter injection to break TreeService ↔ BonusService circular dependency
    private BonusService bonusService;

    public void setBonusService(BonusService bonusService) {
        this.bonusService = bonusService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by PaymentService when a new user activates.
     * Places the user into the inviter's Stage-1 tree using BFS.
     */
    @Transactional
    public void placeNewUser(User inviter, User newUser) {
        int level = inviter.getCurrentLevel();
        User directParent = bfsPlace(inviter, newUser, level, 1);

        if (directParent == null) {
            throw new IllegalStateException(
                    "BFS could not place user " + newUser.getId() + " in inviter " + inviter.getId() + " tree");
        }

        // Check Stage 1 completion for the placement node and up to 2 ancestors —
        // covers: directParent's own matrix (newUser = tier 1) and
        // directParent's parent's matrix (newUser = tier 2).
        checkStage1UpTheChain(directParent, level);

        String msg = newUser.getFirstName() + " " + newUser.getLastName() + " присоединился к вашей команде";

        // Always notify the tree root (inviter)
        notificationService.send(inviter, NotificationType.NEW_MEMBER, "Новый участник", msg);

        // Also notify the direct parent if different from inviter
        if (directParent != null && !directParent.getId().equals(inviter.getId())) {
            notificationService.send(directParent, NotificationType.NEW_MEMBER,
                    "Новый участник в вашей ветке",
                    newUser.getFirstName() + " " + newUser.getLastName() + " занял позицию под вами");
        }
    }

    /**
     * Проверяет завершение Этапа 1 для node и его родителя в дереве.
     * Это покрывает случаи когда новый юзер попал на тир 3+ от изначального inviter-а:
     * он является тир-1 для node и тир-2 для node's parent.
     */
    private void checkStage1UpTheChain(User node, int level) {
        checkStage1Completion(node, level);
        treePositionRepo.findByUserAndLevelAndStage(node, level, 1).ifPresent(pos -> {
            if (pos.getParent() != null) {
                checkStage1Completion(pos.getParent(), level);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STAGE TRANSITIONS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stage 1 → Stage 2.
     * Called when root's Stage-1 tree has all 6 positions filled.
     */
    @Transactional
    public void onStage1Completed(User user, int level) {
        markStageComplete(user, level, 1);

        if (bonusService != null) {
            bonusService.createStageBonuses(user, level, 1);

            // 1250 сом прямому реферу каждого из 6 участников матрицы
            List<User> members = collectMatrixMembers(user, level, 1);
            bonusService.createMemberReferralBonuses(level, members);
        }

        // Ускоритель выполнил свою задачу — удаляем его из матрицы
        removeAcceleratorsUnder(user, level);

        user.setCurrentStage(2);
        userRepository.save(user);

        log.info("User {} completed Stage 1 at level {}", user.getId(), level);

        // Fill a Stage-2 slot under the DIRECT inviter (if inviter is already on Stage 2)
        fillStage2UnderInviter(user, level);

        notificationService.send(user, NotificationType.STAGE_COMPLETE,
                "Этап 1 завершён",
                "Все 6 позиций заполнены. Переход на Этап 2 уровня " + level + ".");
    }

    /**
     * Stage 2 → Stage 3.
     * Called when both fixed partners of user have been assigned.
     */
    @Transactional
    public void onStage2Completed(User user, int level) {
        markStageComplete(user, level, 2);

        if (bonusService != null) {
            bonusService.createStageBonuses(user, level, 2); // платит только уровням 3-4
        }

        user.setCurrentStage(3);
        userRepository.save(user);

        // Accelerator is placed only on Levels 1 and 2
        if (level <= 2) {
            placeAccelerator(user, level);
        }

        log.info("User {} completed Stage 2 at level {}", user.getId(), level);

        // Notify the inviter's tree root that one more member reached Stage 3
        checkStage3Progress(user, level);

        notificationService.send(user, NotificationType.STAGE_COMPLETE,
                "Этап 2 завершён",
                "Оба партнёра подтверждены. Вы на Этапе 3 уровня " + level + ".");
    }

    /**
     * Stage 3 → Stage 4.
     * Called when ALL 6 positions in user's Stage-1 tree have reached Stage 3.
     */
    @Transactional
    public void onStage3Completed(User user, int level) {
        markStageComplete(user, level, 3);

        if (bonusService != null) {
            bonusService.createStageBonuses(user, level, 3);
        }

        user.setCurrentStage(4);
        userRepository.save(user);

        log.info("User {} completed Stage 3 at level {}", user.getId(), level);

        // Notify inviter that one more team member reached Stage 4
        checkStage4Progress(user, level);

        notificationService.send(user, NotificationType.STAGE_COMPLETE,
                "Этап 3 завершён",
                "Вся команда вышла на Этап 3. Уровень " + level + " почти завершён!");
    }

    /**
     * Stage 4 → next Level (or Shareholder if Level 4).
     * Called when BOTH fixed partners of user have reached Stage 4.
     */
    @Transactional
    public void onStage4Completed(User user, int level) {
        markStageComplete(user, level, 4);

        if (bonusService != null) {
            bonusService.createStageBonuses(user, level, 4); // платит только уровню 4
        }

        if (level < 4) {
            int nextLevel = level + 1;
            user.setCurrentLevel(nextLevel);
            user.setCurrentStage(1);
            userRepository.save(user);

            log.info("User {} advanced to level {}", user.getId(), nextLevel);

            // Re-seed Stage-1 tree for next level with existing fixed partners
            reEnterNextLevel(user, nextLevel);

            notificationService.send(user, NotificationType.LEVEL_UP,
                    "Новый уровень!",
                    "Поздравляем! Вы перешли на Уровень " + nextLevel + ".");
        } else {
            // Level 4 Stage 4 done → Shareholder
            notificationService.send(user, NotificationType.LEVEL_UP,
                    "Вы стали Акционером!",
                    "Поздравляем! Вы достигли вершины и теперь получаете дивиденды.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADMIN: REPAIR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * For all active users who completed Stage 1 (currentStage >= 2) but are not yet placed
     * as anyone's fixedPartnerLeft/Right, re-runs fillStage2UnderInviter.
     */
    @Transactional
    public List<String> repairStage2Placements() {
        List<String> report = new ArrayList<>();

        List<User> candidates = userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == greenecomall.enums.AccountStatus.ACTIVE)
                .filter(u -> u.getCurrentStage() >= 2)
                .filter(u -> !userRepository.existsByFixedPartnerLeft(u)
                          && !userRepository.existsByFixedPartnerRight(u))
                .toList();

        for (User user : candidates) {
            int level = user.getCurrentLevel();
            User ancestor = findFirstStage2Ancestor(user, level);
            if (ancestor == null) {
                report.add("NO_ANCESTOR: " + user.getFirstName() + " " + user.getLastName()
                        + " (id=" + user.getId() + ") — нет предка на Stage 2");
                continue;
            }
            User locked = userRepository.findByIdForUpdate(ancestor.getId()).orElse(null);
            if (locked == null || locked.getCurrentStage() != 2) {
                report.add("SKIP: " + user.getFirstName() + " — предок уже прошёл Stage 2");
                continue;
            }
            if (locked.getFixedPartnerLeft() == null) {
                locked.setFixedPartnerLeft(user);
                userRepository.save(locked);
                report.add("PLACED_LEFT: " + user.getFirstName() + " " + user.getLastName()
                        + " → " + locked.getFirstName() + " " + locked.getLastName());
                if (locked.getFixedPartnerRight() != null) onStage2Completed(locked, level);
            } else if (locked.getFixedPartnerRight() == null) {
                locked.setFixedPartnerRight(user);
                userRepository.save(locked);
                report.add("PLACED_RIGHT: " + user.getFirstName() + " " + user.getLastName()
                        + " → " + locked.getFirstName() + " " + locked.getLastName());
                onStage2Completed(locked, level);
            } else {
                report.add("FULL: " + locked.getFirstName() + " уже заполнен, " + user.getFirstName() + " не размещён");
            }
        }

        if (report.isEmpty()) report.add("Все позиции Stage 2 в порядке");
        return report;
    }

    /**
     * Finds all active users who have an inviter but no TreePosition at the inviter's current level,
     * and places them in the tree using BFS. Returns a report of what was fixed.
     */
    @Transactional
    public List<String> repairMissingPositions() {
        List<String> report = new ArrayList<>();

        List<User> activeUsers = userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == greenecomall.enums.AccountStatus.ACTIVE)
                .filter(u -> u.getInviter() != null)
                .toList();

        for (User user : activeUsers) {
            User inviter = userRepository.findById(user.getInviter().getId()).orElse(null);
            if (inviter == null) continue;

            int level = inviter.getCurrentLevel();
            boolean hasPosition = treePositionRepo.findByUserAndLevelAndStage(user, level, 1).isPresent();
            if (!hasPosition) {
                User placed = bfsPlace(inviter, user, level, 1);
                if (placed != null) {
                    report.add("PLACED: user=" + user.getId() + " (" + user.getFirstName() + " " + user.getLastName()
                            + ") under parent=" + placed.getId() + " level=" + level);
                } else {
                    report.add("FAILED: user=" + user.getId() + " — no free BFS slot found");
                }
            }
        }

        if (report.isEmpty()) report.add("No missing positions found");
        return report;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL: BFS PLACEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Iterative BFS — finds the first free slot in the inviter's tree and places newUser there.
     * Left position preferred when both branches are equal.
     * Returns the direct parent node where the user was placed (null if no slot found).
     */
    private User bfsPlace(User root, User newUser, int level, int stage) {
        Queue<User> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            User current = queue.poll();
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(current, level, stage);

            boolean hasLeft  = children.stream().anyMatch(c -> c.getPosition() == 1);
            boolean hasRight = children.stream().anyMatch(c -> c.getPosition() == 2);

            if (!hasLeft) {
                savePosition(newUser, current, level, stage, 1);
                return current;
            }
            if (!hasRight) {
                savePosition(newUser, current, level, stage, 2);
                return current;
            }

            // Both slots taken — enqueue real children (accelerators excluded) left→right
            children.stream()
                    .filter(c -> !c.getIsAccelerator())
                    .sorted(Comparator.comparingInt(TreePosition::getPosition))
                    .forEach(c -> queue.add(c.getUser()));
        }

        log.error("BFS: no free position found in tree of user {} level={} stage={}", root.getId(), level, stage);
        return null;
    }

    private void savePosition(User user, User parent, int level, int stage, int position) {
        treePositionRepo.save(TreePosition.builder()
                .user(user)
                .parent(parent)
                .level(level)
                .stage(stage)
                .position(position)
                .isAccelerator(false)
                .stageStatus(StageStatus.IN_PROGRESS)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL: STAGE COMPLETION CHECKS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks if root's Stage-1 tree is now fully filled (6 positions = 2 levels deep).
     * Accelerators count as real positions per the business rules.
     */
    private void checkStage1Completion(User root, int level) {
        // Reload to get fresh stage/level state and avoid double-triggering
        User fresh = userRepository.findById(root.getId()).orElse(root);
        if (fresh.getCurrentLevel() != level || fresh.getCurrentStage() != 1) return;

        int tier1 = treePositionRepo.countByParentAndLevelAndStage(fresh, level, 1);
        if (tier1 < 2) return;

        List<TreePosition> directChildren = treePositionRepo.findByParentAndLevelAndStage(fresh, level, 1);
        int tier2 = directChildren.stream()
                .mapToInt(c -> treePositionRepo.countByParentAndLevelAndStage(c.getUser(), level, 1))
                .sum();

        if (tier1 + tier2 >= 6) {
            onStage1Completed(fresh, level);
        }
    }

    /**
     * After user advances to Stage 3, checks if the root of the tree user belongs to
     * now has all 6 members on Stage 3 — which triggers Stage 3 completion for that root.
     */
    private void checkStage3Progress(User user, int level) {
        User treeRoot = findStage1TreeRoot(user, level);
        if (treeRoot == null) return;
        if (treeRoot.getCurrentStage() != 3) return; // root hasn't reached stage 3 yet itself

        boolean allAtStage3 = allSixMembersAtStage(treeRoot, level, 3);
        if (allAtStage3) {
            onStage3Completed(treeRoot, level);
        }
    }

    /**
     * After user advances to Stage 4, checks if the user is a fixed partner of their inviter
     * and both partners are now on Stage 4 — which triggers Stage 4 completion for the inviter.
     */
    private void checkStage4Progress(User user, int level) {
        // Reload user to get fresh data
        User fresh = userRepository.findById(user.getId()).orElse(user);
        User inviter = fresh.getInviter();
        if (inviter == null) return;

        inviter = userRepository.findById(inviter.getId()).orElse(inviter);
        if (inviter.getCurrentStage() != 4) return;

        boolean isLeftPartner  = inviter.getFixedPartnerLeft()  != null
                && inviter.getFixedPartnerLeft().getId().equals(user.getId());
        boolean isRightPartner = inviter.getFixedPartnerRight() != null
                && inviter.getFixedPartnerRight().getId().equals(user.getId());

        if (!isLeftPartner && !isRightPartner) return;

        boolean leftDone  = inviter.getFixedPartnerLeft()  != null
                && inviter.getFixedPartnerLeft().getCurrentStage()  >= 4;
        boolean rightDone = inviter.getFixedPartnerRight() != null
                && inviter.getFixedPartnerRight().getCurrentStage() >= 4;

        if (leftDone && rightDone) {
            onStage4Completed(inviter, level);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL: STAGE 2 PARTNER ASSIGNMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * When user completes Stage 1 they fill a Stage 2 slot under the closest ancestor
     * in the tree who is currently on Stage 2. Walks UP the tree_positions parent chain
     * so tier-2+ completers correctly bubble up past intermediate nodes.
     */
    private void fillStage2UnderInviter(User user, int level) {
        User ancestor = findFirstStage2Ancestor(user, level);
        if (ancestor == null) return;

        User locked = userRepository.findByIdForUpdate(ancestor.getId())
                .orElseThrow(() -> new IllegalStateException("Stage2 ancestor not found: " + ancestor.getId()));

        if (locked.getCurrentStage() != 2 || locked.getCurrentLevel() != level) return;

        if (locked.getFixedPartnerLeft() == null) {
            locked.setFixedPartnerLeft(user);
            userRepository.save(locked);

            notificationService.send(locked, NotificationType.NEW_MEMBER,
                    "Этап 2 — левая позиция занята",
                    user.getFirstName() + " " + user.getLastName() + " встал на Этап 2 (слева)");

        } else if (locked.getFixedPartnerRight() == null) {
            locked.setFixedPartnerRight(user);
            userRepository.save(locked);

            notificationService.send(locked, NotificationType.NEW_MEMBER,
                    "Этап 2 — правая позиция занята",
                    user.getFirstName() + " " + user.getLastName() + " встал на Этап 2 (справа)");

            onStage2Completed(locked, level);
        }
    }

    /**
     * Walks UP the Stage-1 tree_positions parent chain and returns the TOPMOST ancestor
     * currently on Stage 2. This ensures that when a tier-2+ completer bubbles up, they
     * fill the root's Stage 2 slot first — not an intermediate node that just moved to Stage 2.
     * Only after the root's Stage 2 is full will lower ancestors receive completers.
     */
    private User findFirstStage2Ancestor(User user, int level) {
        Optional<TreePosition> pos = treePositionRepo.findByUserAndLevelAndStage(user, level, 1);
        if (pos.isEmpty() || pos.get().getParent() == null) return null;

        User topmost = null;
        User parent = pos.get().getParent();
        while (parent != null) {
            User fresh = userRepository.findById(parent.getId()).orElse(null);
            if (fresh == null) break;

            if (fresh.getCurrentLevel() == level && fresh.getCurrentStage() == 2) {
                topmost = fresh; // keep going — maybe there's an even higher ancestor on Stage 2
            }

            Optional<TreePosition> parentPos = treePositionRepo.findByUserAndLevelAndStage(fresh, level, 1);
            if (parentPos.isEmpty() || parentPos.get().getParent() == null) break;
            parent = parentPos.get().getParent();
        }
        return topmost;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL: ACCELERATOR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Places a virtual accelerator in user's Stage-1 tree.
     * Only for Levels 1 and 2.
     *
     * Priority: if any branch already has an accelerator → stack into that branch.
     * Otherwise: place in the weaker (smaller) branch.
     */
    @Transactional
    public void placeAccelerator(User user, int level) {
        List<TreePosition> directChildren = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);

        // Find the branch that already has the most accelerators (stack there)
        Optional<User> stackBranch = directChildren.stream()
                .filter(c -> !c.getIsAccelerator())
                .filter(c -> countAcceleratorsInSubTree(c.getUser(), level) > 0)
                .max(Comparator.comparingInt(c -> countAcceleratorsInSubTree(c.getUser(), level)))
                .map(TreePosition::getUser);

        if (stackBranch.isPresent()) {
            bfsPlaceAccelerator(user, stackBranch.get(), level);
        } else {
            // No existing accelerators — place in the weaker branch
            int leftSize  = branchSize(user, level, 1);
            int rightSize = branchSize(user, level, 2);
            int weakSide  = (leftSize <= rightSize) ? 1 : 2;

            Optional<TreePosition> weakDirectChild = directChildren.stream()
                    .filter(c -> c.getPosition() == weakSide)
                    .findFirst();

            if (weakDirectChild.isEmpty()) {
                treePositionRepo.save(TreePosition.builder()
                        .user(user).parent(user).level(level).stage(1)
                        .position(weakSide).isAccelerator(true)
                        .stageStatus(StageStatus.IN_PROGRESS).build());
            } else {
                bfsPlaceAccelerator(user, weakDirectChild.get().getUser(), level);
            }
        }

        notificationService.send(user, NotificationType.ACCELERATOR_PLACED,
                "Ускоритель размещён",
                "Система автоматически разместила ускоритель в команду.");
    }

    private int countAcceleratorsInSubTree(User root, int level) {
        int count = 0;
        Set<UUID> visited = new HashSet<>();
        Queue<User> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            User cur = queue.poll();
            if (!visited.add(cur.getId())) continue;
            for (TreePosition child : treePositionRepo.findByParentAndLevelAndStage(cur, level, 1)) {
                if (child.getIsAccelerator()) count++;
                else queue.add(child.getUser());
            }
        }
        return count;
    }

    private void bfsPlaceAccelerator(User owner, User branchRoot, int level) {
        Queue<User> queue = new ArrayDeque<>();
        queue.add(branchRoot);

        while (!queue.isEmpty()) {
            User current = queue.poll();
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(current, level, 1);

            boolean hasLeft  = children.stream().anyMatch(c -> c.getPosition() == 1);
            boolean hasRight = children.stream().anyMatch(c -> c.getPosition() == 2);

            int freeSlot = !hasLeft ? 1 : !hasRight ? 2 : 0;
            if (freeSlot != 0) {
                treePositionRepo.save(TreePosition.builder()
                        .user(owner)
                        .parent(current)
                        .level(level)
                        .stage(1)
                        .position(freeSlot)
                        .isAccelerator(true)
                        .stageStatus(StageStatus.IN_PROGRESS)
                        .build());
                return;
            }

            children.stream()
                    .filter(c -> !c.getIsAccelerator())
                    .sorted(Comparator.comparingInt(TreePosition::getPosition))
                    .forEach(c -> queue.add(c.getUser()));
        }
    }

    /**
     * Removes ALL accelerators within user's entire Stage-1 sub-tree (recursive).
     * Called when user completes Stage 1 — accelerators have served their purpose.
     */
    @Transactional
    public void removeAcceleratorsUnder(User user, int level) {
        Set<UUID> visited = new HashSet<>();
        Queue<User> queue = new ArrayDeque<>();
        queue.add(user);
        while (!queue.isEmpty()) {
            User cur = queue.poll();
            if (!visited.add(cur.getId())) continue;
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(cur, level, 1);
            for (TreePosition child : children) {
                if (child.getIsAccelerator()) {
                    treePositionRepo.delete(child);
                } else {
                    queue.add(child.getUser());
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL: LEVEL TRANSITION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * After advancing to the next level, the user's two fixed partners are placed
     * into their Stage-1 tree for that new level (same partners, no new race).
     */
    private void reEnterNextLevel(User user, int level) {
        User left  = user.getFixedPartnerLeft();
        User right = user.getFixedPartnerRight();
        if (left  != null) bfsPlace(user, left,  level, 1);
        if (right != null) bfsPlace(user, right, level, 1); // return value unused here
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL: HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Собирает всех реальных участников (без ускорителей) в матрице root (тир 1 + тир 2). */
    private List<User> collectMatrixMembers(User root, int level, int stage) {
        List<User> members = new ArrayList<>();
        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(root, level, stage);
        for (TreePosition t1 : tier1) {
            if (t1.getIsAccelerator()) continue;
            members.add(t1.getUser());
            treePositionRepo.findByParentAndLevelAndStage(t1.getUser(), level, stage)
                    .stream().filter(t2 -> !t2.getIsAccelerator())
                    .forEach(t2 -> members.add(t2.getUser()));
        }
        return members;
    }

    private void markStageComplete(User user, int level, int stage) {
        treePositionRepo.findByUserAndLevelAndStage(user, level, stage).ifPresent(pos -> {
            pos.setStageStatus(StageStatus.COMPLETED);
            pos.setCompletedStageAt(LocalDateTime.now());
            treePositionRepo.save(pos);
        });
    }

    /** BFS count of all nodes in a specific branch (side: 1=left, 2=right) under root at stage 1. */
    private int branchSize(User root, int level, int side) {
        List<TreePosition> direct = treePositionRepo.findByParentAndLevelAndStage(root, level, 1);
        Optional<TreePosition> sideChild = direct.stream().filter(c -> c.getPosition() == side).findFirst();
        if (sideChild.isEmpty()) return 0;

        int count = 0;
        Queue<User> queue = new ArrayDeque<>();
        queue.add(sideChild.get().getUser());
        while (!queue.isEmpty()) {
            User cur = queue.poll();
            count++;
            treePositionRepo.findByParentAndLevelAndStage(cur, level, 1)
                    .forEach(c -> queue.add(c.getUser()));
        }
        return count;
    }

    /**
     * Walks UP the TreePosition parent chain to find the root of the Stage-1 tree
     * that this user belongs to (the root is the node with no parent in stage 1).
     */
    private User findStage1TreeRoot(User user, int level) {
        Optional<TreePosition> pos = treePositionRepo.findByUserAndLevelAndStage(user, level, 1);
        if (pos.isEmpty() || pos.get().getParent() == null) return null;

        User parent = pos.get().getParent();
        // Walk up until we find a node whose own Stage-1 position has no parent (= root)
        while (true) {
            Optional<TreePosition> parentPos = treePositionRepo.findByUserAndLevelAndStage(parent, level, 1);
            if (parentPos.isEmpty() || parentPos.get().getParent() == null) return parent;
            parent = parentPos.get().getParent();
        }
    }

    /**
     * Returns true if all 6 members in root's Stage-1 tree have currentStage >= minStage.
     */
    private boolean allSixMembersAtStage(User root, int level, int minStage) {
        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(root, level, 1);
        if (tier1.size() < 2) return false;

        for (TreePosition t1 : tier1) {
            if (t1.getUser().getCurrentStage() < minStage) return false;
            List<TreePosition> tier2 = treePositionRepo.findByParentAndLevelAndStage(t1.getUser(), level, 1);
            if (tier2.size() < 2) return false;
            for (TreePosition t2 : tier2) {
                if (t2.getUser().getCurrentStage() < minStage) return false;
            }
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API: BUILD TREE RESPONSE
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TreeResponse getTree(User user, int level, int stage) {
        // Stage 2 and 4 partners are stored as fixedPartnerLeft/Right on User, not in tree_positions
        if (stage == 2 || stage == 4) {
            return buildFixedPartnersTree(user, level, stage);
        }

        TreePosition rootPos = treePositionRepo.findByUserAndLevelAndStage(user, level, stage).orElse(null);
        StageStatus status = rootPos != null ? rootPos.getStageStatus() : StageStatus.WAITING;

        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(user, level, stage);
        int filled = tier1.size() + tier1.stream()
                .mapToInt(c -> treePositionRepo.countByParentAndLevelAndStage(c.getUser(), level, stage))
                .sum();

        boolean hasAccelerator = tier1.stream().anyMatch(TreePosition::getIsAccelerator)
                || tier1.stream().anyMatch(c ->
                treePositionRepo.findByParentAndLevelAndStage(c.getUser(), level, stage)
                        .stream().anyMatch(TreePosition::getIsAccelerator));

        // Stage 1 Level 1: infinite BFS tree; everything else: up to 6 (2 tiers)
        int depth = (stage == 1 && level == 1) ? 20 : 3;

        TreeNodeResponse rootNode = buildNode(user, level, stage, depth);

        return TreeResponse.builder()
                .root(rootNode)
                .stageStatus(status)
                .progress(TreeResponse.TreeProgress.builder().filled(filled).total(6).build())
                .accelerator(TreeResponse.AcceleratorInfo.builder().active(hasAccelerator).build())
                .build();
    }

    private TreeResponse buildFixedPartnersTree(User user, int level, int stage) {
        User left  = userRepository.findById(
                user.getFixedPartnerLeft()  != null ? user.getFixedPartnerLeft().getId()  : user.getId())
                .filter(u -> user.getFixedPartnerLeft() != null).orElse(null);
        User right = userRepository.findById(
                user.getFixedPartnerRight() != null ? user.getFixedPartnerRight().getId() : user.getId())
                .filter(u -> user.getFixedPartnerRight() != null).orElse(null);

        List<TreeNodeResponse> children = new ArrayList<>();
        if (left != null) {
            children.add(TreeNodeResponse.builder()
                    .userId(left.getId())
                    .name(left.getFirstName() + " " + left.getLastName())
                    .initials(initials(left))
                    .position(1)
                    .isAccelerator(false)
                    .stageStatus(left.getCurrentStage() > stage ? StageStatus.COMPLETED : StageStatus.IN_PROGRESS)
                    .children(buildNode(left, level, 1, 20).children())
                    .build());
        }
        if (right != null) {
            children.add(TreeNodeResponse.builder()
                    .userId(right.getId())
                    .name(right.getFirstName() + " " + right.getLastName())
                    .initials(initials(right))
                    .position(2)
                    .isAccelerator(false)
                    .stageStatus(right.getCurrentStage() > stage ? StageStatus.COMPLETED : StageStatus.IN_PROGRESS)
                    .children(buildNode(right, level, 1, 20).children())
                    .build());
        }

        int filled = children.size();
        boolean isCurrentStage = user.getCurrentLevel() == level && user.getCurrentStage() == stage;
        StageStatus status = user.getCurrentStage() > stage ? StageStatus.COMPLETED
                : isCurrentStage ? StageStatus.IN_PROGRESS : StageStatus.WAITING;

        TreeNodeResponse rootNode = TreeNodeResponse.builder()
                .userId(user.getId())
                .name(user.getFirstName() + " " + user.getLastName())
                .initials(initials(user))
                .stageStatus(status)
                .children(children)
                .build();

        return TreeResponse.builder()
                .root(rootNode)
                .stageStatus(status)
                .progress(TreeResponse.TreeProgress.builder().filled(filled).total(2).build())
                .accelerator(TreeResponse.AcceleratorInfo.builder().active(false).build())
                .build();
    }

    private TreeNodeResponse buildNode(User user, int level, int stage, int depth) {
        TreePosition pos = treePositionRepo.findByUserAndLevelAndStage(user, level, stage).orElse(null);
        List<TreeNodeResponse> childNodes = new ArrayList<>();

        if (depth > 0) {
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(user, level, stage);
            children.stream()
                    .sorted(Comparator.comparingInt(TreePosition::getPosition))
                    .forEach(child -> {
                        TreePosition childPos = child;
                        boolean accel = childPos.getIsAccelerator();
                        childNodes.add(TreeNodeResponse.builder()
                                .userId(accel ? null : childPos.getUser().getId())
                                .name(accel ? null : childPos.getUser().getFirstName() + " " + childPos.getUser().getLastName())
                                .initials(accel ? null : initials(childPos.getUser()))
                                .position(childPos.getPosition())
                                .isAccelerator(accel)
                                .stageStatus(childPos.getStageStatus())
                                .children(accel ? List.of() : buildNode(childPos.getUser(), level, stage, depth - 1).children())
                                .build());
                    });
        }

        return TreeNodeResponse.builder()
                .userId(user.getId())
                .name(user.getFirstName() + " " + user.getLastName())
                .initials(initials(user))
                .stageStatus(pos != null ? pos.getStageStatus() : StageStatus.WAITING)
                .children(childNodes)
                .build();
    }

    private String initials(User u) {
        return String.valueOf(u.getFirstName().charAt(0)).toUpperCase()
                + String.valueOf(u.getLastName().charAt(0)).toUpperCase();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API: STAGES OVERVIEW — все 4 этапа текущего уровня
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public greenecomall.dto.response.StagesOverviewResponse getStagesOverview(User user) {
        int level = user.getCurrentLevel();
        int current = user.getCurrentStage();

        List<greenecomall.dto.response.StagesOverviewResponse.StageDetail> stages = new ArrayList<>();
        stages.add(buildStage1Detail(user, level, current));
        stages.add(buildStage2Detail(user, level, current));
        stages.add(buildStage3Detail(user, level, current));
        stages.add(buildStage4Detail(user, level, current));

        return greenecomall.dto.response.StagesOverviewResponse.builder()
                .currentLevel(level)
                .currentStage(current)
                .stages(stages)
                .build();
    }

    private greenecomall.dto.response.StagesOverviewResponse.StageDetail buildStage1Detail(
            User user, int level, int currentStage) {

        StageStatus status = resolveStatus(user, level, 1, currentStage);

        // Collect all 6 positions (2 tiers deep)
        List<greenecomall.dto.response.StagesOverviewResponse.TreeMember> members = new ArrayList<>();
        int posNum = 2;

        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);
        tier1.sort(Comparator.comparingInt(TreePosition::getPosition));

        for (TreePosition t1 : tier1) {
            members.add(toTreeMember(posNum++, t1));
            List<TreePosition> tier2 = treePositionRepo.findByParentAndLevelAndStage(t1.getUser(), level, 1);
            tier2.sort(Comparator.comparingInt(TreePosition::getPosition));
            for (TreePosition t2 : tier2) {
                members.add(toTreeMember(posNum++, t2));
            }
        }

        int filled = members.size();

        LocalDateTime completedAt = treePositionRepo.findByUserAndLevelAndStage(user, level, 1)
                .map(TreePosition::getCompletedStageAt).orElse(null);

        return greenecomall.dto.response.StagesOverviewResponse.StageDetail.builder()
                .stage(1)
                .title("Этап 1 — Формирование команды")
                .description("Заполни 6 позиций своего дерева (2 яруса по 2). " +
                        "Бонусы за приглашённых подтвердятся когда каждый из них сам соберёт 6.")
                .status(status)
                .progress(greenecomall.dto.response.StagesOverviewResponse.Stage1Progress.builder()
                        .filled(filled).total(6).members(members).build())
                .completedAt(completedAt)
                .build();
    }

    private greenecomall.dto.response.StagesOverviewResponse.StageDetail buildStage2Detail(
            User user, int level, int currentStage) {

        StageStatus status = resolveStatus(user, level, 2, currentStage);

        User left  = user.getFixedPartnerLeft();
        User right = user.getFixedPartnerRight();

        LocalDateTime leftFilledAt  = left  != null ? treePositionRepo
                .findByUserAndLevelAndStage(left,  level, 1)
                .map(TreePosition::getCompletedStageAt).orElse(null) : null;
        LocalDateTime rightFilledAt = right != null ? treePositionRepo
                .findByUserAndLevelAndStage(right, level, 1)
                .map(TreePosition::getCompletedStageAt).orElse(null) : null;

        LocalDateTime completedAt = treePositionRepo.findByUserAndLevelAndStage(user, level, 2)
                .map(TreePosition::getCompletedStageAt).orElse(null);

        return greenecomall.dto.response.StagesOverviewResponse.StageDetail.builder()
                .stage(2)
                .title("Этап 2 — Domkrat (гонка партнёров)")
                .description("Два первых участника из твоей ветки кто завершит Этап 1 " +
                        "занимают левую и правую позицию. На уровне 1 — гонка, на уровнях 2-4 — те же партнёры.")
                .status(status)
                .progress(greenecomall.dto.response.StagesOverviewResponse.Stage2Progress.builder()
                        .left(toPartnerSlot(left, leftFilledAt))
                        .right(toPartnerSlot(right, rightFilledAt))
                        .build())
                .completedAt(completedAt)
                .build();
    }

    private greenecomall.dto.response.StagesOverviewResponse.StageDetail buildStage3Detail(
            User user, int level, int currentStage) {

        StageStatus status = resolveStatus(user, level, 3, currentStage);

        // All 6 members of user's Stage-1 tree — how many have reached Stage 3?
        List<greenecomall.dto.response.StagesOverviewResponse.Stage3Member> members = new ArrayList<>();
        int reached = 0;

        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);
        tier1.sort(Comparator.comparingInt(TreePosition::getPosition));

        for (TreePosition t1 : tier1) {
            boolean done = t1.getUser().getCurrentStage() >= 3;
            if (done) reached++;
            members.add(toStage3Member(t1.getUser(), done));

            List<TreePosition> tier2 = treePositionRepo.findByParentAndLevelAndStage(t1.getUser(), level, 1);
            tier2.sort(Comparator.comparingInt(TreePosition::getPosition));
            for (TreePosition t2 : tier2) {
                boolean done2 = t2.getUser().getCurrentStage() >= 3;
                if (done2) reached++;
                members.add(toStage3Member(t2.getUser(), done2));
            }
        }

        LocalDateTime completedAt = treePositionRepo.findByUserAndLevelAndStage(user, level, 3)
                .map(TreePosition::getCompletedStageAt).orElse(null);

        return greenecomall.dto.response.StagesOverviewResponse.StageDetail.builder()
                .stage(3)
                .title("Этап 3 — Leader Core")
                .description("Все 6 участников твоей команды должны дойти до Этапа 3. " +
                        "Как только последний дойдёт — получишь этапный бонус.")
                .status(status)
                .progress(greenecomall.dto.response.StagesOverviewResponse.Stage3Progress.builder()
                        .reached(reached).total(6).members(members).build())
                .completedAt(completedAt)
                .build();
    }

    private greenecomall.dto.response.StagesOverviewResponse.StageDetail buildStage4Detail(
            User user, int level, int currentStage) {

        StageStatus status = resolveStatus(user, level, 4, currentStage);

        User left  = user.getFixedPartnerLeft();
        User right = user.getFixedPartnerRight();

        LocalDateTime leftFilledAt  = left  != null ? treePositionRepo
                .findByUserAndLevelAndStage(left,  level, 3)
                .map(TreePosition::getCompletedStageAt).orElse(null) : null;
        LocalDateTime rightFilledAt = right != null ? treePositionRepo
                .findByUserAndLevelAndStage(right, level, 3)
                .map(TreePosition::getCompletedStageAt).orElse(null) : null;

        LocalDateTime completedAt = treePositionRepo.findByUserAndLevelAndStage(user, level, 4)
                .map(TreePosition::getCompletedStageAt).orElse(null);

        return greenecomall.dto.response.StagesOverviewResponse.StageDetail.builder()
                .stage(4)
                .title("Этап 4 — Переход на следующий уровень")
                .description("Оба фиксированных партнёра должны завершить Этап 3. " +
                        "После этого вся команда переходит на следующий уровень вместе с тобой.")
                .status(status)
                .progress(greenecomall.dto.response.StagesOverviewResponse.Stage4Progress.builder()
                        .left(toPartnerSlotStage4(left, leftFilledAt))
                        .right(toPartnerSlotStage4(right, rightFilledAt))
                        .build())
                .completedAt(completedAt)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API: TEAM ACTIVITY — лента событий в команде
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<greenecomall.dto.response.TeamActivityResponse> getTeamActivity(User user) {
        int level = user.getCurrentLevel();
        List<greenecomall.dto.response.TeamActivityResponse> events = new ArrayList<>();

        // Collect all team members (positions 2-7 in user's tree across all stages)
        Set<UUID> seen = new HashSet<>();
        collectTeamMembers(user, level, 1, events, seen);

        // Sort newest first
        events.sort(Comparator.comparing(
                greenecomall.dto.response.TeamActivityResponse::occurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return events;
    }

    private void collectTeamMembers(User root, int level, int stage,
            List<greenecomall.dto.response.TeamActivityResponse> events, Set<UUID> seen) {

        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(root, level, stage);
        for (TreePosition t1 : tier1) {
            if (t1.getIsAccelerator()) continue;
            User member = t1.getUser();
            addMemberEvents(member, level, events, seen);

            List<TreePosition> tier2 = treePositionRepo.findByParentAndLevelAndStage(member, level, stage);
            for (TreePosition t2 : tier2) {
                if (!t2.getIsAccelerator()) {
                    addMemberEvents(t2.getUser(), level, events, seen);
                }
            }
        }
    }

    private void addMemberEvents(User member, int level,
            List<greenecomall.dto.response.TeamActivityResponse> events, Set<UUID> seen) {
        if (seen.contains(member.getId())) return;
        seen.add(member.getId());

        // JOIN event — when they activated
        events.add(greenecomall.dto.response.TeamActivityResponse.builder()
                .userId(member.getId())
                .name(member.getFirstName() + " " + member.getLastName())
                .initials(initials(member))
                .event("JOINED")
                .description(member.getFirstName() + " вступил в команду")
                .level(level).stage(1)
                .occurredAt(member.getActivatedAt())
                .build());

        // Stage completion events
        for (int s = 1; s <= member.getCurrentStage() - 1; s++) {
            int stageNum = s;
            treePositionRepo.findByUserAndLevelAndStage(member, level, s)
                    .filter(p -> p.getCompletedStageAt() != null)
                    .ifPresent(p -> events.add(
                            greenecomall.dto.response.TeamActivityResponse.builder()
                                    .userId(member.getId())
                                    .name(member.getFirstName() + " " + member.getLastName())
                                    .initials(initials(member))
                                    .event("STAGE_" + stageNum + "_DONE")
                                    .description(member.getFirstName() + " завершил Этап " + stageNum)
                                    .level(level).stage(stageNum)
                                    .occurredAt(p.getCompletedStageAt())
                                    .build()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API: BRANCH STATS — левая и правая ветки с участниками
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BranchStatsResponse getBranchStats(User user) {
        int level = user.getCurrentLevel();
        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);

        BranchStatsResponse.BranchInfo leftInfo  = buildBranchInfo(user, level, 1, tier1);
        BranchStatsResponse.BranchInfo rightInfo = buildBranchInfo(user, level, 2, tier1);

        int filled = tier1.size() + tier1.stream()
                .mapToInt(c -> treePositionRepo.countByParentAndLevelAndStage(c.getUser(), level, 1))
                .sum();

        return BranchStatsResponse.builder()
                .left(leftInfo)
                .right(rightInfo)
                .totalFilled(filled)
                .total(6)
                .build();
    }

    private BranchStatsResponse.BranchInfo buildBranchInfo(User root, int level, int side,
            List<TreePosition> tier1) {
        List<BranchStatsResponse.BranchMember> members = new ArrayList<>();

        Optional<TreePosition> directOpt = tier1.stream()
                .filter(c -> c.getPosition() == side)
                .findFirst();

        if (directOpt.isPresent()) {
            TreePosition direct = directOpt.get();
            members.add(toBranchMember(direct));

            treePositionRepo.findByParentAndLevelAndStage(direct.getUser(), level, 1)
                    .forEach(child -> members.add(toBranchMember(child)));
        }

        return BranchStatsResponse.BranchInfo.builder()
                .size(members.size())
                .members(members)
                .build();
    }

    private BranchStatsResponse.BranchMember toBranchMember(TreePosition tp) {
        return BranchStatsResponse.BranchMember.builder()
                .userId(tp.getUser().getId())
                .name(tp.getUser().getFirstName() + " " + tp.getUser().getLastName())
                .initials(initials(tp.getUser()))
                .currentStage(tp.getUser().getCurrentStage())
                .isAccelerator(tp.getIsAccelerator())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS for overview
    // ─────────────────────────────────────────────────────────────────────────

    private StageStatus resolveStatus(User user, int level, int stage, int currentStage) {
        if (currentStage > stage) return StageStatus.COMPLETED;
        if (currentStage == stage) return StageStatus.IN_PROGRESS;
        return StageStatus.WAITING;
    }

    private greenecomall.dto.response.StagesOverviewResponse.TreeMember toTreeMember(
            int position, TreePosition tp) {
        return greenecomall.dto.response.StagesOverviewResponse.TreeMember.builder()
                .position(position)
                .userId(tp.getUser().getId())
                .name(tp.getUser().getFirstName() + " " + tp.getUser().getLastName())
                .initials(initials(tp.getUser()))
                .stageStatus(tp.getStageStatus())
                .currentStage(tp.getUser().getCurrentStage())
                .joinedAt(tp.getCreatedAt())
                .build();
    }

    private greenecomall.dto.response.StagesOverviewResponse.PartnerSlot toPartnerSlot(
            User partner, LocalDateTime filledAt) {
        if (partner == null) {
            return greenecomall.dto.response.StagesOverviewResponse.PartnerSlot.builder()
                    .filled(false).build();
        }
        return greenecomall.dto.response.StagesOverviewResponse.PartnerSlot.builder()
                .filled(true)
                .userId(partner.getId())
                .name(partner.getFirstName() + " " + partner.getLastName())
                .initials(initials(partner))
                .currentStage(partner.getCurrentStage())
                .filledAt(filledAt)
                .build();
    }

    private greenecomall.dto.response.StagesOverviewResponse.PartnerSlot toPartnerSlotStage4(
            User partner, LocalDateTime completedAt) {
        if (partner == null) {
            return greenecomall.dto.response.StagesOverviewResponse.PartnerSlot.builder()
                    .filled(false).build();
        }
        return greenecomall.dto.response.StagesOverviewResponse.PartnerSlot.builder()
                .filled(partner.getCurrentStage() >= 4)
                .userId(partner.getId())
                .name(partner.getFirstName() + " " + partner.getLastName())
                .initials(initials(partner))
                .currentStage(partner.getCurrentStage())
                .filledAt(completedAt)
                .build();
    }

    private greenecomall.dto.response.StagesOverviewResponse.Stage3Member toStage3Member(
            User member, boolean reachedStage3) {
        return greenecomall.dto.response.StagesOverviewResponse.Stage3Member.builder()
                .userId(member.getId())
                .name(member.getFirstName() + " " + member.getLastName())
                .initials(initials(member))
                .currentStage(member.getCurrentStage())
                .reachedStage3(reachedStage3)
                .build();
    }
}
