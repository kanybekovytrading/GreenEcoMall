package greenecomall.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import greenecomall.enums.NewsAudience;
import greenecomall.enums.NewsCategory;

import java.time.LocalDateTime;

public record UpdateNewsRequest(
        String title,
        String excerpt,
        @JsonAlias("content") String body,
        NewsCategory category,
        NewsAudience audience,
        LocalDateTime publishAt,
        Boolean pinned,
        String coverColor,
        String coverIcon,
        String coverImageUrl
) {}
