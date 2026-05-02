package greenecomall.controller;

import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.BranchStatsResponse;
import greenecomall.dto.response.StagesOverviewResponse;
import greenecomall.dto.response.TeamActivityResponse;
import greenecomall.dto.response.TreeResponse;
import greenecomall.entity.User;
import greenecomall.service.TreeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tree")
@RequiredArgsConstructor
@Tag(name = "3. Tree", description = "Дерево участников, прогресс этапов и активность команды")
public class TreeController {

    private final TreeService treeService;

    @Operation(
            summary = "Обзор всех 4 этапов текущего уровня ⭐",
            description = """
                    **Главный эндпоинт для отслеживания прогресса.**
                    Показывает все 4 этапа сразу с подробностями:

                    - **Этап 1** — кто занял позиции дерева (первые 6 = твоя матрица), когда вступил, на каком этапе сейчас каждый
                    - **Этап 2** — 2 фиксированных партнёра (на Уровне 1 — гонка: кто первым завершит Этап 1, на уровнях 2-4 — те же партнёры)
                    - **Этап 3** — сколько из 6 человек команды дошли до Этапа 3 (ждём всех 6)
                    - **Этап 4** — дошли ли оба фиксированных партнёра до Этапа 4 → переход на следующий уровень

                    Статусы: `WAITING` — ещё не начат | `IN_PROGRESS` — идёт | `COMPLETED` — завершён
                    """
    )
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<StagesOverviewResponse>> getOverview(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(treeService.getStagesOverview(user)));
    }

    @Operation(
            summary = "Лента активности команды",
            description = """
                    Показывает все события в твоей команде в хронологическом порядке (новые первые):

                    - `JOINED` — участник вступил в команду
                    - `STAGE_1_DONE` — участник завершил Этап 1 (собрал свои 6)
                    - `STAGE_2_DONE` — участник завершил Этап 2
                    - `STAGE_3_DONE` — участник завершил Этап 3

                    Охватывает все 6 позиций (2 яруса) твоего дерева.
                    """
    )
    @GetMapping("/activity")
    public ResponseEntity<ApiResponse<List<TeamActivityResponse>>> getActivity(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(treeService.getTeamActivity(user)));
    }

    @Operation(
            summary = "Дерево на конкретном уровне и этапе",
            description = """
                    Визуализация дерева для конкретного уровня/этапа.
                    Каждый узел содержит: имя, инициалы, позицию, статус этапа, флаг ускорителя.

                    **Глубина отображения зависит от этапа:**

                    - `stage=1 & level=1` — бесконечное BFS-дерево (все участники на всех ярусах)
                    - `stage=2 или stage=4` **пока это текущий этап** — только 2 прямых партнёра
                    - `stage=2 или stage=4` **как история (уже перешли дальше)** — 6 человек (2 партнёра + по 2 под ними)
                    - `stage=3` и все остальные — 6 человек (2 яруса)

                    **По умолчанию** `level=1&stage=1` — твоё текущее дерево первого этапа.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Дерево получено"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Не авторизован", content = @Content)
    })
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<TreeResponse>> getMyTree(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Уровень 1-4", example = "1") @RequestParam(defaultValue = "1") int level,
            @Parameter(description = "Этап 1-4",   example = "1") @RequestParam(defaultValue = "1") int stage) {
        return ResponseEntity.ok(ApiResponse.ok(treeService.getTree(user, level, stage)));
    }

    @Operation(
            summary = "Статистика веток дерева",
            description = """
                    Показывает левую и правую ветку текущего дерева:
                    - Сколько человек в каждой ветке
                    - Список участников по веткам (имя, этап, ускоритель)
                    - Общее заполнение (из 6)

                    Используется на экране дерева для отображения панели с двумя ветками внизу.
                    """
    )
    @GetMapping("/branches")
    public ResponseEntity<ApiResponse<BranchStatsResponse>> getBranches(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(treeService.getBranchStats(user)));
    }

    @Operation(
            summary = "Текущий уровень и этап",
            description = "Быстрый способ узнать на каком ты уровне и этапе прямо сейчас."
    )
    @GetMapping("/stage-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStageStatus(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "currentLevel", user.getCurrentLevel(),
                "currentStage", user.getCurrentStage()
        )));
    }
}
