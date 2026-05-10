package greenecomall.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank
        @Pattern(regexp = "\\+996\\d{9}", message = "Формат телефона: +996XXXXXXXXX")
        String phone,

        @NotBlank
        String code,

        @NotBlank
        @Size(min = 6, message = "Пароль минимум 6 символов")
        String newPassword
) {}
