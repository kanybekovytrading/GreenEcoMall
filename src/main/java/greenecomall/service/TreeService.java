package greenecomall.service;

import greenecomall.dto.response.BranchStatsResponse;
import greenecomall.dto.response.TreeNodeResponse;
import greenecomall.dto.response.TreeResponse;
import greenecomall.entity.TreePosition;
import greenecomall.entity.User;
import greenecomall.enums.NotificationType;
import greenecomall.enums.RegistrationPlan;
import greenecomall.enums.StageStatus;
import greenecomall.repository.TreePositionRepository;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.base-url:https://green-eco-mall-client.up.railway.app}")
    private String baseUrl;

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
        // Fast Start users (currentLevel=2) skip Level 1 and land in the Level 2 tree.
        // Normal users use the inviter's current level.
        int level = Math.max(inviter.getCurrentLevel(), newUser.getCurrentLevel());
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
     * Fast Start (Level 0) placement.
     * Finds the oldest Level 0 user who has no child yet and places newUser under them.
     * That triggers the host's graduation to Stage 2 of Level 1.
     * If no waiting host found — newUser becomes the first in queue.
     */
    @Transactional
    public void placeNewFastStartUser(User newUser) {
        // Assign sequential Fast Start number
        int queueNumber = userRepository.getNextFastStartNumber();
        newUser.setFastStartNumber(queueNumber);
        userRepository.save(newUser);

        User waitingHost = userRepository.findWaitingLevel0Users().stream()
                .filter(c -> !c.getId().equals(newUser.getId()))
                .filter(c -> treePositionRepo.countByParentAndLevelAndStage(c, 0, 1) == 0)
                .findFirst()
                .orElse(null);

        if (waitingHost != null) {
            // Place newUser under waitingHost in the Level 0 mini-tree
            savePosition(newUser, waitingHost, 0, 1, 1);

            // Host now has their 1 person — graduate to Stage 2 of Level 1
            graduateLevel0User(waitingHost, newUser);
        } else {
            // newUser is first in queue — they wait for the next Fast Start registrant
            notificationService.send(newUser, NotificationType.NEW_MEMBER,
                    "Быстрый Старт — ожидание",
                    "Вы #" + queueNumber + " в программе Быстрого Старта. Ждём следующего участника.");
        }
    }

    /**
     * Called when a Level 0 user gets their 1 required person.
     * Graduate jumps to Level 1, Stage 2 by finding any Stage 2 user with an empty
     * fixed-partner slot, respecting the rule: max 1 Level 0 graduate per node.
     * Priority: left slot first, earliest-activated host first.
     */
    private void graduateLevel0User(User graduate, User newUnder) {
        User locked = userRepository.findByIdForUpdate(graduate.getId()).orElse(graduate);
        locked.setCurrentLevel(1);
        locked.setCurrentStage(2);
        userRepository.save(locked);

        notificationService.send(locked, NotificationType.STAGE_COMPLETE,
                "Быстрый Старт завершён!",
                newUnder.getFirstName() + " " + newUnder.getLastName()
                        + " встал под вас. Переход на Этап 2 Уровня 1!");
        notificationService.send(newUnder, NotificationType.NEW_MEMBER,
                "Аккаунт активирован",
                "Вы размещены в Быстром Старте. Теперь ждите своего участника.");

        // Find eligible Stage 2 slot: weakest link (1 partner) first, then 0 partners;
        // within each group by activatedAt ASC (left-to-right approximation).
        // No node may have both slots occupied by Level 0 graduates.
        List<User> stage2Candidates = userRepository.findStage2UsersWithEmptySlots().stream()
                .sorted(Comparator.comparingInt((User u) -> {
                    int p = (u.getFixedPartnerLeft() != null ? 1 : 0)
                          + (u.getFixedPartnerRight() != null ? 1 : 0);
                    return p == 1 ? 0 : 1;
                }))
                .collect(java.util.stream.Collectors.toList());
        for (User candidate : stage2Candidates) {
            User host = userRepository.findByIdForUpdate(candidate.getId()).orElse(candidate);

            boolean leftIsLevel0  = host.getFixedPartnerLeft() != null
                    && host.getFixedPartnerLeft().getRegistrationPlan() == RegistrationPlan.FAST_START;
            boolean rightIsLevel0 = host.getFixedPartnerRight() != null
                    && host.getFixedPartnerRight().getRegistrationPlan() == RegistrationPlan.FAST_START;

            if (host.getFixedPartnerLeft() == null && !rightIsLevel0) {
                host.setFixedPartnerLeft(locked);
                userRepository.save(host);
                notificationService.send(host, NotificationType.NEW_MEMBER,
                        "Этап 2 — левая позиция занята",
                        locked.getFirstName() + " " + locked.getLastName() + " (Быстрый Старт) встал слева");
                if (host.getFixedPartnerRight() != null) {
                    onStage2Completed(host, 1);
                }
                log.info("Level 0 graduate {} placed as fixedPartnerLeft of {}", locked.getId(), host.getId());
                return;
            }

            if (host.getFixedPartnerRight() == null && !leftIsLevel0) {
                host.setFixedPartnerRight(locked);
                userRepository.save(host);
                notificationService.send(host, NotificationType.NEW_MEMBER,
                        "Этап 2 — правая позиция занята",
                        locked.getFirstName() + " " + locked.getLastName() + " (Быстрый Старт) встал справа");
                if (host.getFixedPartnerLeft() != null) {
                    onStage2Completed(host, 1);
                }
                log.info("Level 0 graduate {} placed as fixedPartnerRight of {}", locked.getId(), host.getId());
                return;
            }
        }

        log.warn("Level 0 graduate {} could not find an eligible Stage 2 slot — will be picked up by repair job",
                locked.getId());
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
            List<TreePosition> tier1Positions = treePositionRepo
                    .findByParentAndLevelAndStage(user, level, 1);

            List<User> externalTier1 = tier1Positions.stream()
                    .filter(tp -> !tp.getIsAccelerator())
                    .map(TreePosition::getUser)
                    .filter(m -> m.getInviter() != null
                              && !m.getInviter().getId().equals(user.getId()))
                    .collect(java.util.stream.Collectors.toList());

            List<User> tier2Members = tier1Positions.stream()
                    .filter(tp -> !tp.getIsAccelerator())
                    .flatMap(tp -> treePositionRepo
                            .findByParentAndLevelAndStage(tp.getUser(), level, 1).stream())
                    .filter(tp -> !tp.getIsAccelerator())
                    .map(TreePosition::getUser)
                    .collect(java.util.stream.Collectors.toList());

            bonusService.createStage1Bonuses(user, level, externalTier1, tier2Members);
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
     * Manually moves a Stage 2 user from their current host to a new target host.
     * Removes them from the old host's left/right slot, places them in the target's empty slot.
     * Triggers onStage2Completed if the target's slots become full after placement.
     */
    @Transactional
    public String moveStage2Partner(User userToMove, User newHost) {
        int level = userToMove.getCurrentLevel();

        // Remove from current host (if any)
        Optional<User> currentHostOpt = userRepository.findStage2HostOf(userToMove);
        if (currentHostOpt.isPresent()) {
            User oldHost = userRepository.findByIdForUpdate(currentHostOpt.get().getId())
                    .orElse(currentHostOpt.get());
            if (userToMove.equals(oldHost.getFixedPartnerLeft())) {
                oldHost.setFixedPartnerLeft(null);
            } else if (userToMove.equals(oldHost.getFixedPartnerRight())) {
                oldHost.setFixedPartnerRight(null);
            }
            userRepository.save(oldHost);
        }

        // Place under new host
        User lockedHost = userRepository.findByIdForUpdate(newHost.getId())
                .orElseThrow(() -> new IllegalStateException("New host not found: " + newHost.getId()));

        String slot;
        if (lockedHost.getFixedPartnerLeft() == null) {
            lockedHost.setFixedPartnerLeft(userToMove);
            slot = "LEFT";
        } else if (lockedHost.getFixedPartnerRight() == null) {
            lockedHost.setFixedPartnerRight(userToMove);
            slot = "RIGHT";
        } else {
            throw new IllegalStateException("New host " + newHost.getId() + " already has both Stage 2 partners");
        }
        userRepository.save(lockedHost);

        if (lockedHost.getFixedPartnerLeft() != null && lockedHost.getFixedPartnerRight() != null) {
            onStage2Completed(lockedHost, level);
        }

        String from = currentHostOpt.map(h -> h.getFirstName() + " " + h.getLastName()).orElse("нет (не был размещён)");
        return "MOVED: " + userToMove.getFirstName() + " " + userToMove.getLastName()
                + " | FROM: " + from
                + " | TO: " + lockedHost.getFirstName() + " " + lockedHost.getLastName() + " (" + slot + ")";
    }

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
     * Checks all Stage-1 users whose matrix is already full (6 positions = tier1+tier2 >= 6)
     * but who never received the onStage1Completed trigger (e.g. last slot was an accelerator).
     */
    @Transactional
    public List<String> repairMissingStage1Completions() {
        List<String> report = new ArrayList<>();

        List<User> candidates = userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == greenecomall.enums.AccountStatus.ACTIVE)
                .filter(u -> u.getCurrentStage() == 1)
                .toList();

        for (User user : candidates) {
            int level = user.getCurrentLevel();
            int tier1 = treePositionRepo.countByParentAndLevelAndStage(user, level, 1);
            if (tier1 < 2) continue;

            int tier2 = treePositionRepo.findByParentAndLevelAndStage(user, level, 1).stream()
                    .mapToInt(c -> treePositionRepo.countByParentAndLevelAndStage(c.getUser(), level, 1))
                    .sum();

            if (tier1 + tier2 >= 6) {
                report.add("COMPLETING: " + user.getFirstName() + " " + user.getLastName()
                        + " (id=" + user.getId() + ", tier1=" + tier1 + ", tier2=" + tier2 + ")");
                onStage1Completed(user, level);
            }
        }

        if (report.isEmpty()) report.add("No missing Stage 1 completions found");
        return report;
    }

    /**
     * Finds all active users stuck on Stage 3 whose entire 6-person Stage-1 team has already
     * reached Stage 3, and triggers onStage3Completed for them.
     * Fixes the edge case where the root reached Stage 3 last (checkStage3Progress returned early).
     */
    @Transactional
    public List<String> repairStage3Completions() {
        List<String> report = new ArrayList<>();

        List<User> candidates = userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == greenecomall.enums.AccountStatus.ACTIVE)
                .filter(u -> u.getCurrentStage() == 3)
                .toList();

        for (User user : candidates) {
            int level = user.getCurrentLevel();
            if (allStage2FixedPartnersAtStage(user, 3)) {
                report.add("COMPLETING Stage 3: " + user.getFirstName() + " " + user.getLastName()
                        + " (id=" + user.getId() + ")");
                onStage3Completed(user, level);
            }
        }

        if (report.isEmpty()) report.add("No missing Stage 3 completions found");
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

        // Level 0 (Fast Start) graduation is triggered directly in placeNewFastStartUser,
        // not through this general-purpose check.
        if (fresh.getRegistrationPlan() == RegistrationPlan.FAST_START) return;

        // Standard: needs 6 positions (2 tiers)
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
        if (treeRoot == null) treeRoot = user; // user is the root of their own Stage-1 tree
        if (treeRoot.getCurrentStage() != 3) return; // root hasn't reached stage 3 yet itself

        boolean allAtStage3 = allStage2FixedPartnersAtStage(treeRoot, 3);
        if (allAtStage3) {
            onStage3Completed(treeRoot, level);
        }
    }

    /**
     * After user advances to Stage 4, checks if the user is a fixed partner of their inviter
     * and both partners are now on Stage 4 — which triggers Stage 4 completion for the inviter.
     */
    private void checkStage4Progress(User user, int level) {
        User host = userRepository.findStage2HostOf(user).orElse(null);
        if (host == null) return;

        host = userRepository.findById(host.getId()).orElse(host);
        if (host.getCurrentStage() != 4) return;

        boolean leftDone  = host.getFixedPartnerLeft()  != null
                && userRepository.findById(host.getFixedPartnerLeft().getId())
                        .map(u -> u.getCurrentStage() >= 4).orElse(false);
        boolean rightDone = host.getFixedPartnerRight() != null
                && userRepository.findById(host.getFixedPartnerRight().getId())
                        .map(u -> u.getCurrentStage() >= 4).orElse(false);

        if (leftDone && rightDone) {
            onStage4Completed(host, level);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL: STAGE 2 PARTNER ASSIGNMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * When user completes Stage 1 they fill a Stage 2 slot under the closest ancestor
     * in the tree who is currently on Stage 2. Walks UP the tree_positions parent chain
     * so tier-2+ completers correctly bubble up past intermediate nodes.
     *
     * Fallback: if no ancestor on Stage 2 found (e.g. everyone above already passed Stage 2),
     * places the user under the earliest Fast Start graduate currently waiting on Stage 2.
     */
    private void fillStage2UnderInviter(User user, int level) {
        User ancestor = findFirstStage2Ancestor(user, level);

        if (ancestor == null) {
            placeUnderFastStartGraduate(user, level);
            return;
        }

        // BFS the Stage 2 team to find the weakest link:
        // priority 1 — has exactly 1 partner (needs just 1 more); priority 2 — has 0 partners.
        // Within each priority, BFS left-to-right order is preserved naturally.
        User target = findWeakestStage2Target(ancestor, level);
        if (target == null) {
            placeUnderFastStartGraduate(user, level);
            return;
        }

        User locked = userRepository.findByIdForUpdate(target.getId())
                .orElseThrow(() -> new IllegalStateException("Stage2 target not found: " + target.getId()));

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
     * BFS through the Stage 2 team rooted at `root` (via fixedPartnerLeft/Right links).
     * Returns the weakest candidate: has exactly 1 partner first (needs 1 more to complete),
     * then 0 partners. Within each priority group, BFS left-to-right order is preserved.
     */
    private User findWeakestStage2Target(User root, int level) {
        Queue<User> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(root);

        List<User> onePartner  = new ArrayList<>();
        List<User> zeroPartner = new ArrayList<>();

        while (!queue.isEmpty()) {
            User cur = queue.poll();
            if (!visited.add(cur.getId())) continue;

            User fresh = userRepository.findById(cur.getId()).orElse(null);
            if (fresh == null) continue;

            if (fresh.getCurrentLevel() == level && fresh.getCurrentStage() == 2) {
                int partners = (fresh.getFixedPartnerLeft()  != null ? 1 : 0)
                             + (fresh.getFixedPartnerRight() != null ? 1 : 0);
                if      (partners == 1) onePartner.add(fresh);
                else if (partners == 0) zeroPartner.add(fresh);
            }

            // Traverse deeper: left first (BFS left-to-right)
            if (fresh.getFixedPartnerLeft()  != null) queue.add(fresh.getFixedPartnerLeft());
            if (fresh.getFixedPartnerRight() != null) queue.add(fresh.getFixedPartnerRight());
        }

        if (!onePartner.isEmpty())  return onePartner.get(0);
        if (!zeroPartner.isEmpty()) return zeroPartner.get(0);
        return null;
    }

    /**
     * Fallback placement: when a user completed Stage 1 but has no ancestor on Stage 2,
     * place them under the earliest-activated Fast Start graduate who is on Stage 2
     * and still has an empty fixed partner slot (left before right).
     */
    private void placeUnderFastStartGraduate(User user, int level) {
        List<User> candidates = userRepository.findFastStartStage2WithEmptySlots().stream()
                .sorted(Comparator.comparingInt((User u) -> {
                    int p = (u.getFixedPartnerLeft() != null ? 1 : 0)
                          + (u.getFixedPartnerRight() != null ? 1 : 0);
                    return p == 1 ? 0 : 1;
                }))
                .collect(java.util.stream.Collectors.toList());

        for (User candidate : candidates) {
            User locked = userRepository.findByIdForUpdate(candidate.getId()).orElse(null);
            if (locked == null || locked.getCurrentStage() != 2) continue;

            if (locked.getFixedPartnerLeft() == null) {
                locked.setFixedPartnerLeft(user);
                userRepository.save(locked);
                notificationService.send(locked, NotificationType.NEW_MEMBER,
                        "Этап 2 — левая позиция занята",
                        user.getFirstName() + " " + user.getLastName() + " встал на Этап 2 (слева)");
                log.info("Fallback Stage2: {} placed as LEFT partner of FastStart graduate {}",
                        user.getId(), locked.getId());
                return;
            } else if (locked.getFixedPartnerRight() == null) {
                locked.setFixedPartnerRight(user);
                userRepository.save(locked);
                notificationService.send(locked, NotificationType.NEW_MEMBER,
                        "Этап 2 — правая позиция занята",
                        user.getFirstName() + " " + user.getLastName() + " встал на Этап 2 (справа)");
                onStage2Completed(locked, level);
                log.info("Fallback Stage2: {} placed as RIGHT partner of FastStart graduate {}",
                        user.getId(), locked.getId());
                return;
            }
        }

        log.warn("Stage2 fallback: no Fast Start graduate with empty slot found for user {}", user.getId());
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
     * Priority:
     * 1. If user's own tree already has an accelerator → stack into that branch.
     * 2. If an ancestor's tree already has accelerators → stack into that same branch.
     * 3. No existing accelerators anywhere:
     *    a. If user's own Stage-1 tree is still incomplete (<6 real members) → place in own weak branch.
     *    b. If user's own Stage-1 tree is already COMPLETE (6 real members) → walk UP to the nearest
     *       ancestor whose subtree has free Stage-1 slots, then BFS left-to-right from there.
     *       This avoids drilling into tier-3+ of an already-complete matrix.
     */
    @Transactional
    public void placeAccelerator(User user, int level) {
        List<TreePosition> directChildren = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);

        // 1. Stack if own tree has existing accelerators
        Optional<User> stackBranch = directChildren.stream()
                .filter(c -> !c.getIsAccelerator())
                .filter(c -> countAcceleratorsInSubTree(c.getUser(), level) > 0)
                .max(Comparator.comparingInt(c -> countAcceleratorsInSubTree(c.getUser(), level)))
                .map(TreePosition::getUser);

        if (stackBranch.isPresent()) {
            User target = findAcceleratorStackTarget(stackBranch.get(), level);
            bfsPlaceAccelerator(user, target, level);
        } else {
            // 2. Stack with ancestor's existing accelerators
            User ancestor = findAncestorWithAccelerators(user, level);
            if (ancestor != null) {
                List<TreePosition> ancestorChildren = treePositionRepo.findByParentAndLevelAndStage(ancestor, level, 1);
                Optional<User> ancestorStack = ancestorChildren.stream()
                        .filter(c -> !c.getIsAccelerator())
                        .filter(c -> countAcceleratorsInSubTree(c.getUser(), level) > 0)
                        .max(Comparator.comparingInt(c -> countAcceleratorsInSubTree(c.getUser(), level)))
                        .map(TreePosition::getUser);
                if (ancestorStack.isPresent()) {
                    User target = findAcceleratorStackTarget(ancestorStack.get(), level);
                    bfsPlaceAccelerator(user, target, level);
                } else {
                    placeInOwnWeakBranch(user, level, directChildren);
                }
            } else {
                // 3. No accelerators anywhere — check if own tree is already complete
                long realTier1 = directChildren.stream().filter(c -> !c.getIsAccelerator()).count();
                long realTier2 = directChildren.stream()
                        .filter(c -> !c.getIsAccelerator())
                        .mapToLong(c -> treePositionRepo.findByParentAndLevelAndStage(c.getUser(), level, 1)
                                .stream().filter(t -> !t.getIsAccelerator()).count())
                        .sum();
                boolean ownTreeComplete = (realTier1 + realTier2 >= 6);

                if (ownTreeComplete) {
                    // Own Stage-1 matrix is full — walk UP to nearest ancestor with free slots
                    // and BFS left-to-right from there (avoids drilling into tier-3+ of a full matrix)
                    User upline = findNearestAncestorWithFreeSlots(user, level);
                    if (upline != null) {
                        bfsPlaceAccelerator(user, upline, level);
                    } else {
                        // Last resort: no upline with free slots found, use own tree
                        placeInOwnWeakBranch(user, level, directChildren);
                    }
                } else {
                    placeInOwnWeakBranch(user, level, directChildren);
                }
            }
        }

        notificationService.send(user, NotificationType.ACCELERATOR_PLACED,
                "Ускоритель размещён",
                "Система автоматически разместила ускоритель в команду.");
    }

    private void placeInOwnWeakBranch(User user, int level, List<TreePosition> directChildren) {
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

    /**
     * Walks UP the Stage-1 parent chain from user to find the nearest ancestor
     * whose direct children have existing accelerators somewhere in their sub-trees.
     * Returns that ancestor so its tree context can be used for stacking.
     */
    private User findAncestorWithAccelerators(User user, int level) {
        Optional<TreePosition> pos = treePositionRepo.findByUserAndLevelAndStage(user, level, 1);
        if (pos.isEmpty() || pos.get().getParent() == null) return null;

        User parent = pos.get().getParent();
        while (parent != null) {
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(parent, level, 1);
            boolean anyBranchHasAccelerators = children.stream()
                    .filter(c -> !c.getIsAccelerator())
                    .anyMatch(c -> countAcceleratorsInSubTree(c.getUser(), level) > 0);
            if (anyBranchHasAccelerators) return parent;

            Optional<TreePosition> parentPos = treePositionRepo.findByUserAndLevelAndStage(parent, level, 1);
            if (parentPos.isEmpty() || parentPos.get().getParent() == null) break;
            parent = parentPos.get().getParent();
        }
        return null;
    }

    /**
     * Walks UP the Stage-1 parent chain from user and returns the nearest ancestor
     * whose subtree contains at least one node with fewer than 2 real (non-accelerator) children.
     * Used to redirect accelerator placement away from a complete Stage-1 tree.
     */
    private User findNearestAncestorWithFreeSlots(User user, int level) {
        Optional<TreePosition> pos = treePositionRepo.findByUserAndLevelAndStage(user, level, 1);
        if (pos.isEmpty() || pos.get().getParent() == null) return null;

        User parent = pos.get().getParent();
        while (parent != null) {
            User fresh = userRepository.findById(parent.getId()).orElse(null);
            if (fresh == null) break;

            if (hasFreeSlotsInSubTree(fresh, level)) return fresh;

            Optional<TreePosition> parentPos = treePositionRepo.findByUserAndLevelAndStage(fresh, level, 1);
            if (parentPos.isEmpty() || parentPos.get().getParent() == null) break;
            parent = parentPos.get().getParent();
        }
        return null;
    }

    /**
     * Returns true if any node in root's Stage-1 subtree has fewer than 2 real children
     * (i.e. there is at least one free slot where an accelerator could be placed meaningfully).
     */
    private boolean hasFreeSlotsInSubTree(User root, int level) {
        Queue<User> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            User cur = queue.poll();
            if (!visited.add(cur.getId())) continue;
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(cur, level, 1);
            long realChildren = children.stream().filter(c -> !c.getIsAccelerator()).count();
            // Only count as "free" if placing here would eventually be cleaned up
            if (realChildren < 2 && canAcceleratorBeCleanedUp(cur, level)) return true;
            children.stream().filter(c -> !c.getIsAccelerator()).forEach(c -> queue.add(c.getUser()));
        }
        return false;
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

    /**
     * Drills down from the given node following the path with existing accelerators.
     * Stops at the node where:
     * - one child has accelerators in its subtree (the "hot" side)
     * - another child does NOT have accelerators AND still has a free slot itself
     * This ensures BFS runs only within the targeted branch, not sibling branches.
     */
    private User findAcceleratorStackTarget(User node, int level) {
        List<TreePosition> realChildren = treePositionRepo.findByParentAndLevelAndStage(node, level, 1)
                .stream().filter(c -> !c.getIsAccelerator()).toList();

        if (realChildren.isEmpty()) return node;

        Optional<TreePosition> hotChild = realChildren.stream()
                .filter(c -> countAcceleratorsInSubTree(c.getUser(), level) > 0)
                .max(Comparator.comparingInt(c -> countAcceleratorsInSubTree(c.getUser(), level)));

        if (hotChild.isEmpty()) return node;

        // Check if a sibling exists without accelerators AND still has space for children
        boolean siblingNeedsMore = realChildren.stream()
                .filter(c -> !c.getUser().getId().equals(hotChild.get().getUser().getId()))
                .anyMatch(c -> countAcceleratorsInSubTree(c.getUser(), level) == 0
                        && treePositionRepo.findByParentAndLevelAndStage(c.getUser(), level, 1).size() < 2);

        if (siblingNeedsMore) {
            return node; // BFS from this node fills the sibling's empty slot(s)
        }

        // All siblings are full — drill deeper into the hot child
        return findAcceleratorStackTarget(hotChild.get().getUser(), level);
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
                // Skip slots inside already-completed matrices: when both `current` and its
                // tree-parent are past Stage 1, checkStage1UpTheChain will return early and
                // the accelerator will never be removed (stuck forever).
                if (!canAcceleratorBeCleanedUp(current, level)) {
                    children.stream()
                            .filter(c -> !c.getIsAccelerator())
                            .sorted(Comparator.comparingInt(TreePosition::getPosition))
                            .forEach(c -> queue.add(c.getUser()));
                    continue;
                }
                treePositionRepo.save(TreePosition.builder()
                        .user(owner)
                        .parent(current)
                        .level(level)
                        .stage(1)
                        .position(freeSlot)
                        .isAccelerator(true)
                        .stageStatus(StageStatus.IN_PROGRESS)
                        .build());
                checkStage1UpTheChain(current, level);
                return;
            }

            children.stream()
                    .filter(c -> !c.getIsAccelerator())
                    .sorted(Comparator.comparingInt(TreePosition::getPosition))
                    .forEach(c -> queue.add(c.getUser()));
        }
    }

    /**
     * Returns true if placing an accelerator as a child of `node` will eventually
     * be cleaned up by a Stage-1 completion event.
     * This is the case when `node` itself OR its direct parent in the tree is still
     * at Stage 1 — so checkStage1UpTheChain will find an active matrix to complete.
     */
    private boolean canAcceleratorBeCleanedUp(User node, int level) {
        User fresh = userRepository.findById(node.getId()).orElse(node);
        if (fresh.getCurrentLevel() == level && fresh.getCurrentStage() == 1) return true;

        Optional<TreePosition> pos = treePositionRepo.findByUserAndLevelAndStage(node, level, 1);
        if (pos.isPresent() && pos.get().getParent() != null) {
            User parent = userRepository.findById(pos.get().getParent().getId()).orElse(null);
            if (parent != null && parent.getCurrentLevel() == level && parent.getCurrentStage() == 1) {
                return true;
            }
        }
        return false;
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
    /**
     * Returns true if all 6 members of root's Stage-2 fixed-partner tree have currentStage >= minStage.
     * Tree = root.fixedPartnerLeft/Right (tier-1) + their fixedPartnerLeft/Right (tier-2).
     */
    private boolean allStage2FixedPartnersAtStage(User root, int minStage) {
        User left1  = root.getFixedPartnerLeft()  != null
                ? userRepository.findById(root.getFixedPartnerLeft().getId()).orElse(null)  : null;
        User right1 = root.getFixedPartnerRight() != null
                ? userRepository.findById(root.getFixedPartnerRight().getId()).orElse(null) : null;
        if (left1 == null || right1 == null) return false;
        if (left1.getCurrentStage() < minStage || right1.getCurrentStage() < minStage) return false;

        User ll = left1.getFixedPartnerLeft()  != null ? userRepository.findById(left1.getFixedPartnerLeft().getId()).orElse(null)  : null;
        User lr = left1.getFixedPartnerRight() != null ? userRepository.findById(left1.getFixedPartnerRight().getId()).orElse(null) : null;
        User rl = right1.getFixedPartnerLeft() != null ? userRepository.findById(right1.getFixedPartnerLeft().getId()).orElse(null)  : null;
        User rr = right1.getFixedPartnerRight() != null ? userRepository.findById(right1.getFixedPartnerRight().getId()).orElse(null) : null;
        if (ll == null || lr == null || rl == null || rr == null) return false;
        return ll.getCurrentStage() >= minStage && lr.getCurrentStage() >= minStage
                && rl.getCurrentStage() >= minStage && rr.getCurrentStage() >= minStage;
    }

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
        // Stage 3 has no separate tree_positions rows — reuse stage=1 positions and check currentStage>=3
        if (stage == 3) {
            return buildStage3Tree(user, level);
        }

        TreePosition rootPos = treePositionRepo.findByUserAndLevelAndStage(user, level, stage).orElse(null);
        StageStatus status = rootPos != null ? rootPos.getStageStatus() : StageStatus.WAITING;

        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(user, level, stage);

        // Level 0 (Fast Start mini-tree): only 1 slot needed, no tier-2, no accelerators
        boolean isFastStartTree = (level == 0);
        int total  = isFastStartTree ? 1 : 6;
        int filled = isFastStartTree
                ? tier1.size()
                : tier1.size() + tier1.stream()
                        .mapToInt(c -> treePositionRepo.countByParentAndLevelAndStage(c.getUser(), level, stage))
                        .sum();

        boolean hasAccelerator = !isFastStartTree && (
                tier1.stream().anyMatch(TreePosition::getIsAccelerator)
                || tier1.stream().anyMatch(c ->
                        treePositionRepo.findByParentAndLevelAndStage(c.getUser(), level, stage)
                                .stream().anyMatch(TreePosition::getIsAccelerator)));

        // Stage 1 Level 1: infinite BFS tree; Level 0: depth 1; everything else: up to 6 (2 tiers)
        int depth = isFastStartTree ? 1 : (stage == 1 && level == 1) ? 20 : 3;

        TreeNodeResponse rootNode = buildNode(user, level, stage, depth);

        return TreeResponse.builder()
                .root(rootNode)
                .stageStatus(status)
                .progress(TreeResponse.TreeProgress.builder().filled(filled).total(total).build())
                .accelerator(TreeResponse.AcceleratorInfo.builder().active(hasAccelerator).build())
                .fastStartNumber(isFastStartTree ? user.getFastStartNumber() : null)
                .build();
    }

    /**
     * Stage 3 tree: та же структура что и Stage 1 (tree_positions stage=1),
     * но статусы узлов определяются по currentStage >= 3.
     * Глубина — как у Stage 1 Level 1 (BFS без лимита).
     */
    private TreeResponse buildStage3Tree(User user, int level) {
        boolean userReachedStage3 = user.getCurrentLevel() > level
                || (user.getCurrentLevel() == level && user.getCurrentStage() >= 3);

        StageStatus rootStatus = !userReachedStage3 ? StageStatus.WAITING
                : user.getCurrentStage() > 3 ? StageStatus.COMPLETED : StageStatus.IN_PROGRESS;

        if (!userReachedStage3) {
            TreeNodeResponse rootNode = TreeNodeResponse.builder()
                    .userId(user.getId())
                    .name(user.getFirstName() + " " + user.getLastName())
                    .initials(initials(user))
                    .stageStatus(StageStatus.WAITING)
                    .children(List.of())
                    .build();
            return TreeResponse.builder()
                    .root(rootNode)
                    .stageStatus(StageStatus.WAITING)
                    .progress(TreeResponse.TreeProgress.builder().filled(0).total(0).build())
                    .accelerator(TreeResponse.AcceleratorInfo.builder().active(false).build())
                    .build();
        }

        int[] counts = {0, 0}; // [filled, total]
        TreeNodeResponse rootNode = buildStage3DeepNode(user, level, 20, counts);

        return TreeResponse.builder()
                .root(rootNode)
                .stageStatus(rootStatus)
                .progress(TreeResponse.TreeProgress.builder().filled(counts[0]).total(counts[1]).build())
                .accelerator(TreeResponse.AcceleratorInfo.builder().active(false).build())
                .build();
    }

    private TreeNodeResponse buildStage3DeepNode(User user, int level, int depth, int[] counts) {
        List<TreeNodeResponse> childNodes = new ArrayList<>();

        if (depth > 0) {
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);
            children.stream()
                    .sorted(Comparator.comparingInt(TreePosition::getPosition))
                    .forEach(child -> {
                        boolean accel = child.getIsAccelerator();
                        if (!accel) {
                            counts[1]++;
                            if (child.getUser().getCurrentStage() >= 3) counts[0]++;
                        }
                        StageStatus childStatus = accel ? StageStatus.WAITING
                                : child.getUser().getCurrentStage() > 3 ? StageStatus.COMPLETED
                                : child.getUser().getCurrentStage() >= 3 ? StageStatus.IN_PROGRESS
                                : StageStatus.WAITING;
                        childNodes.add(TreeNodeResponse.builder()
                                .userId(accel ? null : child.getUser().getId())
                                .name(accel ? null : child.getUser().getFirstName() + " " + child.getUser().getLastName())
                                .initials(accel ? null : initials(child.getUser()))
                                .position(child.getPosition())
                                .isAccelerator(accel)
                                .stageStatus(childStatus)
                                .children(accel ? List.of() : buildStage3DeepNode(child.getUser(), level, depth - 1, counts).children())
                                .build());
                    });
        }

        StageStatus userStatus = user.getCurrentStage() > 3 ? StageStatus.COMPLETED
                : user.getCurrentStage() >= 3 ? StageStatus.IN_PROGRESS : StageStatus.WAITING;

        return TreeNodeResponse.builder()
                .userId(user.getId())
                .name(user.getFirstName() + " " + user.getLastName())
                .initials(initials(user))
                .stageStatus(userStatus)
                .children(childNodes)
                .build();
    }

    private TreeResponse buildFixedPartnersTree(User user, int level, int stage) {
        // Don't show partners if user hasn't reached this stage yet
        boolean userReachedStage = user.getCurrentLevel() > level
                || (user.getCurrentLevel() == level && user.getCurrentStage() >= stage);

        User left  = userReachedStage && user.getFixedPartnerLeft()  != null
                ? userRepository.findById(user.getFixedPartnerLeft().getId()).orElse(null)  : null;
        User right = userReachedStage && user.getFixedPartnerRight() != null
                ? userRepository.findById(user.getFixedPartnerRight().getId()).orElse(null) : null;

        List<TreeNodeResponse> children = new ArrayList<>();
        if (left != null && left.getCurrentStage() >= stage) {
            children.add(TreeNodeResponse.builder()
                    .userId(left.getId())
                    .name(left.getFirstName() + " " + left.getLastName())
                    .initials(initials(left))
                    .position(1)
                    .isAccelerator(false)
                    .stageStatus(left.getCurrentStage() > stage ? StageStatus.COMPLETED : StageStatus.IN_PROGRESS)
                    .children(buildFixedPartnersTree(left, level, stage).root().children())
                    .build());
        }
        if (right != null && right.getCurrentStage() >= stage) {
            children.add(TreeNodeResponse.builder()
                    .userId(right.getId())
                    .name(right.getFirstName() + " " + right.getLastName())
                    .initials(initials(right))
                    .position(2)
                    .isAccelerator(false)
                    .stageStatus(right.getCurrentStage() > stage ? StageStatus.COMPLETED : StageStatus.IN_PROGRESS)
                    .children(buildFixedPartnersTree(right, level, stage).root().children())
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
    // API: STAGE 2 RACE — кто идёт впереди в гонке за фикс. позицию
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public greenecomall.dto.response.Stage2RaceResponse getStage2Race(User user, int level) {
        boolean stage2Completed = user.getCurrentStage() > 2
                || user.getFixedPartnerLeft() != null && user.getFixedPartnerRight() != null;

        UUID leftId  = user.getFixedPartnerLeft()  != null ? user.getFixedPartnerLeft().getId()  : null;
        UUID rightId = user.getFixedPartnerRight() != null ? user.getFixedPartnerRight().getId() : null;

        // Собираем всех 6 участников Stage 1 дерева
        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);
        tier1.sort(Comparator.comparingInt(TreePosition::getPosition));

        List<greenecomall.dto.response.Stage2RaceResponse.RaceEntry> entries = new ArrayList<>();
        int posNum = 2;

        for (TreePosition t1 : tier1) {
            if (t1.getIsAccelerator()) continue;
            entries.add(toRaceEntry(t1.getUser(), posNum++, level, leftId, rightId));

            List<TreePosition> tier2 = treePositionRepo.findByParentAndLevelAndStage(t1.getUser(), level, 1);
            tier2.sort(Comparator.comparingInt(TreePosition::getPosition));
            for (TreePosition t2 : tier2) {
                if (!t2.getIsAccelerator()) {
                    entries.add(toRaceEntry(t2.getUser(), posNum++, level, leftId, rightId));
                }
            }
        }

        // Сортируем: фикс. партнёры первыми, затем по убыванию прогресса; берём только топ-2
        entries.sort(Comparator
                .comparingInt((greenecomall.dto.response.Stage2RaceResponse.RaceEntry e) -> e.isFixedPartner() ? 0 : 1)
                .thenComparingInt(e -> -e.filled()));

        List<greenecomall.dto.response.Stage2RaceResponse.RaceEntry> top2 =
                entries.stream().limit(2).collect(java.util.stream.Collectors.toList());

        return greenecomall.dto.response.Stage2RaceResponse.builder()
                .candidates(top2)
                .stage2Completed(stage2Completed)
                .build();
    }

    private greenecomall.dto.response.Stage2RaceResponse.RaceEntry toRaceEntry(
            User member, int posNum, int level, UUID leftId, UUID rightId) {

        int tier1Count = treePositionRepo.countByParentAndLevelAndStage(member, level, 1);
        int tier2Count = treePositionRepo.findByParentAndLevelAndStage(member, level, 1).stream()
                .mapToInt(c -> treePositionRepo.countByParentAndLevelAndStage(c.getUser(), level, 1))
                .sum();
        int filled = Math.min(tier1Count + tier2Count, 6);

        boolean isLeft  = leftId  != null && leftId.equals(member.getId());
        boolean isRight = rightId != null && rightId.equals(member.getId());

        return greenecomall.dto.response.Stage2RaceResponse.RaceEntry.builder()
                .userId(member.getId())
                .name(member.getFirstName() + " " + member.getLastName())
                .initials(initials(member))
                .treePosition(posNum)
                .filled(filled)
                .total(6)
                .currentStage(member.getCurrentStage())
                .isFixedPartner(isLeft || isRight)
                .fixedPartnerSlot(isLeft ? 1 : isRight ? 2 : 0)
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
    // API: MEMBER CARD — карточка участника при клике на узел дерева
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public greenecomall.dto.response.MemberCardResponse getMemberCard(java.util.UUID memberId) {
        User member = userRepository.findById(memberId)
                .orElseThrow(() -> greenecomall.exception.BusinessException.of(
                        greenecomall.exception.ErrorCode.USER_NOT_FOUND));

        int level = member.getCurrentLevel();
        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(member, level, 1);

        // Считаем левую и правую ветки
        int leftSize  = countBranchDeep(member, level, 1, tier1);
        int rightSize = countBranchDeep(member, level, 2, tier1);

        String referralCode = member.getReferralCode();
        String referralLink = baseUrl + "/join?ref=" + referralCode;

        return greenecomall.dto.response.MemberCardResponse.builder()
                .userId(member.getId())
                .name(member.getFirstName() + " " + member.getLastName())
                .initials(initials(member))
                .currentLevel(member.getCurrentLevel())
                .currentStage(member.getCurrentStage())
                .accountStatus(member.getAccountStatus())
                .referralCode(referralCode)
                .referralLink(referralLink)
                .joinedAt(member.getActivatedAt())
                .teamSize(countDownline(member))
                .leftBranchSize(leftSize)
                .rightBranchSize(rightSize)
                .build();
    }

    private int countBranchDeep(User root, int level, int side, List<TreePosition> tier1) {
        Optional<TreePosition> directOpt = tier1.stream()
                .filter(c -> c.getPosition() == side && !c.getIsAccelerator())
                .findFirst();
        if (directOpt.isEmpty()) return 0;

        int count = 0;
        Queue<User> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(directOpt.get().getUser());
        visited.add(directOpt.get().getUser().getId());

        while (!queue.isEmpty()) {
            User current = queue.poll();
            count++;
            for (TreePosition child : treePositionRepo.findByParentAndLevelAndStage(current, level, 1)) {
                if (!child.getIsAccelerator() && visited.add(child.getUser().getId())) {
                    queue.add(child.getUser());
                }
            }
        }
        return count;
    }

    private int countDownline(User root) {
        int count = 0;
        Queue<User> queue = new ArrayDeque<>(userRepository.findByInviter(root));
        while (!queue.isEmpty()) {
            User current = queue.poll();
            count++;
            queue.addAll(userRepository.findByInviter(current));
        }
        return count;
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

        int filled = leftInfo.size() + rightInfo.size();

        return BranchStatsResponse.builder()
                .left(leftInfo)
                .right(rightInfo)
                .totalFilled(filled)
                .total(filled) // total = all real members across both branches
                .build();
    }

    private BranchStatsResponse.BranchInfo buildBranchInfo(User root, int level, int side,
            List<TreePosition> tier1) {
        List<BranchStatsResponse.BranchMember> members = new ArrayList<>();

        Optional<TreePosition> directOpt = tier1.stream()
                .filter(c -> c.getPosition() == side)
                .findFirst();

        if (directOpt.isPresent()) {
            // BFS вглубь без ограничений; ускорители пропускаем —
            // у них user = root, что сломало бы обход всего дерева
            Queue<User> queue = new ArrayDeque<>();
            Set<UUID> visited = new HashSet<>();

            TreePosition direct = directOpt.get();
            if (!direct.getIsAccelerator()) {
                queue.add(direct.getUser());
                members.add(toBranchMember(direct));
                visited.add(direct.getUser().getId());
            }

            while (!queue.isEmpty()) {
                User current = queue.poll();
                for (TreePosition child : treePositionRepo.findByParentAndLevelAndStage(current, level, 1)) {
                    if (!child.getIsAccelerator() && visited.add(child.getUser().getId())) {
                        members.add(toBranchMember(child));
                        queue.add(child.getUser());
                    }
                }
            }
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
