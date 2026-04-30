package greenecomall.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(
        @NotBlank
        @Pattern(regexp = "\\+996\\d{9}", message = "Формат телефона: +996XXXXXXXXX")
        String phone,

        @NotBlank
        @Size(min = 6, max = 6, message = "OTP должен быть 6 цифр")
        String code
) {}
