package greenecomall.controller;

import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.QrResponse;
import greenecomall.entity.Payment;
import greenecomall.entity.User;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payment", description = "Оплата вступительного взноса через Finik QR")
public class PaymentController {

    private final PaymentService paymentService;

    @Value("${app.finik.webhook-secret}")
    private String webhookSecret;

    @Operation(summary = "Создать QR-код для оплаты",
            description = "Генерирует QR-код Finik для оплаты вступительного взноса (10 000 сом). " +
                    "QR действует 30 минут. Если QR ещё не истёк — возвращает существующий.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "QR-код создан"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Ожидающий платёж не найден", content = @Content)
    })
    @PostMapping("/create-qr")
    public ResponseEntity<ApiResponse<QrResponse>> createQr(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.createQr(user)));
    }

    @Operation(summary = "Статус платежа",
            description = "Проверяет статус платежа по его ID.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Статус получен"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Платёж не найден", content = @Content)
    })
    @GetMapping("/status/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @Parameter(description = "UUID платежа") @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        Payment p = paymentService.getPaymentStatus(id, user);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "status", p.getStatus(),
                "paid", "SUCCESS".equals(p.getStatus().name())
        )));
    }

    @Operation(summary = "Webhook от Finik (системный)",
            description = "Вызывается платёжной системой Finik после успешной оплаты. " +
                    "Проверяет HMAC-SHA256 подпись из заголовка `X-Finik-Signature`. " +
                    "Активирует аккаунт пользователя и запускает размещение в дереве.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Webhook принят"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Неверная HMAC-подпись", content = @Content)
    })
    @SecurityRequirements
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Boolean>> webhook(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {
        String signature = request.getHeader("X-Finik-Signature");
        validateHmac(payload.toString(), signature);
        paymentService.handleWebhook(payload.get("transactionId"), payload.get("status"));
        return ResponseEntity.ok(Map.of("received", true));
    }

    private void validateHmac(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            if (!expected.equals(signature)) {
                throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("HMAC validation error", e);
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
    }
}
