package greenecomall.service;

import greenecomall.dto.request.UpdateProfileRequest;
import greenecomall.dto.response.UserProfileResponse;
import greenecomall.entity.User;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.base-url:https://greenecomall.kg}")
    private String baseUrl;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(User user) {
        User u = userRepository.findById(user.getId()).orElse(user);
        User inviter = u.getInviter();
        return UserProfileResponse.builder()
                .id(u.getId())
                .firstName(u.getFirstName())
                .lastName(u.getLastName())
                .phone(u.getPhone())
                .passportNumber(u.getPassportNumber())
                .referralCode(u.getReferralCode())
                .referralLink(baseUrl + "/join?ref=" + u.getReferralCode())
                .role(u.getRole())
                .accountStatus(u.getAccountStatus())
                .currentLevel(u.getCurrentLevel())
                .currentStage(u.getCurrentStage())
                .balance(u.getBalance())
                .finikPhone(u.getFinikPhone())
                .inviterId(inviter != null ? inviter.getId() : null)
                .inviterName(inviter != null ? inviter.getFirstName() + " " + inviter.getLastName() : null)
                .inviterReferralCode(inviter != null ? inviter.getReferralCode() : null)
                .createdAt(u.getCreatedAt())
                .activatedAt(u.getActivatedAt())
                .registrationPlan(u.getRegistrationPlan())
                .fastStartNumber(u.getFastStartNumber())
                .build();
    }

    @Transactional
    public UserProfileResponse updateProfile(User user, UpdateProfileRequest req) {
        if (req.firstName() != null) user.setFirstName(req.firstName());
        if (req.lastName() != null) user.setLastName(req.lastName());
        if (req.password() != null) user.setPasswordHash(passwordEncoder.encode(req.password()));
        if (req.finikPhone() != null) user.setFinikPhone(req.finikPhone());
        userRepository.save(user);
        return getProfile(user);
    }
}
