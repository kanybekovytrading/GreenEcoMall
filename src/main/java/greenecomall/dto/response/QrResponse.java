package greenecomall.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record QrResponse(
        String qrCode,
        LocalDateTime expiresAt,
        UUID paymentId,
        String transactionId  // ID транзакции Finik для отслеживания
) {}
