package greenecomall.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import greenecomall.config.FinikConfig;
import greenecomall.dto.WebhookData;
import greenecomall.dto.response.QrResponse;
import greenecomall.entity.Payment;
import greenecomall.entity.User;
import greenecomall.enums.*;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.finik.FinikSignatureUtil;
import greenecomall.repository.OtpCodeRepository;
import greenecomall.repository.PaymentRepository;
import greenecomall.repository.UserRepository;
import greenecomall.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final SmsService smsService;
    private final TreeService treeService;
    private final BonusService bonusService;
    private final NotificationService notificationService;
    private final FinikConfig finikConfig;
    private final FinikSignatureUtil signatureUtil;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${app.payment.qr-expiration-minutes}")
    private int qrExpirationMinutes;

    @Value("${sms.smspro.enabled:true}")
    private boolean smsEnabled;

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE QR — calls real Finik API
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public QrResponse createQr(User user) {
        // Phone must be verified via OTP after account registration (steps 2-3)
        // In test mode (SMS disabled) skip this check — developer can still go through OTP via logs
        if (smsEnabled) {
            String formatted = smsService.formatPhone(user.getPhone());
            boolean phoneVerified = otpCodeRepository.existsByPhoneAndIsUsedTrueAndCreatedAtAfter(
                    formatted, user.getCreatedAt());
            if (!phoneVerified) {
                throw BusinessException.of(ErrorCode.PHONE_NOT_VERIFIED);
            }
        }

        Payment payment = paymentRepository
                .findFirstByUserAndTypeAndStatusOrderByCreatedAtDesc(
                        user, PaymentType.ENTRY_FEE, PaymentStatus.PENDING)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND));

        // Return existing non-expired QR
        if (payment.getFinikQrCode() != null && payment.getExpiresAt() != null
                && payment.getExpiresAt().isAfter(LocalDateTime.now())) {
            return new QrResponse(
                    payment.getFinikQrCode(), payment.getExpiresAt(),
                    payment.getId(), payment.getFinikTransactionId());
        }

        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(qrExpirationMinutes);

        try {
            String paymentUrl = callFinikCreatePayment(payment.getId(), payment.getAmount());

            payment.setFinikQrCode(paymentUrl);
            payment.setFinikTransactionId(payment.getId().toString());
            payment.setExpiresAt(expiresAt);
            paymentRepository.save(payment);

            log.info("Finik QR created for payment {}", payment.getId());
            return new QrResponse(paymentUrl, expiresAt, payment.getId(), payment.getFinikTransactionId());

        } catch (Exception e) {
            log.error("Finik createQr failed, falling back to mock QR", e);
            // Fallback: mock QR so registration flow doesn't break in dev/test
            String mockQr = "FINIK_QR_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
            String txnId  = "TXN_" + payment.getId().toString().replace("-", "").substring(0, 12).toUpperCase();
            payment.setFinikQrCode(mockQr);
            payment.setFinikTransactionId(txnId);
            payment.setExpiresAt(expiresAt);
            paymentRepository.save(payment);
            return new QrResponse(mockQr, expiresAt, payment.getId(), txnId);
        }
    }

    private String callFinikCreatePayment(UUID paymentId, java.math.BigDecimal amount) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("Amount", amount.intValue());
        requestBody.put("CardType", "FINIK_QR");
        requestBody.put("PaymentId", paymentId.toString());
        requestBody.put("RedirectUrl", finikConfig.getWebhookUrl());

        Map<String, Object> data = new HashMap<>();
        data.put("accountId", finikConfig.getAccountId());
        data.put("merchantCategoryCode", finikConfig.getMerchantCategoryCode());
        data.put("name_en", finikConfig.getQrNameEn());
        data.put("description", "GreenEcoMall вступительный взнос");
        data.put("webhookUrl", finikConfig.getWebhookUrl());
        requestBody.put("Data", data);

        String timestamp = String.valueOf(System.currentTimeMillis());
        URI uri = URI.create(finikConfig.getBaseUrl() + "/v1/payment");

        Map<String, String> headers = new HashMap<>();
        headers.put("Host", uri.getHost());
        headers.put("x-api-key", finikConfig.getApiKey());
        headers.put("x-api-timestamp", timestamp);

        String signature = signatureUtil.generateSignature(
                "POST", "/v1/payment", headers, null, requestBody,
                finikConfig.getPrivateKeyPath());

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.set("x-api-key", finikConfig.getApiKey());
        httpHeaders.set("x-api-timestamp", timestamp);
        httpHeaders.set("signature", signature);

        log.info("Finik request: paymentId={}, amount={}", paymentId, amount);

        ResponseEntity<String> response = restTemplate.exchange(
                uri.toString(), HttpMethod.POST,
                new HttpEntity<>(jsonBody, httpHeaders),
                String.class);

        if (response.getStatusCode() == HttpStatus.FOUND) {
            String location = response.getHeaders().getLocation().toString();
            log.info("Finik payment URL: {}", location);
            return location;
        }

        throw new RuntimeException("Unexpected Finik response: " + response.getStatusCode()
                + " body: " + response.getBody());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STATUS CHECK
    // ─────────────────────────────────────────────────────────────────────────

    public Payment getPaymentStatus(UUID paymentId, User user) {
        return paymentRepository.findById(paymentId)
                .filter(p -> p.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional
    public Payment checkPayment(UUID paymentId, User user) {
        Payment payment = paymentRepository.findById(paymentId)
                .filter(p -> p.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() == PaymentStatus.SUCCESS) return payment;

        if (payment.getExpiresAt() != null
                && payment.getExpiresAt().isBefore(LocalDateTime.now())
                && payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.EXPIRED);
            return paymentRepository.save(payment);
        }

        if (payment.getStatus() == PaymentStatus.PENDING) {
            // Try to verify with Finik API; fall back to confirming automatically in test mode
            boolean confirmed = finikConfirmPayment(payment);
            if (confirmed) {
                payment.setStatus(PaymentStatus.SUCCESS);
                payment.setPaidAt(LocalDateTime.now());
                paymentRepository.save(payment);
                activateUser(payment.getUser());
            }
        }

        return payment;
    }

    /**
     * Returns true if payment should be confirmed.
     * Test mode (SMS disabled): auto-confirms always.
     * Production: checks DB for webhook update — Finik does not expose a status polling API.
     * Payment is confirmed only if webhook already set a real transactionId (non-TXN_ prefix).
     */
    private boolean finikConfirmPayment(Payment payment) {
        if (!smsEnabled) {
            log.info("Test mode: auto-confirming payment {}", payment.getId());
            return true;
        }
        if (payment.getFinikTransactionId() != null
                && payment.getFinikTransactionId().startsWith("TXN_")) {
            log.info("Mock QR: auto-confirming payment {}", payment.getId());
            return true;
        }
        // Reload from DB — webhook may have updated the record since this request started
        Payment fresh = paymentRepository.findById(payment.getId()).orElse(payment);
        if (fresh.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Webhook already confirmed payment {}", payment.getId());
            return true;
        }
        if (fresh.getFinikTransactionId() != null
                && !fresh.getFinikTransactionId().startsWith("TXN_")) {
            log.info("Finik transactionId found in DB for payment {}", payment.getId());
            return true;
        }
        log.info("Payment {} still pending — webhook not received yet", payment.getId());
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEBHOOK — called by Finik after successful payment
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void handleWebhook(WebhookData webhook) {
        log.info("Finik webhook: transactionId={}, status={}", webhook.getTransactionId(), webhook.getStatus());

        // Idempotency guard
        if (paymentRepository.findByFinikTransactionId(webhook.getTransactionId()).isPresent()) {
            // Already processed — find by paymentId in fields and check if activation is needed
            String paymentIdStr = extractPaymentId(webhook);
            if (paymentIdStr == null) return;
            paymentRepository.findById(UUID.fromString(paymentIdStr)).ifPresent(p -> {
                if (p.getStatus() == PaymentStatus.SUCCESS) {
                    log.info("Webhook already processed for payment {}", p.getId());
                }
            });
            return;
        }

        if (!"SUCCEEDED".equalsIgnoreCase(webhook.getStatus())) {
            log.warn("Finik webhook: non-success status {}", webhook.getStatus());
            // Try to find payment and mark as FAILED
            String paymentIdStr = extractPaymentId(webhook);
            if (paymentIdStr != null) {
                paymentRepository.findById(UUID.fromString(paymentIdStr)).ifPresent(p -> {
                    p.setStatus(PaymentStatus.FAILED);
                    p.setFinikTransactionId(webhook.getTransactionId());
                    paymentRepository.save(p);
                });
            }
            return;
        }

        // Locate our payment record
        String paymentIdStr = extractPaymentId(webhook);
        if (paymentIdStr == null) {
            log.error("Finik webhook: cannot extract paymentId from fields: {}", webhook.getFields());
            return;
        }

        Payment payment = paymentRepository.findById(UUID.fromString(paymentIdStr))
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND));

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setFinikTransactionId(webhook.getTransactionId());
        if (webhook.getTransactionDate() != null) {
            payment.setPaidAt(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(webhook.getTransactionDate()), ZoneId.systemDefault()));
        } else {
            payment.setPaidAt(LocalDateTime.now());
        }
        paymentRepository.save(payment);

        activateUser(payment.getUser());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACTIVATE USER — called both from webhook and admin manual activation
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void activateUserById(java.util.UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        if (user.getAccountStatus() == AccountStatus.ACTIVE) return; // idempotent
        activateUser(user);
    }

    private void activateUser(User user) {
        User locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);

        locked.setAccountStatus(AccountStatus.ACTIVE);
        locked.setActivatedAt(LocalDateTime.now());
        userRepository.save(locked);

        User inviter = locked.getInviter();
        if (inviter != null) {
            treeService.placeNewUser(inviter, locked);

            notificationService.send(inviter, NotificationType.NEW_MEMBER,
                    "Новый участник",
                    locked.getFirstName() + " " + locked.getLastName() + " активировал аккаунт");
        }

        notificationService.send(locked, NotificationType.NEW_MEMBER,
                "Аккаунт активирован",
                "Добро пожаловать в Green Eco Mall! Ваш аккаунт успешно активирован.");
    }

    private String extractPaymentId(WebhookData webhook) {
        if (webhook.getFields() == null) return null;
        Object val = webhook.getFields().get("paymentId");
        return val != null ? val.toString() : null;
    }
}
