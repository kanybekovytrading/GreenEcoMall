package greenecomall.config;

import greenecomall.service.BonusService;
import greenecomall.service.TreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Resolves the circular dependency: TreeService ↔ BonusService.
 * TreeService calls BonusService methods; BonusService has no dependency on TreeService.
 * We break the cycle by injecting BonusService into TreeService via setter after construction.
 */
@Configuration
@RequiredArgsConstructor
public class ServiceConfig {

    private final TreeService treeService;
    private final BonusService bonusService;

    @PostConstruct
    public void wireDependencies() {
        treeService.setBonusService(bonusService);
    }
}
