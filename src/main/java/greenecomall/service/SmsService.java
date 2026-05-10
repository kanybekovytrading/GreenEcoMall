package greenecomall.service;

import greenecomall.entity.OtpCode;
import greenecomall.repository.OtpCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * Sends OTP codes via SmsPro (https://smspro.nikita.kg).
 * Falls back to log-only mode when sms.smspro.enabled=false.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsService {

    private final RestTemplate restTemplate;
    private final OtpCodeRepository otpCodeRepository;

    @Value("${sms.smspro.url}")
    private String smsUrl;

    @Value("${sms.smspro.login}")
    private String login;

    @Value("${sms.smspro.password}")
    private String password;

    @Value("${sms.smspro.sender:GreenEcoMall}")
    private String sender;

    @Value("${sms.smspro.enabled:true}")
    private boolean enabled;

    @Value("${app.otp.expiration-minutes:5}")
    private int otpTtlMinutes;

    @Value("${app.otp.max-requests-per-hour:5}")
    private int maxPerHour;

    @Value("${telegram.bot-token:}")
    private String telegramBotToken;

    @Value("${telegram.dev-chat-id:}")
    private String telegramDevChatId;

    /**
     * Generates OTP, persists it, sends SMS. Returns the OtpCode's expiresAt.
     * Throws BusinessException on rate limit or SMS failure.
     */
    @Transactional
    public LocalDateTime sendOtp(String phone) {
        String formatted = formatPhone(phone);

        long recent = otpCodeRepository.countByPhoneAndCreatedAtAfter(
                formatted, LocalDateTime.now().minusHours(1));
        if (recent >= maxPerHour) {
            throw greenecomall.exception.BusinessException.of(
                    greenecomall.exception.ErrorCode.TOO_MANY_OTP_REQUESTS);
        }

        String code      = generateCode();
        LocalDateTime exp = LocalDateTime.now().plusMinutes(otpTtlMinutes);

        OtpCode otp = OtpCode.builder()
                .phone(formatted)
                .code(code)
                .expiresAt(exp)
                .build();
        otpCodeRepository.save(otp);

        if (!enabled) {
            sendViaTelegram(formatted, code);
            return exp;
        }

        sendViaSmsProApi(formatted, code);
        return exp;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void sendViaTelegram(String phone, String code) {
        if (telegramBotToken.isBlank() || telegramDevChatId.isBlank()) {
            log.warn("[OTP] Telegram not configured. Phone: {} Code: {}", phone, code);
            return;
        }
        String url = "https://api.telegram.org/bot" + telegramBotToken + "/sendMessage";
        String text = "🔐 GreenEcoMall OTP\n\nНомер: " + phone + "\nКод: *" + code + "*\n\nДействует 5 минут.";
        String body = "{\"chat_id\":\"" + telegramDevChatId + "\",\"text\":\"" + text + "\",\"parse_mode\":\"Markdown\"}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Telegram OTP sent for {}. Response: {}", phone, resp.getBody());
        } catch (Exception e) {
            log.error("Telegram send failed for {}: {}", phone, e.getMessage());
        }
    }

    private void sendViaSmsProApi(String phone, String code) {
        String messageId = "OTP" + System.currentTimeMillis();
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <message>
                    <login>%s</login>
                    <pwd>%s</pwd>
                    <id>%s</id>
                    <sender>%s</sender>
                    <text>Vash kod podtverjdeniya GreenEcoMall: %s</text>
                    <phones>
                        <phone>%s</phone>
                    </phones>
                </message>
                """.formatted(login, password, messageId, sender, code, phone);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    smsUrl, HttpMethod.POST,
                    new HttpEntity<>(xml, headers),
                    String.class);

            String body = response.getBody();
            log.info("SmsPro response: {}", body);

            Integer status = extractInt(body, "status");
            if (status == null || status != 0) {
                String msg = smsErrorMessage(status, extractStr(body, "message"));
                log.error("SmsPro error: {}", msg);
                throw new RuntimeException("SMS отправка не удалась: " + msg);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("SmsPro request failed", e);
            throw new RuntimeException("Ошибка отправки SMS: " + e.getMessage());
        }
    }

    /** Normalises any KG phone to 996XXXXXXXXX (12 digits, no +). */
    public String formatPhone(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0")) digits = "996" + digits.substring(1);
        if (!digits.startsWith("996")) digits = "996" + digits;
        return digits;
    }

    private String generateCode() {
        return String.format("%06d", 100000 + new Random().nextInt(900000));
    }

    private Integer extractInt(String xml, String tag) {
        String val = extractStr(xml, tag);
        if (val == null) return null;
        try { return Integer.parseInt(val.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String extractStr(String xml, String tag) {
        if (xml == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + tag + ">([^<]*)</" + tag + ">")
                .matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private String smsErrorMessage(Integer code, String desc) {
        if (code == null) return desc != null ? desc : "Unknown error";
        return switch (code) {
            case 0  -> "Success";
            case 1  -> "Invalid request format";
            case 2  -> "Invalid authorization";
            case 3  -> "Sender IP not allowed";
            case 4  -> "Insufficient account balance";
            case 5  -> "Invalid sender name";
            case 6  -> "Message blocked (stop words)";
            case 7  -> "Invalid phone number format";
            case 8  -> "Invalid send time format";
            case 9  -> "Request timeout";
            case 10 -> "Duplicate message ID";
            case 11 -> "Test mode – message not sent";
            default -> desc != null ? desc : "Unknown error " + code;
        };
    }
}
