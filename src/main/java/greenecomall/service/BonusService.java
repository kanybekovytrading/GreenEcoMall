package greenecomall.service;

import greenecomall.entity.Bonus;
import greenecomall.entity.User;
import greenecomall.enums.BonusStatus;
import greenecomall.enums.BonusType;
import greenecomall.enums.NotificationType;
import greenecomall.repository.BonusRepository;
import greenecomall.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BonusService {

    private final BonusRepository bonusRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // ─── Бонус завершителю этапа ─────────────────────────────────────────────

    private static final Map<Integer, BigDecimal> STAGE1_BONUS = Map.of(
            1, new BigDecimal("5000"),
            2, new BigDecimal("11000"),
            3, new BigDecimal("44000"),
            4, new BigDecimal("220000")
    );

    // Этап 2: только уровни 3 и 4
    private static final Map<Integer, BigDecimal> STAGE2_BONUS = Map.of(
            3, new BigDecimal("44000"),
            4, new BigDecimal("220000")
    );

    private static final Map<Integer, BigDecimal> STAGE3_BONUS = Map.of(
            1, new BigDecimal("25000"),
            2, new BigDecimal("100000"),
            3, new BigDecimal("12000"),  // USD
            4, new BigDecimal("25000")   // USD
    );

    private static final Map<Integer, String> STAGE3_CURRENCY = Map.of(
            1, "KGS", 2, "KGS", 3, "USD", 4, "USD"
    );

    // Этап 4: только уровень 4
    private static final BigDecimal STAGE4_BONUS_L4 = new BigDecimal("80000"); // USD

    // ─── Реферальный бонус за участника матрицы (Этап 1) ────────────────────
    private static final BigDecimal MEMBER_REFERRAL   = new BigDecimal("1250");
    private static final BigDecimal TIER2_DIVIDEND    = new BigDecimal("625");

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Бонус завершителю этапа.
     * Stage 1 — всегда, Stage 2 — только уровни 3-4, Stage 3 — всегда, Stage 4 — только уровень 4.
     */
    @Transactional
    public void createStageBonuses(User user, int level, int stage) {
        switch (stage) {
            case 1 -> {
                BigDecimal amount = STAGE1_BONUS.getOrDefault(level, BigDecimal.ZERO);
                creditBonus(user, BonusType.STAGE, amount, "KGS", level, stage,
                        "Бонус за завершение Этапа 1 (Уровень " + level + ")");
            }
            case 2 -> {
                BigDecimal amount = STAGE2_BONUS.get(level);
                if (amount != null) {
                    creditBonus(user, BonusType.STAGE, amount, "KGS", level, stage,
                            "Бонус за завершение Этапа 2 (Уровень " + level + ")");
                }
            }
            case 3 -> {
                BigDecimal amount = STAGE3_BONUS.getOrDefault(level, BigDecimal.ZERO);
                String currency = STAGE3_CURRENCY.getOrDefault(level, "KGS");
                if (level <= 2) {
                    creditBonus(user, BonusType.STAGE, amount, currency, level, stage,
                            "Бонус за завершение Этапа 3 (Уровень " + level + ")");
                } else {
                    String reward = level == 3 ? "Автомобиль" : "Квартира";
                    bonusRepository.save(Bonus.builder()
                            .user(user)
                            .type(BonusType.STAGE)
                            .amount(amount)
                            .currency(currency)
                            .status(BonusStatus.CONFIRMED)
                            .level(level)
                            .stage(stage)
                            .description("Физическая награда: " + reward
                                    + " + " + amount.toPlainString() + " " + currency)
                            .build());
                    notificationService.send(user, NotificationType.STAGE_COMPLETE,
                            "Награда — " + reward + "!",
                            "Поздравляем! Вы заработали " + reward.toLowerCase()
                                    + " и " + amount.toPlainString() + " " + currency);
                }
            }
            case 4 -> {
                if (level == 4) {
                    creditBonus(user, BonusType.STAGE, STAGE4_BONUS_L4, "USD", level, stage,
                            "Бонус за завершение Этапа 4 (Уровень 4)");
                }
            }
        }
    }

    /**
     * Бонус за завершение Этапа 1:
     *
     * - root получает: max(0, baseBonus - externalTier1.size() × 1250)
     *   (за каждого тир-1 участника с чужой реф-ссылкой вычитается 1250)
     * - Реальный инвайтер каждого такого тир-1 получает 1250
     * - За каждого тир-2 участника root ВСЕГДА получает 625 как дивиденд
     *   (независимо от чьей реф-ссылки он пришёл)
     *
     * @param root          кто завершил Этап 1
     * @param level         уровень
     * @param externalTier1 тир-1 участники, чей inviter != root
     * @param tier2Members  все реальные тир-2 участники матрицы root
     */
    @Transactional
    public void createStage1Bonuses(User root, int level,
                                    List<User> externalTier1, List<User> tier2Members) {
        BigDecimal baseBonus = STAGE1_BONUS.getOrDefault(level, BigDecimal.ZERO);
        BigDecimal deduction = MEMBER_REFERRAL.multiply(BigDecimal.valueOf(externalTier1.size()));
        BigDecimal rootBonus = baseBonus.subtract(deduction).max(BigDecimal.ZERO);

        if (rootBonus.compareTo(BigDecimal.ZERO) > 0) {
            creditBonus(root, BonusType.STAGE, rootBonus, "KGS", level, 1,
                    "Бонус за завершение Этапа 1 (Уровень " + level + ")"
                    + (externalTier1.isEmpty() ? "" : " — " + externalTier1.size() + " уч. пришли по чужим реф."));
        }

        for (User member : externalTier1) {
            if (member.getInviter() == null) continue;
            User inviter = userRepository.findById(member.getInviter().getId())
                    .orElse(member.getInviter());
            creditBonus(inviter, BonusType.REFERRAL_DIRECT, MEMBER_REFERRAL, "KGS", level, 1,
                    "Реферальный бонус за " + member.getFirstName() + " " + member.getLastName()
                    + " (Этап 1 завершил " + root.getFirstName() + " " + root.getLastName() + ")");
        }

        for (User member : tier2Members) {
            creditBonus(root, BonusType.REFERRAL_INDIRECT, TIER2_DIVIDEND, "KGS", level, 1,
                    "Дивиденд за участника 2-го яруса: "
                    + member.getFirstName() + " " + member.getLastName());
        }
    }

    /**
     * Cron — дивиденды акционерам уровня 4 этапа 4 (6.25% от оборота платформы).
     */
    @Transactional
    public void createDividendBonuses(BigDecimal totalVolume) {
        var shareholders = userRepository.findAll().stream()
                .filter(u -> u.getCurrentLevel() == 4 && u.getCurrentStage() == 4)
                .toList();

        if (shareholders.isEmpty() || totalVolume.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal pool = totalVolume.multiply(new BigDecimal("0.0625"));
        BigDecimal perShareholder = pool.divide(
                new BigDecimal(shareholders.size()), 2, java.math.RoundingMode.DOWN);

        for (User shareholder : shareholders) {
            creditBonus(shareholder, BonusType.DIVIDEND, perShareholder, "KGS", 4, 4,
                    "Дивидендный бонус акционера");
        }
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private void creditBonus(User user, BonusType type, BigDecimal amount, String currency,
                             int level, int stage, String description) {
        bonusRepository.save(Bonus.builder()
                .user(user)
                .type(type)
                .amount(amount)
                .currency(currency)
                .status(BonusStatus.CONFIRMED)
                .level(level)
                .stage(stage)
                .description(description)
                .build());

        // Баланс обновляем только для KGS
        if ("KGS".equals(currency)) {
            User locked = userRepository.findByIdForUpdate(user.getId()).orElse(user);
            locked.setBalance(locked.getBalance().add(amount));
            userRepository.save(locked);
        }

        String amountStr = amount.toPlainString() + " " + currency;
        notificationService.send(user, NotificationType.BONUS_RECEIVED,
                "Бонус зачислен!",
                amountStr + " — " + description);
    }
}
