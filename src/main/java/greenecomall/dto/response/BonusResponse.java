package greenecomall.dto.response;

import greenecomall.enums.BonusStatus;
import greenecomall.enums.BonusType;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record BonusResponse(
        UUID id,
        BonusType type,
        BigDecimal amount,
        BonusStatus status,
        Integer level,
        Integer stage,
        String description,
        String fromUserName,
        LocalDateTime confirmedAt,
        LocalDateTime createdAt
) {}
