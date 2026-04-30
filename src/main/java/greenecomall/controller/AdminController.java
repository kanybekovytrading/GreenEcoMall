package greenecomall.controller;

import greenecomall.dto.response.AdminStatsResponse;
import greenecomall.dto.response.ApiResponse;
import greenecomall.entity.Product;
import greenecomall.entity.User;
import greenecomall.entity.Withdrawal;
import greenecomall.enums.AccountStatus;
import greenecomall.enums.PaymentStatus;
import greenecomall.enums.ProductStatus;
import greenecomall.enums.WithdrawalStatus;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.repository.*;
import greenecomall.service.WithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final WithdrawalService withdrawalService;
    private final PaymentRepository paymentRepository;
    private final ProductRepository productRepository;
    private final BonusRepository bonusRepository;

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Page<User>>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) Integer level,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // Simple findAll with pagination — production would use Specification
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.ok(userRepository.findAll(pageable)));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<User>> getUser(@PathVariable UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        return ResponseEntity.ok(ApiResponse.ok(user));
    }

    @PatchMapping("/users/{id}/block")
    public ResponseEntity<ApiResponse<Void>> blockUser(@PathVariable UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        user.setAccountStatus(AccountStatus.BLOCKED);
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping("/withdrawals")
    public ResponseEntity<ApiResponse<Page<Withdrawal>>> getPendingWithdrawals(
            @RequestParam(defaultValue = "PENDING") WithdrawalStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                withdrawalRepository.findByStatus(status, PageRequest.of(page, size,
                        Sort.by(Sort.Direction.ASC, "createdAt")))));
    }

    @PatchMapping("/withdrawals/{id}/approve")
    public ResponseEntity<ApiResponse<Withdrawal>> approveWithdrawal(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(withdrawalService.approve(id)));
    }

    @PatchMapping("/withdrawals/{id}/reject")
    public ResponseEntity<ApiResponse<Withdrawal>> rejectWithdrawal(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(withdrawalService.reject(id, body.get("note"))));
    }

    @GetMapping("/stats")
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

    @PostMapping("/products/{userId}/issue")
    public ResponseEntity<ApiResponse<Product>> issueProduct(@PathVariable UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));

        Product product = Product.builder()
                .user(user)
                .level(user.getCurrentLevel())
                .description(user.getCurrentLevel() == 3 ? "Автомобиль" : "Квартира")
                .valueSom(BigDecimal.ZERO)
                .status(ProductStatus.ISSUED)
                .issuedAt(LocalDateTime.now())
                .build();

        return ResponseEntity.ok(ApiResponse.ok(productRepository.save(product)));
    }
}
