package greenecomall.service;

import greenecomall.dto.request.*;
import greenecomall.dto.response.LoginResponse;
import greenecomall.dto.response.RegisterResponse;
import greenecomall.entity.OtpCode;
import greenecomall.entity.Payment;
import greenecomall.entity.User;
import greenecomall.enums.*;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.repository.OtpCodeRepository;
import greenecomall.repository.PaymentRepository;
import greenecomall.repository.UserRepository;
import greenecomall.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final PaymentRepository paymentRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.otp.expiration-minutes}")
    private int otpExpirationMinutes;

    @Value("${app.otp.max-requests-per-hour}")
    private int maxOtpRequestsPerHour;

    private static final String REFERRAL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final BigDecimal ENTRY_FEE = new BigDecimal("10000");

    @Transactional
    public void sendOtp(String phone, String clientIp) {
        long count = otpCodeRepository.countByPhoneAndCreatedAtAfter(phone, LocalDateTime.now().minusHours(1));
        if (count >= maxOtpRequestsPerHour) {
            throw BusinessException.of(ErrorCode.TOO_MANY_OTP_REQUESTS);
        }

        String code = String.format("%06d", new Random().nextInt(999999));
        otpCodeRepository.save(OtpCode.builder()
                .phone(phone)
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(otpExpirationMinutes))
                .build());

        log.info("[OTP] Phone: {} Code: {}", phone, code);
    }

    @Transactional
    public void verifyOtp(String phone, String code) {
        OtpCode otp = otpCodeRepository
                .findFirstByPhoneAndIsUsedFalseOrderByCreatedAtDesc(phone)
                .orElseThrow(() -> BusinessException.of(ErrorCode.INVALID_OTP));

        if (otp.getExpiresAt().isBefore(LocalDateTime.now()) || !otp.getCode().equals(code)) {
            throw BusinessException.of(ErrorCode.INVALID_OTP);
        }

        otp.setIsUsed(true);
    }

    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        boolean phoneVerified = otpCodeRepository.existsByPhoneAndIsUsedTrueAndCreatedAtAfter(
                req.phone(), LocalDateTime.now().minusHours(1));
        if (!phoneVerified) {
            throw BusinessException.of(ErrorCode.PHONE_NOT_VERIFIED);
        }

        if (userRepository.existsByPhone(req.phone())) {
            throw BusinessException.of(ErrorCode.PHONE_ALREADY_EXISTS);
        }
        if (userRepository.existsByPassportNumber(req.passportNumber())) {
            throw BusinessException.of(ErrorCode.PASSPORT_ALREADY_EXISTS);
        }

        User inviter = userRepository.findByReferralCode(req.referralCode())
                .orElseThrow(() -> BusinessException.of(ErrorCode.INVALID_REFERRAL_CODE));

        User user = User.builder()
                .firstName(req.firstName())
                .lastName(req.lastName())
                .phone(req.phone())
                .passportNumber(req.passportNumber())
                .passwordHash(passwordEncoder.encode(req.password()))
                .referralCode(generateUniqueReferralCode())
                .inviter(inviter)
                .role(Role.USER)
                .accountStatus(AccountStatus.PENDING)
                .currentLevel(1)
                .currentStage(1)
                .build();

        user = userRepository.save(user);

        Payment payment = Payment.builder()
                .user(user)
                .type(PaymentType.ENTRY_FEE)
                .amount(ENTRY_FEE)
                .status(PaymentStatus.PENDING)
                .build();
        payment = paymentRepository.save(payment);

        return new RegisterResponse(user.getId(), payment.getId());
    }

    public LoginResponse login(LoginRequest req) {
        User user = userRepository.findByPhone(req.phone())
                .orElseThrow(() -> BusinessException.of(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw BusinessException.of(ErrorCode.INVALID_CREDENTIALS);
        }

        if (user.getAccountStatus() == AccountStatus.BLOCKED) {
            throw BusinessException.of(ErrorCode.ACCOUNT_BLOCKED);
        }

        if (user.getAccountStatus() == AccountStatus.PENDING) {
            Payment payment = paymentRepository
                    .findFirstByUserAndTypeOrderByCreatedAtDesc(user, PaymentType.ENTRY_FEE)
                    .orElse(null);
            return LoginResponse.builder()
                    .needsPayment(true)
                    .paymentId(payment != null ? payment.getId() : null)
                    .userId(user.getId())
                    .build();
        }

        return LoginResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(user.getId(), user.getRole()))
                .refreshToken(jwtUtil.generateRefreshToken(user.getId()))
                .userId(user.getId())
                .role(user.getRole().name())
                .build();
    }

    public LoginResponse refresh(String refreshToken) {
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw BusinessException.of(ErrorCode.INVALID_TOKEN);
        }
        java.util.UUID userId = jwtUtil.extractUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));

        return LoginResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(user.getId(), user.getRole()))
                .refreshToken(jwtUtil.generateRefreshToken(user.getId()))
                .userId(user.getId())
                .role(user.getRole().name())
                .build();
    }

    private String generateUniqueReferralCode() {
        SecureRandom rng = new SecureRandom();
        String code;
        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(REFERRAL_CHARS.charAt(rng.nextInt(REFERRAL_CHARS.length())));
            }
            code = sb.toString();
        } while (userRepository.findByReferralCode(code).isPresent());
        return code;
    }
}
