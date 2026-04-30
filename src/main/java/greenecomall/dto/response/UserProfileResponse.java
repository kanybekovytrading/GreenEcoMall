package greenecomall.dto.response;

import greenecomall.enums.AccountStatus;
import greenecomall.enums.Role;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record UserProfileResponse(
        UUID id,
        String firstName,
        String lastName,
        String phone,
        String passportNumber,
        String referralCode,
        Role role,
        AccountStatus accountStatus,
        Integer currentLevel,
        Integer currentStage,
        BigDecimal balance,
        LocalDateTime createdAt,
        LocalDateTime activatedAt
) {}
