package by.gdev.common.service.playwright.manager.impl;

import by.gdev.common.model.SiteName;
import by.gdev.common.service.playwright.manager.BrowserLaunchOptions;
import by.gdev.common.service.playwright.manager.CamoufoxActiveSessions;
import by.gdev.common.service.playwright.manager.CamoufoxLauncherClient;
import by.gdev.common.service.playwright.manager.PlaywrightBrowserManager;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Slf4j
@Component
public class PlaywrightCamoufoxManager implements PlaywrightBrowserManager {

    @Autowired
    private CamoufoxLauncherClient launcherClient;

    @Autowired
    private CamoufoxActiveSessions activeSessions;

    private final ThreadLocal<String> currentKey = new ThreadLocal<>();

    @Override
    public Playwright createPlaywright() {
        return Playwright.create();
    }

    @Override
    public Browser createBrowser(Playwright playwright, BrowserLaunchOptions options, SiteName site) {
        CamoufoxLauncherClient.LaunchResult result =
                launcherClient.launch(options.proxy(), site, options.headless(), options.requestUserEmail());
        currentKey.set(result.key());
        String country = options.proxy() != null && options.proxy().getCountry() != null
                ? options.proxy().getCountry()
                : "-";
        log.info("[{}] Подключение к Camoufox: {} country={} (key={})", site, result.endpoint(), country, result.key());
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Browser browser = playwright.firefox().connect(result.endpoint());
                activeSessions.register(result.key());
                if (attempt > 1) {
                    log.info("[{}] Camoufox connect OK с попытки {}", site, attempt);
                }
                return browser;
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("[{}] Camoufox connect попытка {}/3 не удалась: {}", site, attempt, msg);
                if (attempt < 3 && msg.contains("404")) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                break;
            }
        }
        launcherClient.release(result.key());
        currentKey.remove();
        throw new RuntimeException("Failed to connect to Camoufox", last);
    }

    @Override
    public BrowserContext createBrowserContext(Browser browser, BrowserLaunchOptions options, SiteName site) {
        return createBrowserContext(browser, options, site, null);
    }

    @Override
    public BrowserContext createBrowserContext(Browser browser, BrowserLaunchOptions options,
                                               SiteName site, Path storageStatePath) {
        return createBrowserContext(browser, options, site, storageStatePath, null);
    }

    @Override
    public BrowserContext createBrowserContext(Browser browser, BrowserLaunchOptions options,
                                               SiteName site, Path storageStatePath,
                                               String storageStateJson) {
        // storageState игнорируется: на connect восстановление через restoreCookies/restoreOrigins.
        // Контекст всегда новый: инстанс Camoufox может быть общим, переиспользование чужого
        // контекста привело бы к утечке cookies между пользователями.
        return browser.newContext();
    }

    @Override
    public void closeResources(Page page, BrowserContext context, Browser browser,
                               Playwright playwright, SiteName site) {
        try { if (page != null && !page.isClosed()) page.close(); } catch (Exception ignored) {}
        try { if (context != null) context.close(); } catch (Exception ignored) {}
        // browser.close() не вызываем: инстанс общий, закрытие очистило бы контексты других
        // клиентов. Соединение разрывается на playwright.close(), процесс освобождает лаунчер.
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}

        String key = currentKey.get();
        if (key != null) {
            activeSessions.unregister(key);
            launcherClient.release(key);
            currentKey.remove();
        }
    }
}