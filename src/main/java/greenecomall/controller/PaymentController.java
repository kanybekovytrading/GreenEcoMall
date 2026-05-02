package greenecomall.controller;

import greenecomall.dto.WebhookData;
import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.QrResponse;
import greenecomall.entity.Payment;
import greenecomall.entity.User;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.finik.FinikSignatureVerifier;
import greenecomall.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "2. Payment", description = "Оплата вступительного взноса через Finik QR")
public class PaymentController {

    private final PaymentService paymentService;
    private final FinikSignatureVerifier signatureVerifier;

    @Operation(summary = "[ШАГ 4/5] Создать QR-код для оплаты",
            description = """
                    **Шаг 4 — оплата взноса 10 000 сом.**
                    Создаёт платёж в Finik и возвращает ссылку-QR (CardType: FINIK_QR).

                    QR-ссылка действует **30 минут**. Если не истекла — возвращает существующую.

                    После оплаты Finik автоматически вызовет **webhook** → аккаунт станет ACTIVE.

                    ➡ Проверь статус через **GET /status/{paymentId}**
                    ➡ Если webhook не пришёл — нажми **[Я оплатил] POST /check/{id}**
                    ➡ После активации вызови **[ШАГ 5] login**
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "QR-код создан"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Ожидающий платёж не найден", content = @Content)
    })
    @PostMapping("/create-qr")
    public ResponseEntity<ApiResponse<QrResponse>> createQr(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.createQr(user)));
    }

    @Operation(summary = "Проверить статус оплаты",
            description = """
                    Опрашивай этот эндпоинт после создания QR пока не получишь `"paid": true`.
                    Статусы: **PENDING** → ждём | **SUCCESS** → оплачено | **FAILED** / **EXPIRED** → ошибка.
                    """)
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
                "paid",   "SUCCESS".equals(p.getStatus().name())
        )));
    }

    @Operation(summary = "Я оплатил — ручная проверка оплаты",
            description = """
                    Кнопка **"Я оплатил"** в мобильном приложении.
                    Принудительно перепроверяет статус платежа.

                    Используй если webhook не пришёл автоматически (сеть, таймаут и т.п.).
                    Если QR просрочен — статус меняется на `EXPIRED`.
                    """)
    @PostMapping("/check/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkPayment(
            @Parameter(description = "UUID платежа") @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        Payment p = paymentService.checkPayment(id, user);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "status",    p.getStatus(),
                "paid",      "SUCCESS".equals(p.getStatus().name()),
                "activated", "SUCCESS".equals(p.getStatus().name())
        )));
    }

    @Operation(summary = "Webhook от Finik (системный)",
            description = """
                    Вызывается платёжной системой Finik после успешной оплаты.
                    Проверяет **RSA SHA256** подпись из заголовка `Signature`.
                    Статус `SUCCEEDED` → активирует аккаунт и размещает в дереве.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Webhook принят"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Неверная RSA-подпись", content = @Content)
    })
    @SecurityRequirements
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Boolean>> webhook(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers) {
        try {
            log.info("Finik webhook received, headers: {}", headers.keySet());

            if (!signatureVerifier.verifyWebhookSignature(rawBody, headers)) {
                log.error("Finik webhook: invalid RSA signature");
                throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
            }

            WebhookData data = parseWebhookData(rawBody);
            paymentService.handleWebhook(data);
            return ResponseEntity.ok(Map.of("received", true));

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Finik webhook processing error", e);
            return ResponseEntity.ok(Map.of("received", false));
        }
    }

    private WebhookData parseWebhookData(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(json, WebhookData.class);
        } catch (Exception e) {
            throw new RuntimeException("Cannot parse Finik webhook body", e);
        }
    }
}
