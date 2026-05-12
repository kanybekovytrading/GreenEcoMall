package greenecomall.dto.response;

import greenecomall.enums.NewsAudience;
import greenecomall.enums.NewsCategory;
import greenecomall.enums.NewsStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record NewsItemResponse(
        UUID id,
        String title,
        String excerpt,
        NewsCategory category,
        NewsStatus status,
        NewsAudience audience,
        boolean pinned,
        String coverImageUrl,
        String coverColor,
        String coverIcon,
        LocalDateTime publishAt,
        LocalDateTime createdAt,
        long viewCount,
        long commentCount
) {}
