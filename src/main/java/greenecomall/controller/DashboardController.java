package greenecomall.controller;

import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.DashboardResponse;
import greenecomall.entity.User;
import greenecomall.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Главная страница — агрегированные данные профиля, дерева и активности")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(
            summary = "Главная страница ⭐",
            description = """
                    Агрегированный эндпоинт для главного экрана приложения. Возвращает одним запросом:

                    - **Финансы**: баланс, ожидающие бонусы, всего заработано
                    - **Команда**: размер команды, текущий уровень/этап
                    - **Дерево**: прогресс (заполнено/6), размер левой и правой ветки
                    - **Реферальная ссылка**: код и готовая ссылка для приглашения
                    - **Последние 3 бонуса**: краткая лента начислений
                    - **Последние 3 события команды**: активность участников
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Данные главной страницы"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Не авторизован", content = @Content)
    })
    @GetMapping
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getDashboard(user)));
    }
}
