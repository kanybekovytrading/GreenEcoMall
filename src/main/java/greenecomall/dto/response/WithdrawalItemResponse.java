package greenecomall.dto.response;

import greenecomall.enums.WithdrawalStatus;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record WithdrawalItemResponse(
        UUID id,
        UUID userId,
        String userName,
        String userPhone,
        BigDecimal amount,
        WithdrawalStatus status,
        String method,
        String requisite,
        String bankName,
        String adminNote,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt
) {}
