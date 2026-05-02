package greenecomall.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record DashboardResponse(
        // Финансовый блок
        BigDecimal balance,
        BigDecimal pendingBonuses,
        BigDecimal totalEarned,

        // Команда
        int teamSize,
        int currentLevel,
        int currentStage,

        // Дерево — прогресс текущего уровня
        TreeSummary treeSummary,

        // Реферальная ссылка
        String referralCode,
        String referralLink,

        // Лента активности (3 последних события)
        List<TeamActivityResponse> recentActivity,

        // Последние 3 бонуса
        List<BonusItem> recentBonuses
) {
    @Builder
    public record TreeSummary(
            int filled,
            int total,
            int leftBranchSize,
            int rightBranchSize
    ) {}

    @Builder
    public record BonusItem(
            UUID id,
            String type,
            BigDecimal amount,
            String status,
            String fromUserName,
            LocalDateTime createdAt
    ) {}
}
