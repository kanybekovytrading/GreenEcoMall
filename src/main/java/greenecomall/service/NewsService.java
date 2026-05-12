package greenecomall.service;

import greenecomall.dto.request.CreateCommentRequest;
import greenecomall.dto.request.CreateNewsRequest;
import greenecomall.dto.request.UpdateNewsRequest;
import greenecomall.dto.response.NewsCommentResponse;
import greenecomall.dto.response.NewsDetailResponse;
import greenecomall.dto.response.NewsItemResponse;
import greenecomall.dto.response.NewsMediaResponse;
import greenecomall.dto.response.NewsStatsResponse;
import greenecomall.entity.News;
import greenecomall.entity.NewsComment;
import greenecomall.entity.NewsMedia;
import greenecomall.entity.User;
import greenecomall.enums.NewsAudience;
import greenecomall.enums.NewsCategory;
import greenecomall.enums.NewsStatus;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.repository.NewsCommentRepository;
import greenecomall.repository.NewsMediaRepository;
import greenecomall.repository.NewsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NewsService {

    private final NewsRepository newsRepository;
    private final NewsMediaRepository newsMediaRepository;
    private final NewsCommentRepository newsCommentRepository;
    private final MinioService minioService;

    // ── Клиентские ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<NewsItemResponse> getPublished(User user, NewsCategory category, int page, int size) {
        List<NewsAudience> allowed = audiencesForUser(user);
        PageRequest pageable = PageRequest.of(page, size);
        Page<News> raw = category != null
                ? newsRepository.findPublishedForAudiencesByCategory(allowed, category, pageable)
                : newsRepository.findPublishedForAudiences(allowed, pageable);
        return raw.map(this::toItem);
    }

    @Transactional
    public NewsDetailResponse getDetail(UUID id, User user) {
        News news = newsRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));

        if (news.getStatus() != NewsStatus.PUBLISHED) {
            throw BusinessException.of(ErrorCode.USER_NOT_FOUND);
        }
        List<NewsAudience> allowed = audiencesForUser(user);
        if (!allowed.contains(news.getAudience())) {
            throw BusinessException.of(ErrorCode.USER_NOT_FOUND);
        }

        newsRepository.incrementViewCount(id);
        return toDetail(news);
    }

    @Transactional(readOnly = true)
    public long countUnread(User user) {
        List<NewsAudience> allowed = audiencesForUser(user);
        return newsRepository.countRecentForAudiences(allowed, LocalDateTime.now().minusDays(7));
    }

    // ── Комментарии ─────────────────────────────────────────────────────────

    @Transactional
    public NewsCommentResponse addComment(UUID newsId, User author, CreateCommentRequest req) {
        News news = findPublishedOrThrow(newsId);
        NewsComment comment = newsCommentRepository.save(NewsComment.builder()
                .news(news)
                .author(author)
                .text(req.text())
                .build());
        return toComment(comment, author.getId());
    }

    @Transactional(readOnly = true)
    public Page<NewsCommentResponse> getComments(UUID newsId, User viewer, int page, int size) {
        News news = findPublishedOrThrow(newsId);
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        return newsCommentRepository.findByNewsOrderByCreatedAtAsc(news, pageable)
                .map(c -> toComment(c, viewer.getId()));
    }

    @Transactional
    public void deleteComment(UUID newsId, UUID commentId, User user) {
        NewsComment comment = newsCommentRepository.findById(commentId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        if (!comment.getNews().getId().equals(newsId)) {
            throw BusinessException.of(ErrorCode.USER_NOT_FOUND);
        }
        boolean isAdmin = user.getRole() != null && user.getRole().name().equals("ADMIN");
        if (!isAdmin && !comment.getAuthor().getId().equals(user.getId())) {
            throw BusinessException.of(ErrorCode.FORBIDDEN);
        }
        newsCommentRepository.delete(comment);
    }

    // ── Медиа (только админ) ─────────────────────────────────────────────────

    @Transactional
    public NewsMediaResponse addMedia(UUID newsId, String objectKey) {
        News news = findOrThrow(newsId);
        int order = newsMediaRepository.findByNewsOrderBySortOrderAsc(news).size();
        NewsMedia media = newsMediaRepository.save(NewsMedia.builder()
                .news(news)
                .objectKey(objectKey)
                .sortOrder(order)
                .build());
        return toMediaResponse(media);
    }

    @Transactional
    public void deleteMedia(UUID newsId, UUID mediaId) {
        NewsMedia media = newsMediaRepository.findById(mediaId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        if (!media.getNews().getId().equals(newsId)) {
            throw BusinessException.of(ErrorCode.USER_NOT_FOUND);
        }
        minioService.delete(media.getObjectKey());
        newsMediaRepository.delete(media);
    }

    // ── Админские ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<NewsItemResponse> adminList(NewsStatus status, String search, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        if (search != null && !search.isBlank()) {
            return newsRepository.findByStatusAndSearch(status, search, pageable).map(this::toItem);
        }
        return newsRepository.findByStatusAdmin(status, pageable).map(this::toItem);
    }

    @Transactional(readOnly = true)
    public NewsStatsResponse getStats() {
        LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
        return NewsStatsResponse.builder()
                .publishedCount(newsRepository.countByStatus(NewsStatus.PUBLISHED))
                .scheduledCount(newsRepository.countByStatus(NewsStatus.SCHEDULED))
                .draftCount(newsRepository.countByStatus(NewsStatus.DRAFT))
                .archivedCount(newsRepository.countByStatus(NewsStatus.ARCHIVED))
                .viewsThisMonth(newsRepository.sumViewsSince(monthStart))
                .build();
    }

    @Transactional
    public NewsItemResponse create(CreateNewsRequest req) {
        NewsStatus status;
        LocalDateTime publishAt;

        if (req.publishAt() != null && req.publishAt().isAfter(LocalDateTime.now())) {
            status = NewsStatus.SCHEDULED;
            publishAt = req.publishAt();
        } else {
            status = NewsStatus.PUBLISHED;
            publishAt = LocalDateTime.now();
        }

        News news = News.builder()
                .title(req.title())
                .excerpt(req.excerpt() != null ? req.excerpt() : "")
                .body(req.body())
                .category(req.category())
                .audience(req.audience() != null ? req.audience() : NewsAudience.ALL)
                .status(status)
                .publishAt(publishAt)
                .pinned(req.pinned())
                .coverColor(req.coverColor())
                .coverIcon(req.coverIcon())
                .coverImageUrl(req.coverImageUrl())
                .build();

        return toItem(newsRepository.save(news));
    }

    @Transactional
    public NewsItemResponse saveDraft(CreateNewsRequest req) {
        News news = News.builder()
                .title(req.title())
                .excerpt(req.excerpt() != null ? req.excerpt() : "")
                .body(req.body())
                .category(req.category())
                .audience(req.audience() != null ? req.audience() : NewsAudience.ALL)
                .status(NewsStatus.DRAFT)
                .pinned(req.pinned())
                .coverColor(req.coverColor())
                .coverIcon(req.coverIcon())
                .coverImageUrl(req.coverImageUrl())
                .build();
        return toItem(newsRepository.save(news));
    }

    @Transactional
    public NewsItemResponse update(UUID id, UpdateNewsRequest req) {
        News news = findOrThrow(id);
        if (req.title() != null) news.setTitle(req.title());
        if (req.excerpt() != null) news.setExcerpt(req.excerpt());
        if (req.body() != null) news.setBody(req.body());
        if (req.category() != null) news.setCategory(req.category());
        if (req.audience() != null) news.setAudience(req.audience());
        if (req.publishAt() != null) news.setPublishAt(req.publishAt());
        if (req.pinned() != null) news.setPinned(req.pinned());
        if (req.coverColor() != null) news.setCoverColor(req.coverColor());
        if (req.coverIcon() != null) news.setCoverIcon(req.coverIcon());
        if (req.coverImageUrl() != null) news.setCoverImageUrl(req.coverImageUrl());
        return toItem(newsRepository.save(news));
    }

    @Transactional
    public NewsItemResponse publish(UUID id) {
        News news = findOrThrow(id);
        news.setStatus(NewsStatus.PUBLISHED);
        if (news.getPublishAt() == null) news.setPublishAt(LocalDateTime.now());
        return toItem(newsRepository.save(news));
    }

    @Transactional
    public NewsItemResponse archive(UUID id) {
        News news = findOrThrow(id);
        news.setStatus(NewsStatus.ARCHIVED);
        return toItem(newsRepository.save(news));
    }

    @Transactional
    public NewsItemResponse restore(UUID id) {
        News news = findOrThrow(id);
        news.setStatus(NewsStatus.DRAFT);
        return toItem(newsRepository.save(news));
    }

    @Transactional
    public NewsItemResponse togglePin(UUID id) {
        News news = findOrThrow(id);
        news.setPinned(!news.isPinned());
        return toItem(newsRepository.save(news));
    }

    @Transactional
    public void delete(UUID id) {
        newsRepository.deleteById(id);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void publishScheduled() {
        List<News> due = newsRepository.findDueScheduled(LocalDateTime.now());
        for (News n : due) {
            n.setStatus(NewsStatus.PUBLISHED);
        }
        if (!due.isEmpty()) newsRepository.saveAll(due);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private News findOrThrow(UUID id) {
        return newsRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
    }

    private News findPublishedOrThrow(UUID id) {
        News news = findOrThrow(id);
        if (news.getStatus() != NewsStatus.PUBLISHED) {
            throw BusinessException.of(ErrorCode.USER_NOT_FOUND);
        }
        return news;
    }

    private List<NewsAudience> audiencesForUser(User user) {
        int level = user.getCurrentLevel();
        return switch (level) {
            case 0 -> List.of(NewsAudience.ALL);
            case 1 -> List.of(NewsAudience.ALL, NewsAudience.LEVEL_1_PLUS);
            case 2 -> List.of(NewsAudience.ALL, NewsAudience.LEVEL_1_PLUS, NewsAudience.LEVEL_2_PLUS);
            case 3 -> List.of(NewsAudience.ALL, NewsAudience.LEVEL_1_PLUS, NewsAudience.LEVEL_2_PLUS, NewsAudience.LEVEL_3_PLUS);
            default -> List.of(NewsAudience.ALL, NewsAudience.LEVEL_1_PLUS, NewsAudience.LEVEL_2_PLUS, NewsAudience.LEVEL_3_PLUS, NewsAudience.LEVEL_4);
        };
    }

    private NewsItemResponse toItem(News n) {
        return NewsItemResponse.builder()
                .id(n.getId())
                .title(n.getTitle())
                .excerpt(n.getExcerpt())
                .category(n.getCategory())
                .status(n.getStatus())
                .audience(n.getAudience())
                .pinned(n.isPinned())
                .coverImageUrl(n.getCoverImageUrl())
                .coverColor(n.getCoverColor())
                .coverIcon(n.getCoverIcon())
                .publishAt(n.getPublishAt())
                .createdAt(n.getCreatedAt())
                .viewCount(n.getViewCount())
                .commentCount(newsCommentRepository.countByNews(n))
                .build();
    }

    private NewsDetailResponse toDetail(News n) {
        List<NewsMediaResponse> media = newsMediaRepository.findByNewsOrderBySortOrderAsc(n)
                .stream().map(this::toMediaResponse).toList();
        return NewsDetailResponse.builder()
                .id(n.getId())
                .title(n.getTitle())
                .excerpt(n.getExcerpt())
                .body(n.getBody())
                .category(n.getCategory())
                .status(n.getStatus())
                .audience(n.getAudience())
                .pinned(n.isPinned())
                .coverImageUrl(n.getCoverImageUrl())
                .coverColor(n.getCoverColor())
                .coverIcon(n.getCoverIcon())
                .publishAt(n.getPublishAt())
                .createdAt(n.getCreatedAt())
                .viewCount(n.getViewCount())
                .media(media)
                .commentCount(newsCommentRepository.countByNews(n))
                .build();
    }

    private NewsMediaResponse toMediaResponse(NewsMedia m) {
        return NewsMediaResponse.builder()
                .id(m.getId())
                .url(minioService.presign(m.getObjectKey()))
                .sortOrder(m.getSortOrder())
                .createdAt(m.getCreatedAt())
                .build();
    }

    private NewsCommentResponse toComment(NewsComment c, UUID viewerId) {
        User author = c.getAuthor();
        String initials = String.valueOf(author.getFirstName().charAt(0)).toUpperCase()
                + String.valueOf(author.getLastName().charAt(0)).toUpperCase();
        return NewsCommentResponse.builder()
                .id(c.getId())
                .authorId(author.getId())
                .authorName(author.getFirstName() + " " + author.getLastName())
                .authorInitials(initials)
                .text(c.getText())
                .ownComment(author.getId().equals(viewerId))
                .createdAt(c.getCreatedAt())
                .build();
    }
}
