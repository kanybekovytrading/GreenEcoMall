package greenecomall.service;

import greenecomall.dto.response.DashboardResponse;
import greenecomall.dto.response.TeamActivityResponse;
import greenecomall.entity.Bonus;
import greenecomall.entity.TreePosition;
import greenecomall.entity.User;
import greenecomall.enums.BonusStatus;
import greenecomall.repository.BonusRepository;
import greenecomall.repository.TreePositionRepository;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final BonusRepository bonusRepository;
    private final TreePositionRepository treePositionRepo;
    private final TreeService treeService;
    private final UserRepository userRepository;

    @Value("${app.base-url:https://green-eco-mall-client.up.railway.app}")
    private String baseUrl;

    public DashboardResponse getDashboard(User user) {
        int level = user.getCurrentLevel();

        BigDecimal pending  = bonusRepository.sumByUserAndStatus(user, BonusStatus.PENDING);
        BigDecimal totalEarned = bonusRepository.sumAllByUser(user);

        // Tree progress
        List<TreePosition> tier1 = treePositionRepo.findByParentAndLevelAndStage(user, level, 1);
        int leftSize  = branchMemberCount(user, level, 1);
        int rightSize = branchMemberCount(user, level, 2);
        int filled    = tier1.size() + tier1.stream()
                .mapToInt(c -> treePositionRepo.countByParentAndLevelAndStage(c.getUser(), level, 1))
                .sum();

        // Team size = все под ним по реферальной цепочке (все тиры, все уровни)
        int teamSize = countDownline(user);

        // Recent bonuses (last 3)
        List<Bonus> bonuses = bonusRepository.findByUser(user,
                PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        List<DashboardResponse.BonusItem> recentBonuses = bonuses.stream()
                .map(b -> DashboardResponse.BonusItem.builder()
                        .id(b.getId())
                        .type(b.getType().name())
                        .amount(b.getAmount())
                        .status(b.getStatus().name())
                        .fromUserName(b.getFromUser() != null
                                ? b.getFromUser().getFirstName() + " " + b.getFromUser().getLastName()
                                : null)
                        .createdAt(b.getCreatedAt())
                        .build())
                .toList();

        // Recent activity (last 3)
        List<TeamActivityResponse> recentActivity = treeService.getTeamActivity(user).stream()
                .limit(3).toList();

        return DashboardResponse.builder()
                .balance(user.getBalance())
                .pendingBonuses(pending)
                .totalEarned(totalEarned)
                .teamSize(teamSize)
                .currentLevel(user.getCurrentLevel())
                .currentStage(user.getCurrentStage())
                .treeSummary(DashboardResponse.TreeSummary.builder()
                        .filled(filled).total(6)
                        .leftBranchSize(leftSize)
                        .rightBranchSize(rightSize)
                        .build())
                .referralCode(user.getReferralCode())
                .referralLink(baseUrl + "/join?ref=" + user.getReferralCode())
                .recentActivity(recentActivity)
                .recentBonuses(recentBonuses)
                .build();
    }

    private int branchMemberCount(User root, int level, int side) {
        List<TreePosition> direct = treePositionRepo.findByParentAndLevelAndStage(root, level, 1);
        return direct.stream()
                .filter(c -> c.getPosition() == side && !c.getIsAccelerator())
                .mapToInt(c -> 1 + treePositionRepo.countByParentAndLevelAndStage(c.getUser(), level, 1))
                .sum();
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
}
