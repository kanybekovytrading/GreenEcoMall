package greenecomall.controller;

import greenecomall.dto.request.UpdateProfileRequest;
import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.NotificationResponse;
import greenecomall.dto.response.UserProfileResponse;
import greenecomall.entity.Notification;
import greenecomall.entity.User;
import greenecomall.service.NotificationService;
import greenecomall.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "User", description = "Профиль пользователя и уведомления")
public class UserController {

    private final UserService userService;
    private final NotificationService notificationService;

    @Value("${app.base-url:https://greenecomall.kg}")
    private String baseUrl;

    @Operation(summary = "Мой профиль", description = "Возвращает данные текущего авторизованного пользователя.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Профиль получен"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Не авторизован", content = @Content)
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getProfile(user)));
    }

    @Operation(summary = "Обновить профиль",
            description = "Обновляет имя, фамилию или пароль. Передавай только те поля, которые нужно изменить.")
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateProfileRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateProfile(user, req)));
    }

    @Operation(summary = "Список уведомлений",
            description = "Возвращает последние 50 уведомлений, отсортированных по дате (новые первые).")
    @GetMapping("/notifications")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getNotifications(@AuthenticationPrincipal User user) {
        List<NotificationResponse> list = notificationService.getLatest(user).stream()
                .map(this::toResponse).toList();
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @Operation(summary = "Отметить уведомление прочитанным")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Отмечено"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Уведомление не найдено", content = @Content)
    })
    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @AuthenticationPrincipal User user,
            @Parameter(description = "UUID уведомления") @PathVariable UUID id) {
        notificationService.markRead(user, id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Operation(summary = "Отметить все уведомления прочитанными")
    @PatchMapping("/notifications/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllRead(@AuthenticationPrincipal User user) {
        notificationService.markAllRead(user);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Operation(summary = "QR-код моей реферальной ссылки",
            description = """
                    Возвращает готовую реферальную ссылку и строку для генерации QR-кода.
                    QR генерируется на клиенте — просто передай `qrContent` в QR-библиотеку.
                    """)
    @GetMapping("/referral-qr")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> getReferralQr(
            @AuthenticationPrincipal User user) {
        String link = baseUrl + "/join?ref=" + user.getReferralCode();
        return ResponseEntity.ok(ApiResponse.ok(java.util.Map.of(
                "referralCode", user.getReferralCode(),
                "referralLink", link,
                "qrContent", link
        )));
    }

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .body(n.getBody())
                .isRead(n.getIsRead())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
