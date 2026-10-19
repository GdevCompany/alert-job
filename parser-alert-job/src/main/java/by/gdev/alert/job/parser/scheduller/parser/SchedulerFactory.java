package by.gdev.alert.job.parser.scheduller.parser;

import by.gdev.alert.job.parser.scheduller.SiteParserSchedulerBeanRegistrar;
import by.gdev.alert.job.parser.scheduller.parser.properties.ParserScheduleConfig;
import by.gdev.alert.job.parser.scheduller.parser.properties.ParserScheduleProperties;
import by.gdev.alert.job.parser.service.order.SiteParser;
import by.gdev.common.model.SiteName;
import by.gdev.common.service.proxy.ProxyCheckGate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class SchedulerFactory {

    private final List<SiteParser> parsers;
    private final OrderDispatcher dispatcher;
    private final ParserScheduleConfig scheduleConfig;
    private final SiteParserSchedulerBeanRegistrar siteParserSchedulerBeanRegistrar;
    private final ProxyCheckGate proxyCheckGate;

    @Value("${parsers.singlethread:false}")
    private boolean singlethread;

    private final TaskScheduler taskScheduler;


    private SiteParser getParserBySiteName(SiteName siteName) {
        return parsers.stream()
                .filter(p -> p.getSiteName() == siteName)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Парсер не найден: " + siteName));
    }

    @PostConstruct
    public void init() {

        if (singlethread) {
            log.info("Режим работы парсеров: ОДНОПОТОЧНЫЙ (один общий шедулер)");
            registerSchedulersSingleThread();
        } else {
            log.info("Режим работы парсеров: МНОГОПОТОЧНЫЙ (отдельный шедулер на каждый парсер)");
            registerSchedulersMultiThread();
        }
    }

    private void registerSchedulersMultiThread() {
        parsers.stream().filter(SiteParser::isActive).forEach(parser -> {
            SiteName siteName = parser.getSiteName();
            ParserScheduleProperties parserScheduleProperties = scheduleConfig.getForSite(siteName);
            if (parserScheduleProperties != null){
                ThreadPoolTaskScheduler individualScheduler = new ThreadPoolTaskScheduler();
                individualScheduler.setPoolSize(1);
                individualScheduler.setThreadNamePrefix("parser-" + parser.getSiteName() + "-");
                individualScheduler.initialize();

                SiteParserScheduler scheduler = new SiteParserScheduler(
                        parser,
                        dispatcher,
                        parserScheduleProperties,
                        individualScheduler,
                        proxyCheckGate
                );

                String beanName = siteName.name().toLowerCase() + SiteParserScheduler.class.getSimpleName();
                siteParserSchedulerBeanRegistrar.registerScheduler(scheduler, beanName);
                log.debug("Зарегистрирован бин '{}' для парсера {}", beanName, siteName);
                log.info("Создан ThreadPoolTaskScheduler для {} с потоком {}",
                        siteName, individualScheduler.getThreadNamePrefix());
            }
        });
    }

    private void registerSchedulersSingleThread() {
        parsers.stream().filter(SiteParser::isActive).forEach(parser -> {
            SiteName siteName = parser.getSiteName();
            ParserScheduleProperties parserScheduleProperties = scheduleConfig.getForSite(siteName);
            if (parserScheduleProperties != null){
                SiteParserScheduler scheduler = new SiteParserScheduler(
                        parser,
                        dispatcher,
                        parserScheduleProperties,
                        taskScheduler,
                        proxyCheckGate
                );

                String beanName = siteName.name().toLowerCase() + SiteParserScheduler.class.getSimpleName();
                siteParserSchedulerBeanRegistrar.registerScheduler(scheduler, beanName);
                log.debug("Зарегистрирован бин '{}' для парсера {}", beanName, siteName);
            }
        });
    }
}
