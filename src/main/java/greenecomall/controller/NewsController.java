package greenecomall.controller;

import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.NewsDetailResponse;
import greenecomall.dto.response.NewsItemResponse;
import greenecomall.entity.User;
import greenecomall.enums.NewsCategory;
import greenecomall.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "Полная новость", description = "Возвращает полный текст новости и увеличивает счётчик просмотров.")
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
}
