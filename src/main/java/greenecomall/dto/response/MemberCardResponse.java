package greenecomall.dto.response;

import greenecomall.enums.AccountStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record MemberCardResponse(
        UUID userId,
        String name,
        String initials,
        Integer currentLevel,
        Integer currentStage,
        AccountStatus accountStatus,
        String referralCode,
        String referralLink,
        LocalDateTime joinedAt,
        int teamSize,        // сколько всего людей в его дереве (все тиры)
        int leftBranchSize,
        int rightBranchSize
) {}
