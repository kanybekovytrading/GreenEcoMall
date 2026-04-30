package greenecomall.scheduler;

import greenecomall.service.BonusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class DividendScheduler {

    private final BonusService bonusService;

    /** Runs on the 1st of every month at 00:00 */
    @Scheduled(cron = "0 0 0 1 * *")
    public void distributeDividends() {
        log.info("Starting monthly dividend distribution");
        // TODO: calculate actual platform turnover from payment records
        BigDecimal mockVolume = BigDecimal.ZERO;
        bonusService.createDividendBonuses(mockVolume);
    }
}
