package greenecomall.dto.response;

import greenecomall.enums.AccountStatus;
import greenecomall.enums.RegistrationPlan;
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
        String referralLink,
        Role role,
        AccountStatus accountStatus,
        Integer currentLevel,
        Integer currentStage,
        BigDecimal balance,
        String finikPhone,
        // Inviter info
        UUID inviterId,
        String inviterName,
        String inviterReferralCode,
        LocalDateTime createdAt,
        LocalDateTime activatedAt,
        RegistrationPlan registrationPlan,
        // null for STANDARD users; sequential number in Fast Start queue for FAST_START users
        Integer fastStartNumber
) {}
