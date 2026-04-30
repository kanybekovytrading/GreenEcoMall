package greenecomall.controller;

import greenecomall.dto.request.*;
import greenecomall.dto.response.ApiResponse;
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
@Tag(name = "Auth", description = "Аутентификация: OTP, регистрация, вход, refresh токен")
@SecurityRequirements   // публичные эндпоинты — без JWT
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Отправить OTP",
            description = "Генерирует 6-значный код и логирует его (SMS-заглушка). " +
                    "Лимит: 5 запросов в час с одного IP.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP отправлен"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Превышен лимит запросов", content = @Content)
    })
    @PostMapping("/send-otp")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> sendOtp(
            @Valid @RequestBody SendOtpRequest req,
            HttpServletRequest httpRequest) {
        authService.sendOtp(req.phone(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("sent", true)));
    }

    @Operation(summary = "Верифицировать OTP",
            description = "Проверяет OTP-код и помечает его использованным. " +
                    "Срок действия кода — 5 минут.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Телефон верифицирован"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Неверный или истёкший OTP", content = @Content)
    })
    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
        authService.verifyOtp(req.phone(), req.code());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("verified", true)));
    }

    @Operation(summary = "Регистрация",
            description = "Создаёт аккаунт со статусом PENDING. " +
                    "Требует предварительной верификации телефона через OTP. " +
                    "Возвращает paymentId для оплаты вступительного взноса.")
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

    @Operation(summary = "Вход",
            description = "Возвращает `accessToken` (15 мин) и `refreshToken` (30 дней). " +
                    "Если аккаунт не оплачен — возвращает `needsPayment: true` и `paymentId`.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Успешный вход или требуется оплата"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Неверный телефон или пароль", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Аккаунт заблокирован", content = @Content)
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(req)));
    }

    @Operation(summary = "Обновить access token",
            description = "Принимает refreshToken, возвращает новую пару токенов.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Новые токены выданы"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Неверный или истёкший refresh token", content = @Content)
    })
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(req.refreshToken())));
    }
}
