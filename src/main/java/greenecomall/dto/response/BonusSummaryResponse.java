package greenecomall.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Map;

@Builder
public record BonusSummaryResponse(
        BigDecimal available,
        BigDecimal pending,
        BigDecimal confirmed,
        BigDecimal total,
        Map<String, BigDecimal> byType
) {}
