package greenecomall.service;

import greenecomall.enums.MediaFileType;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    /**
     * On startup: create bucket if missing and set public-read policy
     * so avatars, covers, and thumbnails are directly accessible by URL.
     */
    @PostConstruct
    public void init() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            } else {
                log.info("MinIO bucket '{}' is ready", bucket);
            }
            // NOTE: public-read access must be enabled via the Tigris/S3 dashboard.
            // setBucketPolicy is not supported by Tigris (returns NotImplemented).
        } catch (Exception e) {
            log.error("MinIO init failed: {}", e.getMessage());
        }
    }

    /**
     * Uploads an image file and returns the object key (stored in DB).
     * Use presign(objectKey) to get a time-limited URL for API responses.
     */
    public String upload(MultipartFile file, MediaFileType type, String entityId) {
        validateImage(file);

        String ext = getExtension(file.getOriginalFilename());
        String objectKey = buildKey(type, entityId, ext);

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("Uploaded to MinIO: {}", objectKey);
        } catch (Exception e) {
            log.error("MinIO upload failed for key {}: {}", objectKey, e.getMessage(), e);
            throw new RuntimeException("File upload failed: " + e.getMessage());
        }

        return objectKey;
    }

    /**
     * Generates a presigned GET URL valid for 1 hour.
     * Accepts either an object key ("avatars/uuid.jpg") or a legacy full URL.
     * Returns null if input is null or blank.
     */
    public String presign(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return null;
        String objectKey = extractObjectKey(keyOrUrl);
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(1, TimeUnit.HOURS)
                            .build());
        } catch (Exception e) {
            log.error("Failed to presign URL for {}: {}", objectKey, e.getMessage());
            return null;
        }
    }

    /**
     * Deletes an object by its public URL or objectKey.
     */
    public void delete(String urlOrKey) {
        String objectKey = extractObjectKey(urlOrKey);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            log.info("Deleted from MinIO: {}", objectKey);
        } catch (Exception e) {
            log.warn("MinIO delete failed (maybe already gone): {}", objectKey);
        }
    }

    // ---- helpers ----

    private String buildKey(MediaFileType type, String entityId, String ext) {
        String folder = switch (type) {
            case AVATAR          -> "avatars/" + entityId;
            case NEWS_COVER      -> "news";
            case NEWS_MEDIA      -> "news/media";
            case VIDEO_THUMBNAIL -> "thumbnails";
            case TEST_ICON       -> "tests";
            case QUESTION_IMAGE  -> "questions";
        };
        return folder + "/" + UUID.randomUUID() + ext;
    }

    public String extractKey(String urlOrKey) {
        return extractObjectKey(urlOrKey);
    }

    private String extractObjectKey(String urlOrKey) {
        // Strip query params (presigned URLs contain ?X-Amz-... that must not be part of the key)
        String clean = urlOrKey.contains("?") ? urlOrKey.substring(0, urlOrKey.indexOf('?')) : urlOrKey;
        String marker = "/" + bucket + "/";
        int idx = clean.indexOf(marker);
        return idx >= 0 ? clean.substring(idx + marker.length()) : clean;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return ".bin";
        return filename.substring(filename.lastIndexOf("."));
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File is empty.");
        long maxSize = 10L * 1024 * 1024; // 10 MB
        if (file.getSize() > maxSize) throw new IllegalArgumentException("File too large. Max: 10 MB.");
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed.");
        }
    }
}
