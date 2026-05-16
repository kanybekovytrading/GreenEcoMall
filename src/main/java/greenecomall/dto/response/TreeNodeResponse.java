package greenecomall.dto.response;

import greenecomall.enums.StageStatus;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record TreeNodeResponse(
        UUID userId,
        String name,
        String initials,
        Integer position,
        Boolean isAccelerator,
        StageStatus stageStatus,
        Boolean acceleratorAssisted,
        List<TreeNodeResponse> children
) {}
