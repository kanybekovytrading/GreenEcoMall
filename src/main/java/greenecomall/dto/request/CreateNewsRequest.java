package greenecomall.dto.request;

import greenecomall.enums.NewsAudience;
import greenecomall.enums.NewsCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record CreateNewsRequest(
        @NotBlank String title,
        @NotBlank String excerpt,
        String body,
        @NotNull NewsCategory category,
        NewsAudience audience,           // null → ALL
        LocalDateTime publishAt,         // null → опубликовать сейчас
        boolean pinned,
        String coverColor,
        String coverIcon,
        String coverImageUrl,
        boolean sendPushNotification,
        boolean sendSms
) {}
