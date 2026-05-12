package greenecomall.controller;

import greenecomall.dto.request.CreateCommentRequest;
import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.NewsCommentResponse;
import greenecomall.dto.response.NewsDetailResponse;
import greenecomall.dto.response.NewsItemResponse;
import greenecomall.entity.User;
import greenecomall.enums.NewsCategory;
import greenecomall.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
@Tag(name = "5. News", description = "Новости и объявления для участников")
public class NewsController {

    private final NewsService newsService;

    @Operation(
            summary = "Лента новостей",
            description = """
                    Возвращает опубликованные новости для уровня текущего участника.
                    Закреплённые — всегда первые.

                    **category** (опционально): LEVELS | PAYMENTS | PROMO | STAGES | PARTNERS | COMMUNITY
                    """
    )
    @GetMapping
    public ResponseEntity<ApiResponse<Page<NewsItemResponse>>> getNews(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Фильтр по категории") @RequestParam(required = false) NewsCategory category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.getPublished(user, category, page, size)));
    }

    @Operation(summary = "Полная новость", description = "Возвращает полный текст новости, медиа-вложения и счётчик комментариев. Увеличивает счётчик просмотров.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<NewsDetailResponse>> getDetail(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.getDetail(id, user)));
    }

    @Operation(summary = "Кол-во непрочитанных новостей (для бейджа в меню)",
            description = "Считает опубликованные новости за последние 7 дней, доступные этому участнику.")
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("count", newsService.countUnread(user))));
    }

    // ── Комментарии ──────────────────────────────────────────────────────────

    @Operation(
            summary = "Список комментариев",
            description = "Возвращает комментарии к новости постранично (новые снизу). `ownComment=true` — твой комментарий."
    )
    @GetMapping("/{id}/comments")
    public ResponseEntity<ApiResponse<Page<NewsCommentResponse>>> getComments(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.getComments(id, user, page, size)));
    }

    @Operation(summary = "Добавить комментарий", description = "Любой авторизованный участник может оставить комментарий. Максимум 1000 символов.")
    @PostMapping("/{id}/comments")
    public ResponseEntity<ApiResponse<NewsCommentResponse>> addComment(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateCommentRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.addComment(id, user, req)));
    }

    @Operation(summary = "Удалить комментарий", description = "Участник может удалить только свой комментарий. Админ — любой.")
    @DeleteMapping("/{id}/comments/{commentId}")
    public ResponseEntity<ApiResponse<Void>> deleteComment(
            @PathVariable UUID id,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        newsService.deleteComment(id, commentId, user);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
