package by.gdev.common.service.proxy;

import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.model.proxy.ProxyState;
import by.gdev.common.service.proxy.event.ProxyListUpdatedEvent;
import by.gdev.common.service.proxy.supplier.ProxySupplier;
import by.gdev.common.util.DurationFormatter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyCheckerScheduler {

    private final ProxyCheckerService proxyCheckerService;
    private final ProxyAdditionalService proxyAdditionalService;
    private final ProxySupplier proxySupplier;
    private final ApplicationEventPublisher eventPublisher;

    /** Включена ли проверка страны через geo-сервис. */
    @Value("${proxy.checker.country-filter.enabled:true}")
    private boolean countryFilterEnabled;

    @PostConstruct
    public void init() {
        run(true);
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void runScheduled() {
        run(false);
    }

    private void run(boolean verbose) {
        if (verbose) {
            log.info("Запуск обновления и проверки прокси (первый прогон, подробные логи)...");
        } else {
            log.info("Плановая проверка прокси (только сводка)...");
        }

        List<ProxyCredentials> apiProxies = proxyAdditionalService.fetchProxies();
        if (verbose) {
            log.info("API-прокси: {}", apiProxies.size());
        }

        if (!apiProxies.isEmpty()) {
            proxySupplier.addApiProxies(apiProxies);
        }

        List<ProxyCredentials> allProxies = proxySupplier.getProxies();
        if (verbose) {
            log.info("Всего прокси после добавления API: {}", allProxies.size());
        }

        ProxyCheckerService.ProxyCheckSummary summary =
                proxyCheckerService.checkProxies(allProxies, countryFilterEnabled, verbose);

        long active = allProxies.stream()
                .filter(p -> p.getState() == ProxyState.ACTIVE || p.getState() == ProxyState.WARMING_UP)
                .count();
        eventPublisher.publishEvent(new ProxyListUpdatedEvent(this, allProxies));

        if (verbose) {
            logCountryDistribution(allProxies);
            log.info("Проверка завершена. Активных: {}", active);
            log.info("Готово. Всего: {}", allProxies.size());
        } else {
            log.info("Прокси (сводка): за {}, рабочих {}/{}, активных {}, всего {}",
                    DurationFormatter.formatDuration(summary.elapsedMs()),
                    summary.working(), summary.total(), active, allProxies.size());
        }
    }

    private void logCountryDistribution(List<ProxyCredentials> proxies) {
        if (proxies.isEmpty()) {
            log.info("Список прокси пуст.");
            return;
        }
        Map<String, Long> map = proxies.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getCountry() != null ? p.getCountry() : "UNKNOWN",
                        Collectors.counting()
                ));
        log.info("РАСПРЕДЕЛЕНИЕ ПО СТРАНАМ:");
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> log.info("   {}: {} прокси", e.getKey(), e.getValue()));
    }
}