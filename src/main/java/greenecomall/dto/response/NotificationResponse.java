package greenecomall.dto.response;

import greenecomall.enums.NotificationType;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String body,
        Boolean isRead,
        LocalDateTime createdAt
) {}
