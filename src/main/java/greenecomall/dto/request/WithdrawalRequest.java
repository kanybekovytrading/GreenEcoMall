package greenecomall.dto.request;

import greenecomall.enums.WithdrawalMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WithdrawalRequest(
        @NotNull
        @DecimalMin(value = "1000", message = "Минимальная сумма вывода 1 000 сом")
        BigDecimal amount,

        @NotNull(message = "Укажите способ вывода")
        WithdrawalMethod method,

        @NotBlank(message = "Укажите реквизиты")
        String requisite,  // телефон (+996XXXXXXXXX) для FINIK/PHONE_TRANSFER, номер карты для BANK_CARD

        String bankName    // необязательно: название банка или платёжной системы (например "MBank", "Элсом")
) {}
