package greenecomall.controller;

import greenecomall.dto.request.*;
import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.InviterResponse;
import greenecomall.dto.response.LoginResponse;
import greenecomall.dto.response.RegisterResponse;
import greenecomall.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "1. Auth", description = "Аутентификация: OTP, регистрация, вход, refresh токен")
@SecurityRequirements   // публичные эндпоинты — без JWT
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "[ШАГ 2/5] Отправить OTP",
            description = """
                    **Второй шаг.** Отправляет 6-значный OTP-код на телефон для верификации.
                    Вызывать после регистрации (шаг 1). Лимит: 5 запросов в час.

                    ➡ После этого вызови **[ШАГ 3] verify-otp**
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP отправлен"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Превышен лимит запросов", content = @Content)
    })
    @PostMapping("/send-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendOtp(
            @Valid @RequestBody SendOtpRequest req,
            HttpServletRequest httpRequest) {
        java.time.LocalDateTime expiresAt = authService.sendOtp(req.phone(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "sent", true,
                "expiresAt", expiresAt.toString()
        )));
    }

    @Operation(summary = "[ШАГ 3/5] Верифицировать OTP",
            description = """
                    **Третий шаг.** Проверяет код из SMS. Срок действия — 5 минут. Код одноразовый.

                    ➡ После этого вызови **[ШАГ 4] Payment → create-qr** для оплаты
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Телефон верифицирован"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Неверный или истёкший OTP", content = @Content)
    })
    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
        authService.verifyOtp(req.phone(), req.code());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("verified", true)));
    }

    @Operation(summary = "[ШАГ 1/5] Регистрация",
            description = """
                    **Первый шаг.** Создаёт аккаунт со статусом **PENDING**.
                    Требования:
                    - `referralCode` — код пригласившего (получи через GET /api/auth/inviter или /api/auth/platform-referral)

                    Возвращает `accessToken`, `userId` и `paymentId`.

                    ➡ После этого верифицируй телефон: **[ШАГ 2] send-otp** → **[ШАГ 3] verify-otp**
                    ➡ Затем оплати взнос: **[ШАГ 4] Payment → create-qr**
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Пользователь создан"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Телефон не верифицирован / ошибка валидации", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Реферальный код не найден", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Телефон или паспорт уже зарегистрированы", content = @Content)
    })
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.register(req)));
    }

    @Operation(summary = "[ШАГ 5/5] Войти в аккаунт",
            description = """
                    **Пятый шаг.** Вызывай после успешной оплаты (аккаунт стал ACTIVE).

                    Возвращает:
                    - `accessToken` — вставь в **Authorize** вверху страницы (действует 15 мин)
                    - `refreshToken` — храни отдельно, нужен для обновления access-токена

                    Если аккаунт ещё не оплачен — вернётся `{ needsPayment: true, paymentId: "..." }` и токены для доступа к /create-qr.

                    ⚠️ После получения `accessToken` нажми **Authorize** вверху и введи:
                    `Bearer <accessToken>`
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Успешный вход или требуется оплата"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Неверный телефон или пароль", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Аккаунт заблокирован", content = @Content)
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(req)));
    }

    @Operation(summary = "Реферальный код платформы (для первой регистрации)",
            description = """
                    Возвращает реферальный код администратора платформы.
                    Используй если у тебя нет реферальной ссылки — зарегистрируйся под корневым аккаунтом.
                    """)
    @GetMapping("/platform-referral")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> getPlatformReferral() {
        return ResponseEntity.ok(ApiResponse.ok(
                authService.getPlatformReferralCode()));
    }

    @Operation(summary = "Получить информацию о пригласителе по реферальному коду",
            description = """
                    Вызывается на **первом экране регистрации** когда пользователь вводит реферальный код.
                    Показывает имя и уровень пригласившего — чтобы новый участник убедился, что код правильный.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Пригласитель найден"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Реферальный код не найден", content = @Content)
    })
    @GetMapping("/inviter")
    public ResponseEntity<ApiResponse<InviterResponse>> getInviter(
            @io.swagger.v3.oas.annotations.Parameter(description = "Реферальный код (8 символов)", example = "GEM7K2QP")
            @RequestParam String referralCode) {
        return ResponseEntity.ok(ApiResponse.ok(authService.getInviterInfo(referralCode)));
    }

    @Operation(summary = "Обновить access token (когда истёк)",
            description = """
                    Вызывай когда `accessToken` истёк (через 15 мин).
                    Передай `refreshToken` из ответа login — получишь новую пару токенов.
                    `refreshToken` действует 30 дней.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Новые токены выданы"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Неверный или истёкший refresh token", content = @Content)
    })
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(req.refreshToken())));
    }
}
