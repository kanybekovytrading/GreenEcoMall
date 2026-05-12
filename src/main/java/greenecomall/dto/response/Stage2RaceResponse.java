package greenecomall.dto.response;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record Stage2RaceResponse(
        List<RaceEntry> candidates,
        boolean stage2Completed   // оба слота уже заняты
) {
    @Builder
    public record RaceEntry(
            UUID userId,
            String name,
            String initials,
            int treePosition,     // 2-7 — позиция в твоём Stage 1 дереве
            int filled,           // сколько позиций заполнено в его дереве (0-6)
            int total,            // всегда 6
            int currentStage,
            boolean isFixedPartner, // уже стал твоим фикс. партнёром
            int fixedPartnerSlot    // 1=левый, 2=правый, 0=ещё нет
    ) {}
}
