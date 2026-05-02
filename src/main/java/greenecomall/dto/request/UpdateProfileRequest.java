package greenecomall.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        String firstName,
        String lastName,

        @Size(min = 6, message = "Пароль минимум 6 символов")
        String password,

        @Pattern(regexp = "\\+996\\d{9}", message = "Формат телефона: +996XXXXXXXXX")
        String finikPhone
) {}
