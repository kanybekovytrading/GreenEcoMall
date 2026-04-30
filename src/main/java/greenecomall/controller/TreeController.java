package greenecomall.controller;

import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.TreeResponse;
import greenecomall.entity.User;
import greenecomall.service.TreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tree")
@RequiredArgsConstructor
public class TreeController {

    private final TreeService treeService;

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<TreeResponse>> getMyTree(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "1") int level,
            @RequestParam(defaultValue = "1") int stage) {
        return ResponseEntity.ok(ApiResponse.ok(treeService.getTree(user, level, stage)));
    }

    @GetMapping("/stage-status")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getStageStatus(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(java.util.Map.of(
                "currentLevel", user.getCurrentLevel(),
                "currentStage", user.getCurrentStage()
        )));
    }
}
