package greenecomall.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WithdrawalRequest(
        @NotNull
        @DecimalMin(value = "1000", message = "Минимальная сумма вывода 1 000 сом")
        BigDecimal amount
) {}
