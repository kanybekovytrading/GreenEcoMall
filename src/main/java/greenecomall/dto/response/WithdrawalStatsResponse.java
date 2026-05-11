package greenecomall.dto.response;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record WithdrawalStatsResponse(
        long pendingCount,       // ОЖИДАЮТ
        BigDecimal pendingSum,   // К ВЫПЛАТЕ
        long approvedToday,      // ОДОБРЕНО СЕГОДНЯ
        BigDecimal totalPaid     // ВЫПЛАЧЕНО ВСЕГО
) {}
