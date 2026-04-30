package greenecomall.service;

import greenecomall.dto.response.QrResponse;
import greenecomall.entity.Payment;
import greenecomall.entity.User;
import greenecomall.enums.*;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.repository.PaymentRepository;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final TreeService treeService;
    private final BonusService bonusService;
    private final NotificationService notificationService;

    @Value("${app.payment.qr-expiration-minutes}")
    private int qrExpirationMinutes;

    @Transactional
    public QrResponse createQr(User user) {
        Payment payment = paymentRepository
                .findFirstByUserAndTypeAndStatusOrderByCreatedAtDesc(user, PaymentType.ENTRY_FEE, PaymentStatus.PENDING)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND));

        // Return existing non-expired QR
        if (payment.getFinikQrCode() != null && payment.getExpiresAt() != null
                && payment.getExpiresAt().isAfter(LocalDateTime.now())) {
            return new QrResponse(payment.getFinikQrCode(), payment.getExpiresAt(), payment.getId());
        }

        // Generate mock QR (real Finik integration would go here)
        String mockQr = "FINIK_QR_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(qrExpirationMinutes);

        payment.setFinikQrCode(mockQr);
        payment.setFinikTransactionId("TXN_" + payment.getId());
        payment.setExpiresAt(expiresAt);
        paymentRepository.save(payment);

        return new QrResponse(mockQr, expiresAt, payment.getId());
    }

    public Payment getPaymentStatus(UUID paymentId, User user) {
        return paymentRepository.findById(paymentId)
                .filter(p -> p.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND));
    }

    /**
     * Called by Finik webhook after successful payment.
     * HMAC validation happens in the controller before this is called.
     */
    @Transactional
    public void handleWebhook(String transactionId, String status) {
        Payment payment = paymentRepository.findByFinikTransactionId(transactionId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND));

        if (!"SUCCESS".equalsIgnoreCase(status)) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            return;
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        activateUser(payment.getUser());
    }

    private void activateUser(User user) {
        User locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);

        locked.setAccountStatus(AccountStatus.ACTIVE);
        locked.setActivatedAt(LocalDateTime.now());
        userRepository.save(locked);

        User inviter = locked.getInviter();
        if (inviter != null) {
            treeService.placeNewUser(inviter, locked);
            bonusService.createReferralBonuses(locked, inviter);

            notificationService.send(inviter, NotificationType.NEW_MEMBER,
                    "Новый участник",
                    locked.getFirstName() + " " + locked.getLastName() + " активировал аккаунт");
        }

        notificationService.send(locked, NotificationType.NEW_MEMBER,
                "Аккаунт активирован",
                "Добро пожаловать в Green Eco Mall! Ваш аккаунт успешно активирован.");
    }
}
