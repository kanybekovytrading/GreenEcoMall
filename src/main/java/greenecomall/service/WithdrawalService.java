package greenecomall.service;

import greenecomall.entity.User;
import greenecomall.entity.Withdrawal;
import greenecomall.enums.NotificationType;
import greenecomall.enums.WithdrawalMethod;
import greenecomall.enums.WithdrawalStatus;
import greenecomall.exception.BusinessException;
import greenecomall.exception.ErrorCode;
import greenecomall.repository.UserRepository;
import greenecomall.repository.WithdrawalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final WithdrawalRepository withdrawalRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    private static final BigDecimal MIN_WITHDRAWAL = new BigDecimal("1000");

    @Transactional
    public Withdrawal createWithdrawal(User user, BigDecimal amount,
                                       WithdrawalMethod method, String requisite, String bankName) {
        if (amount.compareTo(MIN_WITHDRAWAL) < 0) {
            throw BusinessException.of(ErrorCode.WITHDRAWAL_MIN_AMOUNT);
        }

        User locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);
        if (locked.getBalance().compareTo(amount) < 0) {
            throw BusinessException.of(ErrorCode.INSUFFICIENT_BALANCE);
        }

        // Auto-save Finik phone to profile for quick reuse
        if (method == WithdrawalMethod.FINIK && locked.getFinikPhone() == null) {
            locked.setFinikPhone(requisite);
        }

        locked.setBalance(locked.getBalance().subtract(amount));
        userRepository.save(locked);

        return withdrawalRepository.save(Withdrawal.builder()
                .user(locked)
                .amount(amount)
                .method(method)
                .requisite(requisite)
                .bankName(bankName)
                .status(WithdrawalStatus.PENDING)
                .build());
    }

    public Page<Withdrawal> getUserWithdrawals(User user, Pageable pageable) {
        return withdrawalRepository.findByUser(user, pageable);
    }

    @Transactional
    public Withdrawal approve(UUID withdrawalId) {
        Withdrawal w = findOrThrow(withdrawalId);
        w.setStatus(WithdrawalStatus.APPROVED);
        w.setReviewedAt(LocalDateTime.now());
        notificationService.send(w.getUser(), NotificationType.WITHDRAWAL_STATUS,
                "Вывод одобрен", "Ваша заявка на вывод " + w.getAmount() + " сом одобрена");
        return withdrawalRepository.save(w);
    }

    @Transactional
    public Withdrawal reject(UUID withdrawalId, String note) {
        Withdrawal w = findOrThrow(withdrawalId);
        w.setStatus(WithdrawalStatus.REJECTED);
        w.setAdminNote(note);
        w.setReviewedAt(LocalDateTime.now());

        // Refund balance
        User locked = userRepository.findByIdForUpdate(w.getUser().getId()).orElse(w.getUser());
        locked.setBalance(locked.getBalance().add(w.getAmount()));
        userRepository.save(locked);

        notificationService.send(w.getUser(), NotificationType.WITHDRAWAL_STATUS,
                "Вывод отклонён", "Заявка на вывод отклонена. Средства возвращены на баланс.");
        return withdrawalRepository.save(w);
    }

    private Withdrawal findOrThrow(UUID id) {
        return withdrawalRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND));
    }
}
