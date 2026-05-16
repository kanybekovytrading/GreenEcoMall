package greenecomall.dto.response;

import greenecomall.enums.StageStatus;
import lombok.Builder;

@Builder
public record TreeResponse(
        TreeNodeResponse root,
        StageStatus stageStatus,
        TreeProgress progress,
        AcceleratorInfo accelerator,
        // Only set for Fast Start (level=0) users — their sequential queue number
        Integer fastStartNumber,
        // Left/right branch sizes for this level's Stage-1 BFS tree
        BranchStats branches
) {
    @Builder
    public record TreeProgress(int filled, int total) {}

    @Builder
    public record AcceleratorInfo(boolean active, Integer position) {}

    @Builder
    public record BranchStats(BranchSide left, BranchSide right) {}

    @Builder
    public record BranchSide(int size) {}
}
