package greenecomall.dto.response;

import greenecomall.enums.StageStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record StagesOverviewResponse(
        int currentLevel,
        int currentStage,
        List<StageDetail> stages
) {

    @Builder
    public record StageDetail(
            int stage,
            String title,
            String description,
            StageStatus status,
            Object progress,       // разный тип для каждого этапа
            LocalDateTime completedAt
    ) {}

    // ── Этап 1: дерево 6 позиций ────────────────────────────────────────────
    @Builder
    public record Stage1Progress(
            int filled,
            int total,
            List<TreeMember> members   // позиции 2-7
    ) {}

    @Builder
    public record TreeMember(
            int position,
            UUID userId,
            String name,
            String initials,
            StageStatus stageStatus,
            int currentStage,
            LocalDateTime joinedAt
    ) {}

    // ── Этап 2: два фиксированных партнёра ──────────────────────────────────
    @Builder
    public record Stage2Progress(
            PartnerSlot left,
            PartnerSlot right
    ) {}

    @Builder
    public record PartnerSlot(
            boolean filled,
            UUID userId,
            String name,
            String initials,
            int currentStage,
            LocalDateTime filledAt
    ) {}

    // ── Этап 3: вся команда должна дойти до Этапа 3 ─────────────────────────
    @Builder
    public record Stage3Progress(
            int reached,     // сколько из 6 уже на Stage 3+
            int total,
            List<Stage3Member> members
    ) {}

    @Builder
    public record Stage3Member(
            UUID userId,
            String name,
            String initials,
            int currentStage,
            boolean reachedStage3
    ) {}

    // ── Этап 4: оба партнёра должны дойти до Этапа 4 ────────────────────────
    @Builder
    public record Stage4Progress(
            PartnerSlot left,
            PartnerSlot right
    ) {}
}
