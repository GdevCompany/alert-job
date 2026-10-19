package by.gdev.alert.job.parser.scheduller.parser;

import by.gdev.alert.job.parser.scheduller.parser.properties.ParserScheduleProperties;
import by.gdev.alert.job.parser.service.order.SiteParser;
import by.gdev.common.model.OrderDTO;
import by.gdev.common.service.proxy.ProxyCheckGate;
import by.gdev.common.util.DurationFormatter;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;

import java.util.Date;
import java.util.List;

@Slf4j
public class SiteParserScheduler {

    private final SiteParser parser;
    private final OrderDispatcher dispatcher;
    private final ProxyCheckGate proxyCheckGate;


    public SiteParserScheduler(SiteParser parser,
                               OrderDispatcher dispatcher,
                               ParserScheduleProperties props,
                               TaskScheduler taskScheduler,
                               ProxyCheckGate proxyCheckGate) {
        this.parser = parser;
        this.dispatcher = dispatcher;
        this.proxyCheckGate = proxyCheckGate;

        // регистрируем задачу прямо в конструкторе
        if (parser.isActive()){
            taskScheduler.scheduleWithFixedDelay(
                    this::runParser,
                    new Date(System.currentTimeMillis() + props.getInitialDelayMillis()),
                    props.getFixedDelayMillis());

            log.debug("Шедулер для {} запущен: initialDelay={}s, fixedDelay={}s",
                    parser.getSiteName(), props.getInitialDelaySeconds(), props.getFixedDelaySeconds());
        }
        else log.debug("Парсер для {} ВЫКЛЮЧЕН..", parser.getSiteName());

    }

    private void runParser() {
        if (!proxyCheckGate.mayRunProxyConsumers()) {
            log.info("Парсер {} пропущен: первая проверка прокси при старте ещё не завершена", parser.getSiteName());
            return;
        }
        log.debug("Запуск парсера {} в потоке {}", parser.getSiteName(), Thread.currentThread().getName());
        try {
            long startTime = System.currentTimeMillis();
            List<OrderDTO> orders = parser.parse();
            long duration = System.currentTimeMillis() - startTime;
            String formattedDuration = DurationFormatter.formatDuration(duration);
            dispatcher.dispatch(orders, parser.getSiteName());
            log.debug("Парсер {} завершил работу за {}, найдено {} заказов",
                    parser.getSiteName(), formattedDuration, orders.size());
        }
        catch (PlaywrightException e) {
            if (e.getMessage() != null && (e.getMessage().contains("Timeout") || e.getMessage().contains("TimeoutError"))) {
                    log.debug("Timeout playwright для парсера {}", parser.getSiteName());
                    return;
                }
            log.error("Playwright ошибка парсера {} : {}", parser.getSiteName(),  e.getMessage());
        }
        catch (Exception e) {
            log.error("Ошибка парсера {}: {}", parser.getSiteName(), e.getMessage(), e);
        }
    }
}