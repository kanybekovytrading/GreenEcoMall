package greenecomall.dto.response;

import greenecomall.enums.NewsAudience;
import greenecomall.enums.NewsCategory;
import greenecomall.enums.NewsStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record NewsDetailResponse(
        UUID id,
        String title,
        String excerpt,
        String body,
        NewsCategory category,
        NewsStatus status,
        NewsAudience audience,
        boolean pinned,
        String coverImageUrl,
        String coverColor,
        String coverIcon,
        LocalDateTime publishAt,
        LocalDateTime createdAt,
        long viewCount
) {}
