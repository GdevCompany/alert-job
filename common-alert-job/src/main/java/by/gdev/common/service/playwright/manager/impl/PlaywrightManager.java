package by.gdev.common.service.playwright.manager.impl;

import by.gdev.common.model.SiteName;
import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.service.playwright.manager.StealthInitScripts;
import by.gdev.common.service.playwright.manager.BrowserLaunchOptions;
import by.gdev.common.service.playwright.manager.PlaywrightBrowserManager;
import by.gdev.common.service.proxy.ProxyService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PlaywrightManager implements PlaywrightBrowserManager {

    @Autowired
    private ProxyService proxyService;

    @Value("${playwright.chromium.channel:}")
    private String chromiumChannel;

    @Value("${playwright.chromium.headless-new:true}")
    private boolean headlessNew;

    @Value("${playwright.chromium.slow-mo-ms:120}")
    private int slowMoMs;

    @Override
    public Playwright createPlaywright() {
        try {
            return Playwright.create();
        } catch (PlaywrightException e) {
            log.error("Playwright initialization error", e);
            throw new RuntimeException("Failed to initialize Playwright", e);
        }
    }

    @Override
    public Browser createBrowser(Playwright playwright, BrowserLaunchOptions options, SiteName site) {
        return createBrowser(playwright, options.proxy(), options.headless(), options.useProxy(), site);
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
        return createBrowserContext(browser, options.proxy(), options.useProxy(), site,
                storageStatePath, storageStateJson);
    }

    public Browser createBrowser(Playwright playwright, ProxyCredentials proxy, boolean headless, boolean useProxy, SiteName site) {
        List<String> args = new ArrayList<>(List.of(
                "--disable-blink-features=AutomationControlled",
                "--start-maximized",
                "--disable-infobars",
                "--disable-notifications",
                "--no-default-browser-check",
                "--no-first-run",
                "--disable-features=IsolateOrigins,site-per-process",
                "--disable-site-isolation-trials",
                "--ignore-certificate-errors"
        ));
        if (headless && headlessNew) {
            args.add("--headless=new");
        }

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setSlowMo(slowMoMs)
                .setArgs(args);

        if (StringUtils.hasText(chromiumChannel)) {
            launchOptions.setChannel(chromiumChannel.trim());
            log.debug("Chromium channel для {}: {}", site, chromiumChannel.trim());
        }

        if (useProxy) {
            ProxyCredentials usedProxy = proxy != null ? proxy : proxyService.getRandomActiveProxy();
            launchOptions.setProxy(new Proxy("http://" + usedProxy.getHost() + ":" + usedProxy.getPort())
                    .setUsername(usedProxy.getUsername())
                    .setPassword(usedProxy.getPassword()));
        }

        try {
            Browser browser = playwright.chromium().launch(launchOptions);
            log.debug("Браузер для {} успешно запущен (прокси: {}, headless: {}, channel: {})",
                    site, useProxy ? "включен" : "выключен", headless,
                    StringUtils.hasText(chromiumChannel) ? chromiumChannel : "bundled");
            return browser;
        } catch (Exception e) {
            log.error("Ошибка запуска браузера для {}: {}", site, e.getMessage(), e);
            throw e;
        }
    }

    public ProxyCredentials getProxyWithRetry(int maxRetries, long retryDelayMs) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ProxyCredentials proxy = proxyService.getRandomActiveProxy();
                if (proxy != null) return proxy;
                log.warn("Попытка {}/{}: Нет активных прокси", attempt, maxRetries);
                if (attempt < maxRetries) {
                    try { Thread.sleep(retryDelayMs * attempt); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            } catch (IllegalStateException e) {
                log.warn("Попытка {}/{}: {}", attempt, maxRetries, e.getMessage());
            } catch (Exception e) {
                log.error("Ошибка получения прокси с попытки {}: {}", attempt, e.getMessage(), e);
            }
        }
        return null;
    }

    @Override
    public void closeResources(Page page, BrowserContext context, Browser browser, Playwright playwright, SiteName site) {
        if (page != null) {
            try {
                if (!page.isClosed()) {
                    try {
                        page.waitForLoadState(LoadState.NETWORKIDLE,
                                new Page.WaitForLoadStateOptions().setTimeout(1200));
                    } catch (Exception ignored) {}
                    page.close();
                    log.debug("Page закрыт для {}", site);
                }
            } catch (Exception e) {
                log.error("Ошибка закрытия page в {}: {}", site, e.getMessage(), e);
            }
        }
        if (context != null) {
            try {
                if (!context.pages().isEmpty()) {
                    for (Page p : context.pages()) {
                        try { if (!p.isClosed()) p.close(); } catch (Exception ignored) {}
                    }
                }
                context.close();
                log.debug("Context закрыт для {}", site);
            } catch (Exception e) {
                log.error("Ошибка закрытия context в {}: {}", site, e.getMessage(), e);
            }
        }
        if (browser != null) {
            try {
                if (browser.isConnected()) {
                    if (!browser.contexts().isEmpty()) {
                        for (BrowserContext ctx : browser.contexts()) {
                            try { ctx.close(); } catch (Exception ignored) {}
                        }
                    }
                    browser.close();
                    log.debug("Browser закрыт для {}", site);
                }
            } catch (Exception e) {
                log.error("Ошибка закрытия browser в {}: {}", site, e.getMessage(), e);
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
                log.debug("Playwright закрыт для {}", site);
            } catch (Exception e) {
                log.error("Ошибка закрытия Playwright в {}: {}", site, e.getMessage(), e);
            }
        }
    }

    public Browser.NewContextOptions baseContextOptions(Browser browser) {
        String chromeVersion = browser.version();
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/" + chromeVersion + " Safari/537.36";

        return new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                .setUserAgent(userAgent)
                .setLocale("ru-RU")
                .setDeviceScaleFactor(1.0)
                .setIsMobile(false)
                .setHasTouch(false)
                .setTimezoneId("Europe/Moscow")
                .setColorScheme(ColorScheme.LIGHT)
                .setExtraHTTPHeaders(Map.of(
                        "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
                ));
    }

    public void applyProxy(Browser.NewContextOptions options, ProxyCredentials proxy, boolean useProxy) {
        if (!useProxy) return;
        ProxyCredentials usedProxy = proxy != null ? proxy : proxyService.getRandomActiveProxy();
        options.setProxy(new Proxy("http://" + usedProxy.getHost() + ":" + usedProxy.getPort())
                .setUsername(usedProxy.getUsername())
                .setPassword(usedProxy.getPassword()));
    }

    public BrowserContext createBrowserContext(Browser browser, ProxyCredentials proxy, boolean useProxy, SiteName site) {
        return createBrowserContext(browser, proxy, useProxy, site, null, null);
    }

    public BrowserContext createBrowserContext(Browser browser, ProxyCredentials proxy, boolean useProxy,
                                               SiteName site, Path storageStatePath) {
        return createBrowserContext(browser, proxy, useProxy, site, storageStatePath, null);
    }

    public BrowserContext createBrowserContext(Browser browser, ProxyCredentials proxy, boolean useProxy,
                                               SiteName site, Path storageStatePath, String storageStateJson) {
        Browser.NewContextOptions options = buildContextOptions(browser, proxy, useProxy);
        if (storageStatePath != null && Files.exists(storageStatePath)) {
            log.info("SESSION: загружаем storageState в контекст {}", storageStatePath);
            options.setStorageStatePath(storageStatePath);
        } else if (StringUtils.hasText(storageStateJson)) {
            log.info("SESSION: загружаем storageState в контекст из memory");
            options.setStorageState(storageStateJson);
        }

        BrowserContext context = browser.newContext(options);
        applyStealthScripts(context);
        return context;
    }

    public Browser.NewContextOptions buildContextOptions(Browser browser, ProxyCredentials proxy, boolean useProxy) {
        Browser.NewContextOptions options = baseContextOptions(browser);
        applyProxy(options, proxy, useProxy);
        return options;
    }

    @Override
    public void applyStealthScripts(BrowserContext context) {
        context.addInitScript(StealthInitScripts.CONTEXT_SCRIPT);
    }
}
