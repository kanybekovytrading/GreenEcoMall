package greenecomall.controller;

import greenecomall.entity.User;
import greenecomall.enums.MediaFileType;
import greenecomall.service.MinioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@Tag(name = "Upload", description = "Загрузка файлов. Возвращает публичный URL для сохранения в нужное поле.")
@SecurityRequirement(name = "Bearer")
public class UploadController {

    private final MinioService minioService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Загрузить изображение",
            description = """
                    Загружает изображение и возвращает публичный URL.

                    | type | Куда вставлять URL |
                    |------|--------------------|
                    | `AVATAR` | `PUT /api/profile` → `avatarUrl` |
                    | `NEWS_COVER` | `POST/PUT /api/admin/news` → `coverImageUrl` |
                    | `NEWS_MEDIA` | `POST /api/admin/news/{id}/media` → `objectKey` |
                    | `VIDEO_THUMBNAIL` | `POST/PUT /api/admin/videos` → `thumbnailUrl` |
                    | `TEST_ICON` | `POST/PUT /api/admin/tests` → `iconUrl` |
                    | `QUESTION_IMAGE` | `POST/PUT /api/admin/sub-tests/{id}/questions` → `imageUrl` |
                    """
    )
    public ResponseEntity<UploadResponse> upload(
            @AuthenticationPrincipal User user,
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Тип: AVATAR, NEWS_COVER, NEWS_MEDIA, VIDEO_THUMBNAIL, TEST_ICON, QUESTION_IMAGE")
            @RequestParam MediaFileType type) {

        String entityId = (type == MediaFileType.AVATAR)
                ? String.valueOf(user.getId())
                : "general";

        String objectKey = minioService.upload(file, type, entityId);
        String presignedUrl = minioService.presign(objectKey);
        return ResponseEntity.ok(UploadResponse.builder()
                .url(presignedUrl)
                .objectKey(objectKey)
                .build());
    }

    @Data
    @Builder
    static class UploadResponse {
        private String url;        // presigned URL (valid 1 hour) — use for display
        private String objectKey;  // object key — save this to DB fields
    }
}
