package greenecomall.service;

import greenecomall.dto.request.UpdateProfileRequest;
import greenecomall.dto.response.UserProfileResponse;
import greenecomall.entity.User;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserProfileResponse getProfile(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .passportNumber(user.getPassportNumber())
                .referralCode(user.getReferralCode())
                .role(user.getRole())
                .accountStatus(user.getAccountStatus())
                .currentLevel(user.getCurrentLevel())
                .currentStage(user.getCurrentStage())
                .balance(user.getBalance())
                .createdAt(user.getCreatedAt())
                .activatedAt(user.getActivatedAt())
                .build();
    }

    @Transactional
    public UserProfileResponse updateProfile(User user, UpdateProfileRequest req) {
        if (req.firstName() != null) user.setFirstName(req.firstName());
        if (req.lastName() != null) user.setLastName(req.lastName());
        if (req.password() != null) user.setPasswordHash(passwordEncoder.encode(req.password()));
        userRepository.save(user);
        return getProfile(user);
    }
}
