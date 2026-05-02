package greenecomall.dto.response;

import java.util.UUID;

public record RegisterResponse(
        UUID userId,
        UUID paymentId,
        String accessToken,
        String refreshToken
) {}
