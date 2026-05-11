package greenecomall.dto.response;

import lombok.Builder;

@Builder
public record NewsStatsResponse(
        long publishedCount,
        long scheduledCount,
        long draftCount,
        long archivedCount,
        long viewsThisMonth
) {}
