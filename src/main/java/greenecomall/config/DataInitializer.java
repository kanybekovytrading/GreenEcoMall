package greenecomall.config;

import greenecomall.entity.User;
import greenecomall.enums.AccountStatus;
import greenecomall.enums.RegistrationPlan;
import greenecomall.enums.Role;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.phone:+996700000000}")
    private String adminPhone;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    @Value("${app.admin.referral-code:GEMADMIN}")
    private String adminReferralCode;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.findByPhone(adminPhone).isPresent()) {
            return; // already seeded
        }

        User admin = User.builder()
                .firstName("Admin")
                .lastName("GreenEcoMall")
                .phone(adminPhone)
                .passportNumber("ADM0000001")
                .passwordHash(passwordEncoder.encode(adminPassword))
                .referralCode(adminReferralCode)
                .role(Role.ADMIN)
                .accountStatus(AccountStatus.ACTIVE)
                .currentLevel(1)
                .currentStage(1)
                .registrationPlan(RegistrationPlan.STANDARD)
                .build();

        userRepository.save(admin);

        log.info("========================================");
        log.info("  Admin account created:");
        log.info("  Phone:         {}", adminPhone);
        log.info("  Password:      {}", adminPassword);
        log.info("  Referral code: {}", adminReferralCode);
        log.info("  (use this referral code to register first users)");
        log.info("========================================");
    }
}
