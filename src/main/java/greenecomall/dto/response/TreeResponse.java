package greenecomall.dto.response;

import greenecomall.enums.StageStatus;
import lombok.Builder;

@Builder
public record TreeResponse(
        TreeNodeResponse root,
        StageStatus stageStatus,
        TreeProgress progress,
        AcceleratorInfo accelerator
) {
    @Builder
    public record TreeProgress(int filled, int total) {}

    @Builder
    public record AcceleratorInfo(boolean active, Integer position) {}
}
