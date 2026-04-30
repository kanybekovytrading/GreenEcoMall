package greenecomall.controller;

import greenecomall.dto.request.WithdrawalRequest;
import greenecomall.dto.response.ApiResponse;
import greenecomall.dto.response.BonusSummaryResponse;
import greenecomall.entity.Bonus;
import greenecomall.entity.User;
import greenecomall.entity.Withdrawal;
import greenecomall.enums.BonusStatus;
import greenecomall.enums.BonusType;
import greenecomall.repository.BonusRepository;
import greenecomall.service.WithdrawalService;
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
public class BonusController {

    private final BonusRepository bonusRepository;
    private final WithdrawalService withdrawalService;

    @GetMapping("/bonuses")
    public ResponseEntity<ApiResponse<Page<Bonus>>> getBonuses(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) BonusType type,
            @RequestParam(required = false) BonusStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {

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

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/bonuses/summary")
    public ResponseEntity<ApiResponse<BonusSummaryResponse>> getSummary(@AuthenticationPrincipal User user) {
        BigDecimal pending = bonusRepository.sumByUserAndStatus(user, BonusStatus.PENDING);
        BigDecimal confirmed = bonusRepository.sumByUserAndStatus(user, BonusStatus.CONFIRMED);
        BigDecimal total = bonusRepository.sumAllByUser(user);

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

    @PostMapping("/withdrawals")
    public ResponseEntity<ApiResponse<Withdrawal>> createWithdrawal(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody WithdrawalRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(withdrawalService.createWithdrawal(user, req.amount())));
    }

    @GetMapping("/withdrawals")
    public ResponseEntity<ApiResponse<Page<Withdrawal>>> getWithdrawals(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                withdrawalService.getUserWithdrawals(user, PageRequest.of(page, limit,
                        Sort.by(Sort.Direction.DESC, "createdAt")))));
    }
}
