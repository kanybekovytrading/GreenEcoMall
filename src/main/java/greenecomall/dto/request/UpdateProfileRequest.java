package greenecomall.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        String firstName,
        String lastName,

        @Size(min = 6, message = "Пароль минимум 6 символов")
        String password
) {}
