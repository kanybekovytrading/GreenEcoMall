package greenecomall.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record LevelsOverviewResponse(
        int currentLevel,
        int currentStage,
        List<LevelInfo> levels
) {
    @Builder
    public record LevelInfo(
            int level,
            String title,
            BigDecimal entryFee,
            List<StageInfo> stages,
            boolean isCurrentLevel,
            boolean isCompleted
    ) {}

    @Builder
    public record StageInfo(
            int stage,
            String title,
            String bonusDescription,
            BigDecimal bonusAmount,
            String bonusType,  // "CASH" | "REWARD"
            boolean isCurrentStage,
            boolean isCompleted
    ) {}
}
