package greenecomall.controller;

import greenecomall.dto.response.AdminStatsResponse;
import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.request.RegisterRequest;
import greenecomall.entity.User;
import greenecomall.entity.Withdrawal;
import greenecomall.service.AuthService;
import greenecomall.service.PaymentService;
import greenecomall.service.TreeService;
import greenecomall.enums.AccountStatus;
import greenecomall.enums.WithdrawalStatus;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.repository.*;
import greenecomall.service.WithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Панель администратора (только для роли ADMIN)")
public class AdminController {

    private final UserRepository userRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final WithdrawalService withdrawalService;
    private final BonusRepository bonusRepository;
    private final AuthService authService;
    private final PaymentService paymentService;
    private final TreeService treeService;

    @Operation(summary = "Список пользователей",
            description = "Возвращает всех пользователей с пагинацией.")
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Page<User>>> getUsers(
            @Parameter(description = "Статус: PENDING | ACTIVE | BLOCKED") @RequestParam(required = false) AccountStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.ok(userRepository.findAll(pageable)));
    }

    @Operation(summary = "Карточка пользователя")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Пользователь найден"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Не найден", content = @Content)
    })
    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<User>> getUser(@PathVariable UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        return ResponseEntity.ok(ApiResponse.ok(user));
    }

    @Operation(summary = "Заблокировать пользователя")
    @PatchMapping("/users/{id}/block")
    public ResponseEntity<ApiResponse<Void>> blockUser(@PathVariable UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        user.setAccountStatus(AccountStatus.BLOCKED);
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Operation(summary = "Заявки на вывод")
    @GetMapping("/withdrawals")
    public ResponseEntity<ApiResponse<Page<Withdrawal>>> getWithdrawals(
            @Parameter(description = "Статус: PENDING | APPROVED | REJECTED")
            @RequestParam(defaultValue = "PENDING") WithdrawalStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                withdrawalRepository.findByStatus(status, PageRequest.of(page, size,
                        Sort.by(Sort.Direction.ASC, "createdAt")))));
    }

    @Operation(summary = "Одобрить заявку на вывод")
    @PatchMapping("/withdrawals/{id}/approve")
    public ResponseEntity<ApiResponse<Withdrawal>> approveWithdrawal(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(withdrawalService.approve(id)));
    }

    @Operation(summary = "Отклонить заявку на вывод",
            description = "Возвращает сумму на баланс пользователя.")
    @PatchMapping("/withdrawals/{id}/reject")
    public ResponseEntity<ApiResponse<Withdrawal>> rejectWithdrawal(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(withdrawalService.reject(id, body.get("note"))));
    }

    @Operation(summary = "Распределение участников по уровням",
            description = "Возвращает количество активных участников на каждом уровне (1-4).")
    @GetMapping("/stats/distribution")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLevelDistribution() {
        List<Object[]> rows = userRepository.countByCurrentLevel();
        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (int i = 1; i <= 4; i++) byLevel.put("level" + i, 0L);
        for (Object[] row : rows) {
            Integer lvl = (Integer) row[0];
            Long count  = (Long) row[1];
            if (lvl != null && lvl >= 1 && lvl <= 4) byLevel.put("level" + lvl, count);
        }
        long total = byLevel.values().stream().mapToLong(Long::longValue).sum();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("total", total, "byLevel", byLevel)));
    }

    @Operation(summary = "Добавить участника (создать аккаунт вручную)",
            description = """
                    Администратор может зарегистрировать нового участника напрямую.
                    Пропускает шаги OTP и оплаты — аккаунт сразу создаётся в статусе PENDING.
                    Для активации нужно будет провести оплату или использовать эндпоинт активации.
                    """)
    @PostMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createUser(
            @Valid @RequestBody RegisterRequest req) {
        var result = authService.registerByAdmin(req);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "userId", result.userId(),
                "paymentId", result.paymentId()
        )));
    }

    @Operation(summary = "Активировать аккаунт пользователя (без оплаты)",
            description = "Администратор может вручную активировать аккаунт пользователя, обходя оплату. " +
                    "Запускает полный флоу активации: размещение в дереве, начисление реферальных бонусов.")
    @PatchMapping("/users/{id}/activate")
    public ResponseEntity<ApiResponse<Void>> activateUser(@PathVariable UUID id) {
        paymentService.activateUserById(id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Operation(summary = "Экспорт участников в CSV",
            description = "Скачивает CSV-файл со списком всех активных участников (ID, имя, телефон, уровень, этап, баланс, дата активации).")
    @GetMapping(value = "/export/users", produces = "text/csv")
    public ResponseEntity<byte[]> exportUsers() {
        List<User> users = userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == greenecomall.enums.AccountStatus.ACTIVE)
                .toList();

        StringBuilder csv = new StringBuilder();
        csv.append("ID,Имя,Фамилия,Телефон,Уровень,Этап,Баланс,Дата активации\n");
        for (User u : users) {
            csv.append(u.getId()).append(',')
               .append(escape(u.getFirstName())).append(',')
               .append(escape(u.getLastName())).append(',')
               .append(u.getPhone()).append(',')
               .append(u.getCurrentLevel()).append(',')
               .append(u.getCurrentStage()).append(',')
               .append(u.getBalance()).append(',')
               .append(u.getActivatedAt() != null ? u.getActivatedAt().toLocalDate() : "").append('\n');
        }

        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }

    @Operation(summary = "Восстановить позиции в дереве",
            description = """
                    Находит всех активных участников у которых есть inviter_id, но нет записи в tree_positions.
                    Размещает их в дерево через BFS. Используй если участники активные но не видны в дереве.
                    Возвращает список что было исправлено.
                    """)
    @PostMapping("/repair/tree-positions")
    public ResponseEntity<ApiResponse<List<String>>> repairTreePositions() {
        List<String> report = treeService.repairMissingPositions();
        return ResponseEntity.ok(ApiResponse.ok(report));
    }

    private String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Operation(summary = "Статистика платформы")
    @GetMapping({"/stats", "/dashboard/stats"})
    public ResponseEntity<ApiResponse<AdminStatsResponse>> getStats() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByAccountStatus(AccountStatus.ACTIVE);
        long pendingWithdrawals = withdrawalRepository.countByStatus(WithdrawalStatus.PENDING);
        BigDecimal totalVolume = bonusRepository.sumTotalConfirmed();

        return ResponseEntity.ok(ApiResponse.ok(AdminStatsResponse.builder()
                .totalUsers(totalUsers)
                .activeUsers(activeUsers)
                .totalVolume(totalVolume)
                .pendingWithdrawals(pendingWithdrawals)
                .pendingPayments(0)
                .build()));
    }
}
