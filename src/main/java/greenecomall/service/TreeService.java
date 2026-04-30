package greenecomall.service;

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

    // BonusService injected via setter to avoid circular dependency
    private BonusService bonusService;

    public void setBonusService(BonusService bonusService) {
        this.bonusService = bonusService;
    }

    /**
     * Places a newly activated user into the inviter's tree using BFS.
     * Called within activateUser transaction from PaymentService.
     */
    @Transactional
    public void placeNewUser(User inviter, User newUser) {
        placeInTree(inviter, newUser, inviter.getCurrentLevel(), 1);
    }

    /**
     * BFS placement: find first node in inviter's Stage-1 tree that has fewer than 2 children.
     * Left branch preferred when counts are equal.
     */
    private void placeInTree(User root, User newUser, int level, int stage) {
        // BFS over tree using Queue — iterative, not recursive
        Queue<User> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            User current = queue.poll();
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(current, level, stage);

            boolean hasLeft = children.stream().anyMatch(c -> c.getPosition() == 1);
            boolean hasRight = children.stream().anyMatch(c -> c.getPosition() == 2);

            if (!hasLeft) {
                createPosition(newUser, current, level, stage, 1);
                afterPlacement(root, newUser, level, stage);
                return;
            }
            if (!hasRight) {
                createPosition(newUser, current, level, stage, 2);
                afterPlacement(root, newUser, level, stage);
                return;
            }

            // Both slots filled — add children to queue (left first)
            children.stream()
                    .filter(c -> !c.getIsAccelerator())
                    .sorted(Comparator.comparingInt(TreePosition::getPosition))
                    .forEach(c -> queue.add(c.getUser()));
        }

        log.error("BFS could not find a free position in tree of user {}", root.getId());
    }

    private void createPosition(User user, User parent, int level, int stage, int position) {
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

    /** After placing user — check if root's Stage-1 tree is now complete (6 positions filled). */
    private void afterPlacement(User root, User newUser, int level, int stage) {
        notificationService.send(root, NotificationType.NEW_MEMBER,
                "Новый участник",
                newUser.getFirstName() + " " + newUser.getLastName() + " присоединился к вашей команде");

        if (stage == 1) {
            int filled = countStage1Children(root, level);
            if (filled >= 6) {
                onStage1Completed(root, level);
            }
        }
    }

    /** Counts all non-accelerator positions under root at given level/stage=1 (max depth 2). */
    private int countStage1Children(User root, int level) {
        return treePositionRepo.countByParentAndLevelAndStage(root, level, 1)
                + treePositionRepo.findByParentAndLevelAndStage(root, level, 1)
                .stream()
                .mapToInt(pos -> treePositionRepo.countByParentAndLevelAndStage(pos.getUser(), level, 1))
                .sum();
    }

    /**
     * Called when a user fills all 6 Stage-1 positions.
     */
    @Transactional
    public void onStage1Completed(User user, int level) {
        // Mark Stage-1 position as COMPLETED
        treePositionRepo.findByUserAndLevelAndStage(user, level, 1).ifPresent(pos -> {
            pos.setStageStatus(StageStatus.COMPLETED);
            pos.setCompletedStageAt(LocalDateTime.now());
            treePositionRepo.save(pos);
        });

        // Confirm pending bonuses for inviter and grandparent
        if (bonusService != null) {
            bonusService.confirmBonusesForUser(user);
        }

        // Advance to Stage 2
        user.setCurrentStage(2);
        userRepository.save(user);

        // Try to fill Stage-2 slot under any ancestor who is currently on Stage 2
        tryFillStage2Slot(user, level);

        notificationService.send(user, NotificationType.STAGE_COMPLETE,
                "Этап 1 завершён",
                "Вы успешно завершили Этап 1 уровня " + level + ". Переход на Этап 2.");
    }

    /**
     * Walk up the inviter chain and find an ancestor on Stage 2 with an open slot.
     * On Level 1 this implements the "race" (first finisher → left, second → right).
     * On Levels 2-4 fixed partners are already set, so no race is needed.
     */
    private void tryFillStage2Slot(User user, int level) {
        User ancestor = user.getInviter();
        while (ancestor != null) {
            if (ancestor.getCurrentStage() == 2 && ancestor.getCurrentLevel() == level) {
                boolean filled = assignStage2Partner(ancestor, user, level);
                if (filled) return;
            }
            ancestor = ancestor.getInviter();
        }
    }

    /**
     * Returns true if the slot was filled and, if both slots are now full, triggers Stage-2 completion.
     */
    private boolean assignStage2Partner(User ancestor, User partner, int level) {
        // Reload with lock
        ancestor = userRepository.findByIdForUpdate(ancestor.getId()).orElse(ancestor);

        if (ancestor.getFixedPartnerLeft() == null) {
            ancestor.setFixedPartnerLeft(partner);
            userRepository.save(ancestor);

            notificationService.send(ancestor, NotificationType.NEW_MEMBER,
                    "Партнёр на Этапе 2",
                    partner.getFirstName() + " занял левую позицию на Этапе 2");
            return true;
        }

        if (ancestor.getFixedPartnerRight() == null) {
            ancestor.setFixedPartnerRight(partner);
            userRepository.save(ancestor);

            notificationService.send(ancestor, NotificationType.NEW_MEMBER,
                    "Партнёр на Этапе 2",
                    partner.getFirstName() + " занял правую позицию на Этапе 2");

            // Both slots filled — complete Stage 2
            onStage2Completed(ancestor, level);
            return true;
        }

        return false; // ancestor's stage 2 already full
    }

    /**
     * Called when both Stage-2 partners are assigned.
     * Advances ancestor to Stage 3 and places an accelerator.
     */
    @Transactional
    public void onStage2Completed(User user, int level) {
        user.setCurrentStage(3);
        userRepository.save(user);

        // Place accelerator on levels 1 and 2 only
        if (level <= 2) {
            placeAccelerator(user, level);
        }

        notificationService.send(user, NotificationType.STAGE_COMPLETE,
                "Этап 2 завершён",
                "Оба партнёра подтверждены. Вы на Этапе 3 уровня " + level + ".");
    }

    /**
     * Called when all 6 members of user's team have reached Stage 3.
     */
    @Transactional
    public void onStage3Completed(User user, int level) {
        if (bonusService != null) {
            bonusService.createStageBonuses(user, level, 3);
        }

        user.setCurrentStage(4);
        userRepository.save(user);

        notificationService.send(user, NotificationType.STAGE_COMPLETE,
                "Этап 3 завершён",
                "Все 6 участников команды достигли Этапа 3. Уровень " + level + " завершён!");
    }

    /**
     * Called when both fixed partners of user have reached Stage 4.
     * Advances user to next level (or marks as shareholder).
     */
    @Transactional
    public void onStage4Completed(User user, int level) {
        if (level < 4) {
            user.setCurrentLevel(level + 1);
            user.setCurrentStage(1);
            userRepository.save(user);

            notificationService.send(user, NotificationType.LEVEL_UP,
                    "Новый уровень!",
                    "Поздравляем! Вы перешли на уровень " + (level + 1) + ".");

            // Re-place user and their fixed partners into the new level tree
            User left = user.getFixedPartnerLeft();
            User right = user.getFixedPartnerRight();

            if (left != null) placeInTree(user, left, level + 1, 1);
            if (right != null) placeInTree(user, right, level + 1, 1);
        } else {
            // Level 4 complete → Shareholder
            notificationService.send(user, NotificationType.LEVEL_UP,
                    "Акционер!",
                    "Вы стали Акционером Green Eco Mall! Теперь вы получаете дивиденды.");
        }
    }

    /**
     * Places a virtual accelerator in the weaker branch of user's Stage-1 tree.
     * Only for levels 1 and 2.
     */
    @Transactional
    public void placeAccelerator(User user, int level) {
        int leftCount = countBranchSize(user, level, 1, 1);
        int rightCount = countBranchSize(user, level, 1, 2);

        int targetPosition = leftCount <= rightCount ? 1 : 2;

        // Find the direct child on the target side
        List<TreePosition> directChildren = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);
        Optional<TreePosition> targetChild = directChildren.stream()
                .filter(c -> c.getPosition() == targetPosition)
                .findFirst();

        if (targetChild.isEmpty()) {
            // The slot itself is free — place accelerator directly under user
            treePositionRepo.save(TreePosition.builder()
                    .user(user) // accelerator owned by root
                    .parent(user)
                    .level(level)
                    .stage(1)
                    .position(targetPosition)
                    .isAccelerator(true)
                    .stageStatus(StageStatus.IN_PROGRESS)
                    .build());
        } else {
            // BFS inside the weak branch to find first free slot
            User branchRoot = targetChild.get().getUser();
            placeBfsAccelerator(user, branchRoot, level);
        }

        notificationService.send(user, NotificationType.ACCELERATOR_PLACED,
                "Ускоритель размещён",
                "Система разместила ускоритель в слабую ветку вашего дерева.");
    }

    private void placeBfsAccelerator(User owner, User branchRoot, int level) {
        Queue<User> queue = new ArrayDeque<>();
        queue.add(branchRoot);

        while (!queue.isEmpty()) {
            User current = queue.poll();
            List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(current, level, 1);

            boolean hasLeft = children.stream().anyMatch(c -> c.getPosition() == 1);
            boolean hasRight = children.stream().anyMatch(c -> c.getPosition() == 2);

            if (!hasLeft) {
                treePositionRepo.save(TreePosition.builder()
                        .user(owner)
                        .parent(current)
                        .level(level)
                        .stage(1)
                        .position(1)
                        .isAccelerator(true)
                        .stageStatus(StageStatus.IN_PROGRESS)
                        .build());
                return;
            }
            if (!hasRight) {
                treePositionRepo.save(TreePosition.builder()
                        .user(owner)
                        .parent(current)
                        .level(level)
                        .stage(1)
                        .position(2)
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

    /** Count all users in a specific branch (position 1=left, 2=right) of user's Stage-1 tree. */
    private int countBranchSize(User root, int level, int stage, int side) {
        List<TreePosition> directChildren = treePositionRepo.findByParentAndLevelAndStage(root, level, stage);
        Optional<TreePosition> sideChild = directChildren.stream()
                .filter(c -> c.getPosition() == side)
                .findFirst();
        if (sideChild.isEmpty()) return 0;

        // BFS count within branch
        int count = 0;
        Queue<User> queue = new ArrayDeque<>();
        queue.add(sideChild.get().getUser());
        while (!queue.isEmpty()) {
            User current = queue.poll();
            count++;
            treePositionRepo.findByParentAndLevelAndStage(current, level, stage)
                    .forEach(c -> queue.add(c.getUser()));
        }
        return count;
    }

    /** Build tree response for API — recursive node building. */
    public greenecomall.dto.response.TreeResponse getTree(User user, int level, int stage) {
        TreePosition rootPos = treePositionRepo.findByUserAndLevelAndStage(user, level, stage).orElse(null);
        StageStatus status = rootPos != null ? rootPos.getStageStatus() : StageStatus.WAITING;

        greenecomall.dto.response.TreeNodeResponse rootNode = buildNode(user, level, stage);
        int filled = countStage1Children(user, level);

        boolean acceleratorActive = treePositionRepo
                .findByParentAndLevelAndStage(user, level, stage)
                .stream().anyMatch(TreePosition::getIsAccelerator);

        return greenecomall.dto.response.TreeResponse.builder()
                .root(rootNode)
                .stageStatus(status)
                .progress(greenecomall.dto.response.TreeResponse.TreeProgress.builder()
                        .filled(filled).total(6).build())
                .accelerator(greenecomall.dto.response.TreeResponse.AcceleratorInfo.builder()
                        .active(acceleratorActive).build())
                .build();
    }

    private greenecomall.dto.response.TreeNodeResponse buildNode(User user, int level, int stage) {
        List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(user, level, stage);
        List<greenecomall.dto.response.TreeNodeResponse> childNodes = new ArrayList<>();

        for (TreePosition child : children) {
            childNodes.add(greenecomall.dto.response.TreeNodeResponse.builder()
                    .userId(child.getUser().getId())
                    .name(child.getUser().getFirstName() + " " + child.getUser().getLastName())
                    .initials(initials(child.getUser()))
                    .position(child.getPosition())
                    .isAccelerator(child.getIsAccelerator())
                    .stageStatus(child.getStageStatus())
                    .children(child.getIsAccelerator() ? List.of() : buildChildNodes(child.getUser(), level, stage))
                    .build());
        }

        TreePosition pos = treePositionRepo.findByUserAndLevelAndStage(user, level, stage).orElse(null);
        return greenecomall.dto.response.TreeNodeResponse.builder()
                .userId(user.getId())
                .name(user.getFirstName() + " " + user.getLastName())
                .initials(initials(user))
                .stageStatus(pos != null ? pos.getStageStatus() : StageStatus.WAITING)
                .children(childNodes)
                .build();
    }

    private List<greenecomall.dto.response.TreeNodeResponse> buildChildNodes(User parent, int level, int stage) {
        List<TreePosition> children = treePositionRepo.findByParentAndLevelAndStage(parent, level, stage);
        List<greenecomall.dto.response.TreeNodeResponse> result = new ArrayList<>();
        for (TreePosition child : children) {
            result.add(greenecomall.dto.response.TreeNodeResponse.builder()
                    .userId(child.getUser().getId())
                    .name(child.getUser().getFirstName() + " " + child.getUser().getLastName())
                    .initials(initials(child.getUser()))
                    .position(child.getPosition())
                    .isAccelerator(child.getIsAccelerator())
                    .stageStatus(child.getStageStatus())
                    .children(List.of())
                    .build());
        }
        return result;
    }

    private String initials(User u) {
        return (u.getFirstName().charAt(0) + "" + u.getLastName().charAt(0)).toUpperCase();
    }
}
