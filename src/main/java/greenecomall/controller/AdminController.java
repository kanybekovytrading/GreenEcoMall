package greenecomall.controller;

import greenecomall.dto.response.AdminStatsResponse;
import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.NewsItemResponse;
import greenecomall.dto.response.NewsStatsResponse;
import greenecomall.dto.response.WithdrawalItemResponse;
import greenecomall.dto.response.WithdrawalStatsResponse;
import greenecomall.dto.request.CreateNewsRequest;
import greenecomall.dto.request.RegisterRequest;
import greenecomall.dto.request.UpdateNewsRequest;
import greenecomall.entity.User;
import greenecomall.entity.Withdrawal;
import greenecomall.enums.NewsStatus;
import greenecomall.service.AuthService;
import greenecomall.service.NewsService;
import greenecomall.service.PaymentService;
import greenecomall.service.TreeService;
import greenecomall.service.WithdrawalService;
import greenecomall.enums.AccountStatus;
import greenecomall.enums.WithdrawalStatus;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.repository.*;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final NewsService newsService;

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

    @Operation(summary = "Статистика выплат — 4 карточки вверху",
            description = "pendingCount, pendingSum, approvedToday, totalPaid")
    @GetMapping("/withdrawals/stats")
    public ResponseEntity<ApiResponse<WithdrawalStatsResponse>> getWithdrawalStats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return ResponseEntity.ok(ApiResponse.ok(WithdrawalStatsResponse.builder()
                .pendingCount(withdrawalRepository.countByStatus(WithdrawalStatus.PENDING))
                .pendingSum(withdrawalRepository.sumByStatus(WithdrawalStatus.PENDING))
                .approvedToday(withdrawalRepository.countByStatusAndReviewedAtAfter(WithdrawalStatus.APPROVED, todayStart))
                .totalPaid(withdrawalRepository.sumTotalApproved())
                .build()));
    }

    @Operation(summary = "Заявки на вывод",
            description = "status: PENDING | APPROVED | REJECTED. period: ALL | MONTH (по умолчанию ALL)")
    @GetMapping("/withdrawals")
    public ResponseEntity<ApiResponse<Page<WithdrawalItemResponse>>> getWithdrawals(
            @Parameter(description = "Статус: PENDING | APPROVED | REJECTED | ALL")
            @RequestParam(required = false) WithdrawalStatus status,
            @Parameter(description = "Период: ALL | MONTH")
            @RequestParam(defaultValue = "ALL") String period,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        LocalDateTime from = "MONTH".equalsIgnoreCase(period)
                ? LocalDateTime.now().minusMonths(1) : null;

        Page<Withdrawal> raw;
        if (status != null && from != null) {
            raw = withdrawalRepository.findByStatusAndCreatedAtAfter(status, from, pageable);
        } else if (status != null) {
            raw = withdrawalRepository.findByStatus(status, pageable);
        } else if (from != null) {
            raw = withdrawalRepository.findByCreatedAtAfter(from, pageable);
        } else {
            raw = withdrawalRepository.findAll(pageable);
        }

        Page<WithdrawalItemResponse> result = raw.map(w -> WithdrawalItemResponse.builder()
                .id(w.getId())
                .userId(w.getUser().getId())
                .userName(w.getUser().getFirstName() + " " + w.getUser().getLastName())
                .userPhone(w.getUser().getPhone())
                .amount(w.getAmount())
                .status(w.getStatus())
                .method(w.getMethod().name())
                .requisite(w.getRequisite())
                .bankName(w.getBankName())
                .adminNote(w.getAdminNote())
                .createdAt(w.getCreatedAt())
                .reviewedAt(w.getReviewedAt())
                .build());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @Operation(summary = "Одобрить заявку на вывод")
    @PatchMapping("/withdrawals/{id}/approve")
    public ResponseEntity<ApiResponse<WithdrawalItemResponse>> approveWithdrawal(@PathVariable UUID id) {
        Withdrawal w = withdrawalService.approve(id);
        return ResponseEntity.ok(ApiResponse.ok(toWithdrawalItem(w)));
    }

    @Operation(summary = "Отклонить заявку на вывод",
            description = "Возвращает сумму на баланс пользователя.")
    @PatchMapping("/withdrawals/{id}/reject")
    public ResponseEntity<ApiResponse<WithdrawalItemResponse>> rejectWithdrawal(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        Withdrawal w = withdrawalService.reject(id, body.get("note"));
        return ResponseEntity.ok(ApiResponse.ok(toWithdrawalItem(w)));
    }

    private WithdrawalItemResponse toWithdrawalItem(Withdrawal w) {
        return WithdrawalItemResponse.builder()
                .id(w.getId())
                .userId(w.getUser().getId())
                .userName(w.getUser().getFirstName() + " " + w.getUser().getLastName())
                .userPhone(w.getUser().getPhone())
                .amount(w.getAmount())
                .status(w.getStatus())
                .method(w.getMethod().name())
                .requisite(w.getRequisite())
                .bankName(w.getBankName())
                .adminNote(w.getAdminNote())
                .createdAt(w.getCreatedAt())
                .reviewedAt(w.getReviewedAt())
                .build();
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

    @Operation(summary = "Repair: завершить Stage 1 для участников, у которых матрица уже полная",
            description = "Находит всех Stage-1 пользователей где tier1+tier2 >= 6, но onStage1Completed не был вызван (последний слот заняли ускорители). Вызывает переход вручную.")
    @PostMapping("/repair/stage1-completions")
    public ResponseEntity<ApiResponse<List<String>>> repairStage1Completions() {
        List<String> report = treeService.repairMissingStage1Completions();
        return ResponseEntity.ok(ApiResponse.ok(report));
    }

    @Operation(summary = "Repair: разместить Stage-2 участников которые не были связаны в дереве",
            description = "Находит активных участников на Stage 2+, которые не являются чьим-либо fixedPartnerLeft/Right, и повторно запускает размещение. Исправляет отвязанные поддеревья.")
    @PostMapping("/repair/stage2-placements")
    public ResponseEntity<ApiResponse<List<String>>> repairStage2Placements() {
        List<String> report = treeService.repairStage2Placements();
        return ResponseEntity.ok(ApiResponse.ok(report));
    }

    @Operation(summary = "Repair: завершить Stage 3 для участников у которых вся команда уже на Stage 3",
            description = "Ищет участников на Stage 3, у которых оба fixedPartner уже >=Stage3, и вызывает onStage3Completed.")
    @PostMapping("/repair/stage3-completions")
    public ResponseEntity<ApiResponse<List<String>>> repairStage3Completions() {
        List<String> report = treeService.repairStage3Completions();
        return ResponseEntity.ok(ApiResponse.ok(report));
    }

    @Operation(summary = "Принудительно завершить Stage 2 для пользователя",
            description = "Вызывает onStage2Completed если оба fixed-partner слота уже заполнены, но стейдж не обновился. Тело: { \"userId\": \"uuid\" }")
    @PostMapping("/repair/trigger-stage2-complete")
    public ResponseEntity<ApiResponse<String>> triggerStage2Complete(@RequestBody Map<String, UUID> body) {
        UUID userId = body.get("userId");
        if (userId == null) throw BusinessException.of(ErrorCode.USER_NOT_FOUND);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        if (user.getFixedPartnerLeft() == null || user.getFixedPartnerRight() == null) {
            return ResponseEntity.ok(ApiResponse.ok("SKIPPED: не оба слота заполнены"));
        }
        if (user.getCurrentStage() != 2) {
            return ResponseEntity.ok(ApiResponse.ok("SKIPPED: пользователь не на Stage 2 (текущий: " + user.getCurrentStage() + ")"));
        }
        treeService.onStage2Completed(user, user.getCurrentLevel());
        return ResponseEntity.ok(ApiResponse.ok("OK: " + user.getFirstName() + " " + user.getLastName() + " переведён на Stage 3"));
    }

    @Operation(summary = "Переместить Stage-2 участника под нового хоста",
            description = """
                    Снимает пользователя с текущего хоста (если есть) и ставит под нового.
                    Если у нового хоста оба слота заполнятся — автоматически вызывается onStage2Completed.
                    Тело: { "userToMoveId": "uuid", "newHostId": "uuid" }
                    """)
    @PostMapping("/repair/move-stage2-partner")
    public ResponseEntity<ApiResponse<String>> moveStage2Partner(
            @RequestBody Map<String, UUID> body) {
        UUID userToMoveId = body.get("userToMoveId");
        UUID newHostId    = body.get("newHostId");
        if (userToMoveId == null || newHostId == null) {
            throw BusinessException.of(ErrorCode.USER_NOT_FOUND);
        }
        User userToMove = userRepository.findById(userToMoveId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        User newHost = userRepository.findById(newHostId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        String result = treeService.moveStage2Partner(userToMove, newHost);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ── Тестовые пользователи ────────────────────────────────────────────────

    @Operation(
            summary = "Создать тестовых пользователей (быстрый тест дерева)",
            description = """
                    Создаёт и сразу активирует ДВУХ пользователей на каждую запись без OTP и без оплаты.
                    Телефон, пароль и паспорт генерируются автоматически.
                    firstName/lastName необязательны — если не указаны, генерируются автоматически.

                    Пример тела запроса:
                    ```json
                    [
                      {"inviterCode": "AZAMAT123"},
                      {"inviterCode": "ALIYA_REF"}
                    ]
                    ```
                    Каждая запись → 2 созданных пользователя.
                    Ответ: имя, userId, referralCode — используй referralCode следующих в цепочке.
                    """)
    @PostMapping("/test/create-users")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> createTestUsers(
            @RequestBody List<TestUserEntry> entries) {

        List<Map<String, String>> results = new ArrayList<>();
        long base = System.currentTimeMillis() % 10_000_000L;
        int counter = 0;

        for (TestUserEntry e : entries) {
            for (int pair = 1; pair <= 2; pair++) {
                long idx = base + counter++;
                String phone = "+99670" + String.format("%07d", idx);
                String firstName = (e.firstName() != null && !e.firstName().isBlank())
                        ? e.firstName() + (pair == 2 ? "2" : "") : "Test" + idx;
                String lastName  = (e.lastName()  != null && !e.lastName().isBlank())
                        ? e.lastName()  + (pair == 2 ? "2" : "") : "User" + idx;

                greenecomall.dto.request.RegisterRequest req = new greenecomall.dto.request.RegisterRequest(
                        firstName, lastName,
                        phone,
                        "TEST" + idx,
                        "test123456",
                        e.inviterCode(),
                        null,
                        "testword"
                );

                greenecomall.dto.response.RegisterResponse reg = authService.registerByAdmin(req);
                paymentService.activateUserById(reg.userId());

                greenecomall.entity.User created = userRepository.findById(reg.userId()).orElseThrow();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("name", firstName + " " + lastName);
                row.put("userId", reg.userId().toString());
                row.put("phone", phone);
                row.put("referralCode", created.getReferralCode());
                results.add(row);
            }
        }

        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    public record TestUserEntry(String firstName, String lastName, String inviterCode) {}

    @Operation(
            summary = "Создать тестовых пользователей Уровня 0 (Fast Start)",
            description = """
                    Создаёт и сразу активирует Fast Start пользователей (уровень 0) без OTP и без оплаты.
                    Телефон и паспорт генерируются автоматически.
                    Пользователи попадают в очередь Быстрого Старта и автоматически спариваются.

                    Пример тела запроса:
                    ```json
                    [
                      {"firstName": "Тест", "lastName": "Один",  "inviterCode": "AZAMAT123"},
                      {"firstName": "Тест", "lastName": "Два",   "inviterCode": "AZAMAT123"},
                      {"firstName": "Тест", "lastName": "Три",   "inviterCode": "AZAMAT123"}
                    ]
                    ```
                    Ответ: имя, userId, phone, referralCode для каждого созданного пользователя.
                    """)
    @PostMapping("/test/create-fast-start-users")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> createFastStartTestUsers(
            @RequestBody List<TestUserEntry> entries) {

        List<Map<String, String>> results = new ArrayList<>();
        long base = System.currentTimeMillis() % 10_000_000L;
        int counter = 0;

        for (TestUserEntry e : entries) {
            for (int pair = 1; pair <= 2; pair++) {
                long idx = base + counter++;
                String phone = "+99671" + String.format("%07d", idx);
                String firstName = (e.firstName() != null && !e.firstName().isBlank())
                        ? e.firstName() + (pair == 2 ? "2" : "") : "FST" + idx;
                String lastName  = (e.lastName()  != null && !e.lastName().isBlank())
                        ? e.lastName()  + (pair == 2 ? "2" : "") : "User" + idx;

                greenecomall.dto.request.RegisterRequest req = new greenecomall.dto.request.RegisterRequest(
                        firstName, lastName,
                        phone,
                        "FST" + idx,
                        "test123456",
                        e.inviterCode(),
                        greenecomall.enums.RegistrationPlan.FAST_START,
                        "testword"
                );

                greenecomall.dto.response.RegisterResponse reg = authService.registerByAdmin(req);
                paymentService.activateUserById(reg.userId());

                greenecomall.entity.User created = userRepository.findById(reg.userId()).orElseThrow();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("name", firstName + " " + lastName);
                row.put("userId", reg.userId().toString());
                row.put("phone", phone);
                row.put("referralCode", created.getReferralCode());
                results.add(row);
            }
        }

        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ── Новости ──────────────────────────────────────────────────────────────

    @Operation(summary = "Статистика новостей — 4 карточки")
    @GetMapping("/news/stats")
    public ResponseEntity<ApiResponse<NewsStatsResponse>> getNewsStats() {
        return ResponseEntity.ok(ApiResponse.ok(newsService.getStats()));
    }

    @Operation(summary = "Список всех новостей",
            description = "status: DRAFT | SCHEDULED | PUBLISHED | ARCHIVED | null (все). search — поиск по заголовку.")
    @GetMapping("/news")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<NewsItemResponse>>> adminNewsList(
            @RequestParam(required = false) NewsStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.adminList(status, search, page, size)));
    }

    @Operation(summary = "Создать и опубликовать новость")
    @PostMapping("/news")
    public ResponseEntity<ApiResponse<NewsItemResponse>> createNews(
            @RequestBody @jakarta.validation.Valid CreateNewsRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.create(req)));
    }

    @Operation(summary = "Сохранить черновик")
    @PostMapping("/news/draft")
    public ResponseEntity<ApiResponse<NewsItemResponse>> saveDraft(
            @RequestBody @jakarta.validation.Valid CreateNewsRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.saveDraft(req)));
    }

    @Operation(summary = "Редактировать новость")
    @PutMapping("/news/{id}")
    public ResponseEntity<ApiResponse<NewsItemResponse>> updateNews(
            @PathVariable UUID id,
            @RequestBody UpdateNewsRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.update(id, req)));
    }

    @Operation(summary = "Опубликовать черновик/запланированную")
    @PatchMapping("/news/{id}/publish")
    public ResponseEntity<ApiResponse<NewsItemResponse>> publishNews(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.publish(id)));
    }

    @Operation(summary = "Архивировать новость")
    @PatchMapping("/news/{id}/archive")
    public ResponseEntity<ApiResponse<NewsItemResponse>> archiveNews(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.archive(id)));
    }

    @Operation(summary = "Восстановить из архива (переводит в черновик)")
    @PatchMapping("/news/{id}/restore")
    public ResponseEntity<ApiResponse<NewsItemResponse>> restoreNews(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.restore(id)));
    }

    @Operation(summary = "Закрепить / открепить новость")
    @PatchMapping("/news/{id}/pin")
    public ResponseEntity<ApiResponse<NewsItemResponse>> togglePin(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(newsService.togglePin(id)));
    }

    @Operation(summary = "Удалить новость навсегда")
    @DeleteMapping("/news/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteNews(@PathVariable UUID id) {
        newsService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Operation(
            summary = "Добавить медиа к новости",
            description = """
                    Прикрепляет загруженный файл к новости.

                    **Шаг 1:** Загрузи файл через `POST /api/upload?type=NEWS_MEDIA` → получишь `objectKey`.
                    **Шаг 2:** Передай `objectKey` в это тело: `{"objectKey": "news/media/uuid.jpg"}`.

                    Возвращает новый медиа-объект с presigned URL.
                    """
    )
    @PostMapping("/news/{id}/media")
    public ResponseEntity<ApiResponse<greenecomall.dto.response.NewsMediaResponse>> addNewsMedia(
            @PathVariable UUID id,
            @RequestBody java.util.Map<String, String> body) {
        String objectKey = body.get("objectKey");
        return ResponseEntity.ok(ApiResponse.ok(newsService.addMedia(id, objectKey)));
    }

    @Operation(summary = "Удалить медиа из новости", description = "Удаляет медиа-вложение из новости и из хранилища.")
    @DeleteMapping("/news/{id}/media/{mediaId}")
    public ResponseEntity<ApiResponse<Void>> deleteNewsMedia(
            @PathVariable UUID id,
            @PathVariable UUID mediaId) {
        newsService.deleteMedia(id, mediaId);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    private String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Operation(summary = "Динамика роста — данные для графика",
            description = """
                    Возвращает массив точек {date, newUsers, totalUsers} по дням за период.
                    **days** — количество дней назад (7, 30, 90). По умолчанию 30.
                    `totalUsers` — накопительный итог (cumulative), именно его рисуй на графике.
                    """)
    @GetMapping("/stats/growth")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getGrowthStats(
            @RequestParam(defaultValue = "30") int days) {

        LocalDateTime from = LocalDateTime.now().minusDays(days).toLocalDate().atStartOfDay();
        long baseCount = userRepository.countActiveBefore(from);

        List<Object[]> rows = userRepository.countActivatedByDay(from);
        Map<java.time.LocalDate, Long> byDay = new LinkedHashMap<>();
        for (Object[] row : rows) {
            java.time.LocalDate date = ((java.sql.Date) row[0]).toLocalDate();
            byDay.put(date, (Long) row[1]);
        }

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        long cumulative = baseCount;
        java.time.LocalDate today = java.time.LocalDate.now();
        for (java.time.LocalDate d = from.toLocalDate(); !d.isAfter(today); d = d.plusDays(1)) {
            long newUsers = byDay.getOrDefault(d, 0L);
            cumulative += newUsers;
            result.add(Map.of(
                    "date", d.toString(),
                    "newUsers", newUsers,
                    "totalUsers", cumulative
            ));
        }
        return ResponseEntity.ok(ApiResponse.ok(result));
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
