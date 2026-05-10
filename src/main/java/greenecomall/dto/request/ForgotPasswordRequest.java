package greenecomall.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ForgotPasswordRequest(
        @NotBlank
        @Pattern(regexp = "\\+996\\d{9}", message = "Формат телефона: +996XXXXXXXXX")
        String phone,

        @NotBlank
        String codeWord
) {}
