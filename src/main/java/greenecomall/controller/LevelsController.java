package greenecomall.controller;

import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.LevelsOverviewResponse;
import greenecomall.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/levels")
@RequiredArgsConstructor
@Tag(name = "Levels", description = "Матрица 4 уровня × 4 этапа с бонусами")
public class LevelsController {

    @Operation(
            summary = "Обзор всех 4 уровней ⭐",
            description = """
                    Полная матрица прогресса: 4 уровня × 4 этапа.

                    Для каждого уровня и этапа показывает:
                    - Название и описание этапа
                    - Сумму бонуса (или тип награды для уровней 3-4)
                    - Флаги isCurrentLevel / isCurrentStage / isCompleted

                    **Суммы бонусов:**
                    | Уровень | Уровень 1 | Уровень 2 | Уровень 3 | Уровень 4 |
                    |---------|-----------|-----------|-----------|-----------|
                    | Этап 1  | 1 250 сом | 11 000 сом | 22 000 сом | 110 000 сом |
                    | Этап 2  | —         | —          | —          | —           |
                    | Этап 3  | 25 000 сом | 100 000 сом | Авт. BMW  | Квартира  |
                    | Этап 4  | Переход    | Переход    | Переход   | Акционер  |
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Матрица уровней"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Не авторизован", content = @Content)
    })
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<LevelsOverviewResponse>> getOverview(@AuthenticationPrincipal User user) {
        int curLevel = user.getCurrentLevel();
        int curStage = user.getCurrentStage();

        List<LevelsOverviewResponse.LevelInfo> levels = List.of(
                buildLevel(1, "Старт", new BigDecimal("10000"), curLevel, curStage),
                buildLevel(2, "Партнёр", new BigDecimal("50000"), curLevel, curStage),
                buildLevel(3, "Лидер", new BigDecimal("200000"), curLevel, curStage),
                buildLevel(4, "Акционер", new BigDecimal("1000000"), curLevel, curStage)
        );

        return ResponseEntity.ok(ApiResponse.ok(LevelsOverviewResponse.builder()
                .currentLevel(curLevel)
                .currentStage(curStage)
                .levels(levels)
                .build()));
    }

    private LevelsOverviewResponse.LevelInfo buildLevel(int level, String title, BigDecimal entryFee,
            int curLevel, int curStage) {
        boolean isCurrent   = level == curLevel;
        boolean isCompleted = level < curLevel;

        List<LevelsOverviewResponse.StageInfo> stages = List.of(
                buildStage(level, 1, "Формирование команды",
                        "Прямой бонус за 6 участников",
                        directBonus(level), "CASH", isCurrent, curStage, isCompleted),
                buildStage(level, 2, "Domkrat — гонка партнёров",
                        "Закрепление двух партнёров",
                        BigDecimal.ZERO, "NONE", isCurrent, curStage, isCompleted),
                buildStage(level, 3, "Leader Core",
                        stage3BonusDesc(level),
                        stage3BonusAmount(level), stage3BonusType(level), isCurrent, curStage, isCompleted),
                buildStage(level, 4, "Переход на следующий уровень",
                        level < 4 ? "Вся команда переходит на уровень " + (level + 1) : "Статус Акционера",
                        BigDecimal.ZERO, "LEVEL_UP", isCurrent, curStage, isCompleted)
        );

        return LevelsOverviewResponse.LevelInfo.builder()
                .level(level)
                .title(title)
                .entryFee(entryFee)
                .stages(stages)
                .isCurrentLevel(isCurrent)
                .isCompleted(isCompleted)
                .build();
    }

    private LevelsOverviewResponse.StageInfo buildStage(int level, int stage, String title,
            String bonusDesc, BigDecimal bonusAmount, String bonusType,
            boolean isCurLevel, int curStage, boolean levelCompleted) {
        boolean isCurrent   = isCurLevel && stage == curStage;
        boolean isCompleted = levelCompleted || (isCurLevel && stage < curStage);

        return LevelsOverviewResponse.StageInfo.builder()
                .stage(stage)
                .title(title)
                .bonusDescription(bonusDesc)
                .bonusAmount(bonusAmount)
                .bonusType(bonusType)
                .isCurrentStage(isCurrent)
                .isCompleted(isCompleted)
                .build();
    }

    private BigDecimal directBonus(int level) {
        return switch (level) {
            case 1 -> new BigDecimal("1250");
            case 2 -> new BigDecimal("11000");
            case 3 -> new BigDecimal("22000");
            case 4 -> new BigDecimal("110000");
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal stage3BonusAmount(int level) {
        return switch (level) {
            case 1 -> new BigDecimal("25000");
            case 2 -> new BigDecimal("100000");
            default -> BigDecimal.ZERO;  // physical reward for levels 3 & 4
        };
    }

    private String stage3BonusDesc(int level) {
        return switch (level) {
            case 1 -> "Этапный бонус 25 000 сом";
            case 2 -> "Этапный бонус 100 000 сом";
            case 3 -> "Автомобиль BMW";
            case 4 -> "Квартира";
            default -> "";
        };
    }

    private String stage3BonusType(int level) {
        return level <= 2 ? "CASH" : "REWARD";
    }
}
