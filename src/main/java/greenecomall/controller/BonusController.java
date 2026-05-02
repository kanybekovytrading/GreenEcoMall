package greenecomall.controller;

import greenecomall.dto.request.WithdrawalRequest;
import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.BonusResponse;
import greenecomall.dto.response.BonusSummaryResponse;
import greenecomall.entity.Bonus;
import greenecomall.entity.User;
import greenecomall.entity.Withdrawal;
import greenecomall.enums.BonusStatus;
import greenecomall.enums.BonusType;
import greenecomall.repository.BonusRepository;
import greenecomall.service.WithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Bonuses & Withdrawals", description = "История бонусов, сводка и заявки на вывод средств")
public class BonusController {

    private final BonusRepository bonusRepository;
    private final WithdrawalService withdrawalService;

    @Operation(summary = "История бонусов",
            description = """
                    Возвращает бонусы с пагинацией. Можно фильтровать по типу и/или статусу.

                    Каждая запись содержит `fromUserName` — имя участника, за которого начислен бонус.
                    """)
    @GetMapping("/bonuses")
    public ResponseEntity<ApiResponse<Page<BonusResponse>>> getBonuses(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Тип: REFERRAL_DIRECT | REFERRAL_INDIRECT | STAGE | DIVIDEND")
            @RequestParam(required = false) BonusType type,
            @Parameter(description = "Статус: PENDING | CONFIRMED | PAID")
            @RequestParam(required = false) BonusStatus status,
            @Parameter(description = "Номер страницы (с 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Размер страницы") @RequestParam(defaultValue = "20") int limit) {

        PageRequest pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Bonus> result;

        if (type != null && status != null) {
            result = bonusRepository.findByUserAndTypeAndStatus(user, type, status, pageable);
        } else if (type != null) {
            result = bonusRepository.findByUserAndType(user, type, pageable);
        } else if (status != null) {
            result = bonusRepository.findByUserAndStatus(user, status, pageable);
        } else {
            result = bonusRepository.findByUser(user, pageable);
        }

        return ResponseEntity.ok(ApiResponse.ok(result.map(this::toResponse)));
    }

    @Operation(summary = "Сводка по бонусам",
            description = "Возвращает: доступный баланс, сумму ожидающих/подтверждённых бонусов и разбивку по типам.")
    @GetMapping("/bonuses/summary")
    public ResponseEntity<ApiResponse<BonusSummaryResponse>> getSummary(@AuthenticationPrincipal User user) {
        BigDecimal pending   = bonusRepository.sumByUserAndStatus(user, BonusStatus.PENDING);
        BigDecimal confirmed = bonusRepository.sumByUserAndStatus(user, BonusStatus.CONFIRMED);
        BigDecimal total     = bonusRepository.sumAllByUser(user);

        Map<String, BigDecimal> byType = new HashMap<>();
        for (BonusType t : BonusType.values()) {
            BigDecimal sum = bonusRepository.findByUserAndType(user, t, PageRequest.of(0, Integer.MAX_VALUE))
                    .stream().map(Bonus::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            byType.put(t.name(), sum);
        }

        return ResponseEntity.ok(ApiResponse.ok(BonusSummaryResponse.builder()
                .available(user.getBalance())
                .pending(pending)
                .confirmed(confirmed)
                .total(total)
                .byType(byType)
                .build()));
    }

    @Operation(summary = "Подать заявку на вывод",
            description = """
                    Минимальная сумма — 1 000 сом. Сумма сразу списывается с баланса.

                    Способы вывода (`method`):
                    - `FINIK` — Finik по номеру телефона (+996XXXXXXXXX)
                    - `BANK_CARD` — банковская карта (номер карты в `requisite`, банк в `bankName`)
                    - `PHONE_TRANSFER` — перевод по номеру телефона (MBank, O!Деньги, Элсом и тд; укажи `bankName`)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Заявка создана"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Сумма меньше минимума или недостаточно средств", content = @Content)
    })
    @PostMapping("/withdrawals")
    public ResponseEntity<ApiResponse<Withdrawal>> createWithdrawal(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody WithdrawalRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                withdrawalService.createWithdrawal(user, req.amount(), req.method(), req.requisite(), req.bankName())));
    }

    @Operation(summary = "История заявок на вывод")
    @GetMapping("/withdrawals")
    public ResponseEntity<ApiResponse<Page<Withdrawal>>> getWithdrawals(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                withdrawalService.getUserWithdrawals(user, PageRequest.of(page, limit,
                        Sort.by(Sort.Direction.DESC, "createdAt")))));
    }

    private BonusResponse toResponse(Bonus b) {
        String fromName = null;
        if (b.getFromUser() != null) {
            fromName = b.getFromUser().getFirstName() + " " + b.getFromUser().getLastName();
        }
        return BonusResponse.builder()
                .id(b.getId())
                .type(b.getType())
                .amount(b.getAmount())
                .status(b.getStatus())
                .level(b.getLevel())
                .stage(b.getStage())
                .description(b.getDescription())
                .fromUserName(fromName)
                .confirmedAt(b.getConfirmedAt())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
