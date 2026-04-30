package greenecomall.dto.response;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record AdminStatsResponse(
        long totalUsers,
        long activeUsers,
        BigDecimal totalVolume,
        long pendingWithdrawals,
        long pendingPayments
) {}
