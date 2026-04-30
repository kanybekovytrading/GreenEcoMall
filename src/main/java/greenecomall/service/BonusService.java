package greenecomall.service;

import greenecomall.entity.Bonus;
import greenecomall.entity.User;
import greenecomall.enums.BonusStatus;
import greenecomall.enums.BonusType;
import greenecomall.enums.NotificationType;
import greenecomall.enums.Role;
import greenecomall.repository.BonusRepository;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BonusService {

    private final BonusRepository bonusRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // Referral bonus amounts by level
    private static final Map<Integer, BigDecimal> DIRECT_BONUS = Map.of(
            1, new BigDecimal("1250"),
            2, new BigDecimal("11000"),
            3, new BigDecimal("22000"),
            4, new BigDecimal("110000")
    );
    private static final Map<Integer, BigDecimal> INDIRECT_BONUS = Map.of(
            1, new BigDecimal("625"),
            2, new BigDecimal("5500"),
            3, new BigDecimal("11000"),
            4, new BigDecimal("55000")
    );
    private static final Map<Integer, BigDecimal> STAGE3_BONUS = Map.of(
            1, new BigDecimal("25000"),
            2, new BigDecimal("100000")
    );

    /**
     * Create PENDING referral bonuses when new user activates.
     * Become CONFIRMED only when new user completes Stage 1.
     */
    @Transactional
    public void createReferralBonuses(User newUser, User inviter) {
        int level = inviter.getCurrentLevel();

        bonusRepository.save(Bonus.builder()
                .user(inviter)
                .fromUser(newUser)
                .type(BonusType.REFERRAL_DIRECT)
                .amount(DIRECT_BONUS.getOrDefault(level, BigDecimal.ZERO))
                .status(BonusStatus.PENDING)
                .level(level)
                .stage(1)
                .description("Прямой реферальный бонус за " + newUser.getFirstName())
                .build());

        notificationService.send(inviter, NotificationType.BONUS_RECEIVED,
                "Реферальный бонус",
                "Начислен бонус за приглашение " + newUser.getFirstName() + " (ожидание подтверждения)");

        // Grandparent indirect bonus
        if (inviter.getInviter() != null) {
            User grandparent = inviter.getInviter();
            bonusRepository.save(Bonus.builder()
                    .user(grandparent)
                    .fromUser(newUser)
                    .type(BonusType.REFERRAL_INDIRECT)
                    .amount(INDIRECT_BONUS.getOrDefault(level, BigDecimal.ZERO))
                    .status(BonusStatus.PENDING)
                    .level(level)
                    .stage(1)
                    .description("Косвенный реферальный бонус за " + newUser.getFirstName())
                    .build());
        }
    }

    /**
     * Called from TreeService.onStage1Completed — confirms all PENDING bonuses
     * where fromUser = user who just completed Stage 1.
     */
    @Transactional
    public void confirmBonusesForUser(User fromUser) {
        List<Bonus> pending = bonusRepository.findByFromUserAndStatus(fromUser, BonusStatus.PENDING);
        for (Bonus bonus : pending) {
            bonus.setStatus(BonusStatus.CONFIRMED);
            bonus.setConfirmedAt(LocalDateTime.now());

            User owner = userRepository.findByIdForUpdate(bonus.getUser().getId())
                    .orElse(bonus.getUser());
            owner.setBalance(owner.getBalance().add(bonus.getAmount()));
            userRepository.save(owner);

            notificationService.send(owner, NotificationType.BONUS_CONFIRMED,
                    "Бонус подтверждён",
                    "Бонус " + bonus.getAmount() + " сом подтверждён и добавлен к балансу");
        }
        bonusRepository.saveAll(pending);
    }

    /**
     * Stage-3 completion bonus. Credited immediately (CONFIRMED).
     * Levels 3 and 4 → record as physical product reward.
     */
    @Transactional
    public void createStageBonuses(User user, int level, int stage) {
        if (level == 1 || level == 2) {
            BigDecimal amount = STAGE3_BONUS.getOrDefault(level, BigDecimal.ZERO);
            bonusRepository.save(Bonus.builder()
                    .user(user)
                    .type(BonusType.STAGE)
                    .amount(amount)
                    .status(BonusStatus.CONFIRMED)
                    .level(level)
                    .stage(stage)
                    .description("Этапный бонус за завершение Этапа " + stage + " Уровня " + level)
                    .build());

            User locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);
            locked.setBalance(locked.getBalance().add(amount));
            userRepository.save(locked);

            notificationService.send(user, NotificationType.BONUS_RECEIVED,
                    "Этапный бонус!",
                    "Вам начислено " + amount + " сом за завершение Этапа 3");
        } else {
            // Physical reward (auto or apartment)
            String desc = level == 3 ? "Автомобиль" : "Квартира";
            bonusRepository.save(Bonus.builder()
                    .user(user)
                    .type(BonusType.STAGE)
                    .amount(BigDecimal.ZERO)
                    .status(BonusStatus.CONFIRMED)
                    .level(level)
                    .stage(stage)
                    .description("Физическая награда: " + desc)
                    .build());

            notificationService.send(user, NotificationType.STAGE_COMPLETE,
                    "Награда — " + desc + "!",
                    "Поздравляем! Вы заработали " + desc.toLowerCase());
        }
    }

    /**
     * Cron job — distributes 6.25% of total platform volume among all shareholders.
     */
    @Transactional
    public void createDividendBonuses(BigDecimal totalVolume) {
        List<User> shareholders = userRepository.findAll().stream()
                .filter(u -> u.getCurrentLevel() == 4 && u.getCurrentStage() == 4)
                .toList();

        if (shareholders.isEmpty() || totalVolume.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal pool = totalVolume.multiply(new BigDecimal("0.0625"));
        BigDecimal perShareholder = pool.divide(new BigDecimal(shareholders.size()), 2, java.math.RoundingMode.DOWN);

        for (User shareholder : shareholders) {
            bonusRepository.save(Bonus.builder()
                    .user(shareholder)
                    .type(BonusType.DIVIDEND)
                    .amount(perShareholder)
                    .status(BonusStatus.CONFIRMED)
                    .description("Дивидендный бонус акционера")
                    .build());

            User locked = userRepository.findByIdForUpdate(shareholder.getId()).orElse(shareholder);
            locked.setBalance(locked.getBalance().add(perShareholder));
            userRepository.save(locked);
        }
    }
}
