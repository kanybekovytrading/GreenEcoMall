package greenecomall.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record TeamActivityResponse(
        UUID userId,
        String name,
        String initials,
        String event,        // "JOINED", "STAGE_1_DONE", "STAGE_2_DONE", "STAGE_3_DONE", "STAGE_4_DONE"
        String description,
        int level,
        int stage,
        LocalDateTime occurredAt
) {}
