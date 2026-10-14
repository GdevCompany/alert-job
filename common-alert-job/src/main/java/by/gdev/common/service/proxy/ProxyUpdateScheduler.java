package by.gdev.common.service.proxy;

import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.model.proxy.ProxyState;
import by.gdev.common.service.proxy.event.ProxyListUpdatedEvent;
import by.gdev.common.service.proxy.supplier.ProxySupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyUpdateScheduler {

    private final ProxySupplier proxySupplier;
    private final ProxyCheckerService proxyCheckerService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${parser.proxy.refresh-interval-hours:24}")
    private int refreshIntervalHours;

    /** Включена ли проверка страны через geo-сервис. */
    @Value("${proxy.checker.country-filter.enabled:true}")
    private boolean countryFilterEnabled;

    /** Не гонять вторую полную проверку сразу после стартовой (мс). */
    @Value("${proxy.checker.grace-after-check-ms:900000}")
    private long graceAfterCheckMs;

    @Scheduled(
            initialDelayString = "${proxy.checker.supplier-refresh-initial-delay-ms:900000}",
            fixedDelayString = "#{${parser.proxy.refresh-interval-hours:24} * 60 * 60 * 1000}")
    public void refreshProxies() {
        log.info("Проверка списка прокси на обновления... (интервал {} ч., country-filter={})",
                refreshIntervalHours, countryFilterEnabled);
        List<ProxyCredentials> fresh = proxySupplier.loadFreshProxies();
        if (fresh.isEmpty()) {
            log.warn("Обновление прокси пропущено — свежий список пустой!");
            return;
        }

        // Считаем текущие SUPPLIER-прокси ДО замены
        List<ProxyCredentials> current = proxySupplier.getProxies();
        long oldSupplierCount = current.stream()
                .filter(p -> p.getSource() == ProxySource.SUPPLIER)
                .count();

        // Заменяем только SUPPLIER-прокси
        proxySupplier.replaceSupplierProxies(fresh);

        // Новое количество SUPPLIER-прокси
        long newSupplierCount = fresh.size();
        long totalAfter = proxySupplier.getProxies().size();
        long apiCount = totalAfter - newSupplierCount;

        log.warn("Список SUPPLIER-прокси обновлён (было {} SUPPLIER, стало {} SUPPLIER)",
                oldSupplierCount, newSupplierCount);
        log.warn("Всего прокси после обновления: {} (SUPPLIER: {}, API: {})",
                totalAfter, newSupplierCount, apiCount);

        if (ProxyCheckerService.isWithinCheckGracePeriod(graceAfterCheckMs)) {
            log.info("Прокси SUPPLIER обновлены; полная перепроверка пропущена (недавно была проверка при старте)");
            eventPublisher.publishEvent(new ProxyListUpdatedEvent(this, proxySupplier.getProxies()));
            return;
        }

        ProxyCheckerService.ProxyCheckSummary summary =
                proxyCheckerService.checkAllProxies(countryFilterEnabled);
        long active = proxySupplier.getProxies().stream()
                .filter(p -> p.getState() == ProxyState.ACTIVE || p.getState() == ProxyState.WARMING_UP)
                .count();
        log.info("Прокси (сводка после обновления SUPPLIER): рабочих {}/{}, активных {}, всего {}",
                summary.working(), summary.total(), active, summary.total());
        eventPublisher.publishEvent(new ProxyListUpdatedEvent(this, proxySupplier.getProxies()));
    }
}