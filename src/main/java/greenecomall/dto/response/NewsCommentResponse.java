package greenecomall.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record NewsCommentResponse(
        UUID id,
        UUID authorId,
        String authorName,
        String authorInitials,
        String text,
        boolean ownComment,
        LocalDateTime createdAt
) {}
