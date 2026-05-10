package greenecomall.dto.request;

import greenecomall.enums.RegistrationPlan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,

        @NotBlank
        @Pattern(regexp = "\\+996\\d{9}", message = "Формат телефона: +996XXXXXXXXX")
        String phone,

        @NotBlank String passportNumber,

        @NotBlank
        @Size(min = 6, message = "Пароль минимум 6 символов")
        String password,

        @NotBlank
        @Size(min = 8, max = 14, message = "Реферальный код от 8 до 14 символов")
        String referralCode,

        // null → STANDARD (10 000 сом, Уровень 1)
        // FAST_START → 20 000 сом, старт с Уровня 2
        RegistrationPlan plan
) {}
