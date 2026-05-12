package greenecomall.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record NewsMediaResponse(
        UUID id,
        String url,        // presigned URL (valid 1 hour)
        int sortOrder,
        LocalDateTime createdAt
) {}
