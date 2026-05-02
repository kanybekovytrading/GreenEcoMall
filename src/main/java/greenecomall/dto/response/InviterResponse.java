package greenecomall.dto.response;

import lombok.Builder;

@Builder
public record InviterResponse(
        String name,
        String initials,
        int currentLevel,
        int currentStage,
        String referralCode
) {}
