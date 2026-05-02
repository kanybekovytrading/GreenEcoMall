package greenecomall.dto.response;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record BranchStatsResponse(
        BranchInfo left,
        BranchInfo right,
        int totalFilled,
        int total
) {
    @Builder
    public record BranchInfo(
            int size,
            List<BranchMember> members
    ) {}

    @Builder
    public record BranchMember(
            UUID userId,
            String name,
            String initials,
            int currentStage,
            boolean isAccelerator
    ) {}
}
