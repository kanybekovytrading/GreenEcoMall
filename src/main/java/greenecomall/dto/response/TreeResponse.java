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
        Integer fastStartNumber
) {
    @Builder
    public record TreeProgress(int filled, int total) {}

    @Builder
    public record AcceleratorInfo(boolean active, Integer position) {}
}
