package greenecomall.controller;

import greenecomall.dto.response.ApiResponse;
import greenecomall.service.TreeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/repair")
@RequiredArgsConstructor
@Tag(name = "Repair", description = "Восстановление данных (без авторизации)")
public class RepairController {

    private final TreeService treeService;

    @Operation(summary = "Восстановить слоты Stage 2",
            description = """
                    Находит всех кто завершил Этап 1 (currentStage >= 2) но ещё не встал ни под кого
                    как fixedPartnerLeft/Right. Проставляет их под ближайшего предка на Stage 2.
                    Используй если человек завершил Этап 1 но не появился на Этапе 2 у своего инвайтера.
                    """)
    @SecurityRequirements
    @PostMapping("/stage2-slots")
    public ResponseEntity<ApiResponse<List<String>>> repairStage2Slots() {
        return ResponseEntity.ok(ApiResponse.ok(treeService.repairStage2Placements()));
    }

    @Operation(summary = "Восстановить позиции в дереве (Stage 1)",
            description = "Находит активных участников с inviter_id но без tree_positions и размещает их через BFS.")
    @SecurityRequirements
    @PostMapping("/tree-positions")
    public ResponseEntity<ApiResponse<List<String>>> repairTreePositions() {
        return ResponseEntity.ok(ApiResponse.ok(treeService.repairMissingPositions()));
    }
}
