package by.gdev.alert.job.notification.service.ai.sessions;

import by.gdev.alert.job.notification.model.dto.AiNotificationPayload;
import by.gdev.alert.job.notification.model.dto.DecryptedCredential;
import by.gdev.alert.job.notification.service.ai.proxy.AssignedProxyService;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.common.model.SiteName;
import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.service.playwright.manager.BrowserLaunchOptions;
import by.gdev.common.service.playwright.manager.PlaywrightBrowserManager;
import by.gdev.common.service.playwright.manager.PlaywrightManagerResolver;
import by.gdev.common.service.playwright.manager.impl.PlaywrightCamoufoxManager;
import by.gdev.common.service.playwright.sessions.storage.SessionStorage;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.List;

@Slf4j
public abstract class AbstractAutoreplySessionVerifier implements AutoreplySessionVerifier {

    protected boolean headless = true;
    protected boolean proxy;

    @Autowired
    protected SessionStorage sessionStorage;

    @Autowired
    protected PlaywrightManagerResolver managerResolver;

    @Autowired
    protected AssignedProxyService assignedProxyService;

    protected abstract String sessionCookieCheckUrl();

    @Override
    public StepResult<Void> verifyStoredSession(String userUuid, DecryptedCredential creds,
                                                AiNotificationPayload payload) {
        SiteName site = getSiteName();
        String siteName = site.name();
        String login = creds.login();

        if (!sessionStorage.hasSession(userUuid, siteName, login)) {
            return StepResult.ok(StepType.SEND_AUTOREPLY, null);
        }

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;
        PlaywrightBrowserManager manager = managerResolver.resolve(site);
        BrowserLaunchOptions options = buildLaunchOptions(payload);
        boolean camoufox = manager instanceof PlaywrightCamoufoxManager;

        try {
            playwright = manager.createPlaywright();
            browser = manager.createBrowser(playwright, options, site);

            Path chromiumPath = null;
            String chromiumJson = null;
            if (!camoufox) {
                chromiumPath = sessionStorage.storageStatePath(userUuid, siteName, login).orElse(null);
                if (chromiumPath == null) {
                    chromiumJson = sessionStorage.storageStateJson(userUuid, siteName, login).orElse(null);
                }
            }
            context = manager.createBrowserContext(browser, options, site, chromiumPath, chromiumJson);

            if (camoufox) {
                sessionStorage.restoreCookies(context, userUuid, siteName, login);
            }

            page = context.newPage();

            if (camoufox) {
                sessionStorage.restoreOrigins(page, userUuid, siteName, login);
            }

            sessionStorage.logContextCookies(context, sessionCookieCheckUrl(), "after-restore");

            StepResult<Void> verified = verifySessionOnPage(page, creds);
            if (!verified.failed()) {
                sessionStorage.save(context, userUuid, siteName, login);
                log.info("SESSION-VERIFY: OK {}/{} user={}", siteName, login, userUuid);
                return verified;
            }

            sessionStorage.delete(userUuid, siteName, login);
            log.warn("SESSION-VERIFY: DELETED {}/{} user={} — {}", siteName, login, userUuid,
                    verified.getErrorMessage());
            return verified;

        } catch (Exception e) {
            log.error("SESSION-VERIFY: ERROR {}/{} user={}: {}", siteName, login, userUuid, e.getMessage());
            return StepResult.fail(StepType.SEND_AUTOREPLY, e.getMessage());
        } finally {
            manager.closeResources(page, context, browser, playwright, site);
        }
    }

    protected BrowserLaunchOptions buildLaunchOptions(AiNotificationPayload payload) {
        String userEmail = payload.getUser() != null ? payload.getUser().getEmail() : null;
        if (!proxy) {
            return new BrowserLaunchOptions(null, headless, false, userEmail);
        }
        String userUuid = payload.getUser().getUuid();
        Long moduleId = payload.getModule() != null ? payload.getModule().getId() : null;
        ProxyCredentials proxyCred = assignedProxyService.getProxyForUserAndModule(userUuid, moduleId);
        if (proxyCred == null) {
            proxyCred = assignedProxyService.getProxyForUserAndSite(userUuid, getSiteName());
        }
        if (proxyCred == null) {
            proxyCred = assignedProxyService.pickWorkingProxyExcluding(getSiteName(), null);
            log.info("SESSION-VERIFY: {} -> нет закреплённого прокси, взят рабочий из пула: {}", getSiteName(),
                    proxyCred != null ? proxyCred.getHost() + ":" + proxyCred.getPort() : "нет проверенных");
        }
        return new BrowserLaunchOptions(proxyCred, headless, true, userEmail);
    }

    protected boolean contextHasCookie(BrowserContext ctx, String url, String name) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            List<Cookie> cookies = ctx.cookies(url);
            return cookies.stream().anyMatch(c -> name.equals(c.name));
        } catch (Exception e) {
            return false;
        }
    }

    protected boolean contextHasCookieValue(BrowserContext ctx, String url, String name, String expectedValue) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            List<Cookie> cookies = ctx.cookies(url);
            return cookies.stream()
                    .anyMatch(c -> name.equals(c.name) && expectedValue.equals(c.value));
        } catch (Exception e) {
            return false;
        }
    }

    protected void safeNavigate(Page page, String url) {
        for (int i = 1; i <= 5; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                return;
            } catch (PlaywrightException e) {
                log.warn("SESSION-VERIFY: навигация не удалась (попытка {}): {}", i, e.getMessage());
                page.waitForTimeout(1500);
            }
        }
        throw new RuntimeException("Не удалось открыть страницу после 5 попыток: " + url);
    }

    protected boolean waitOrFail(Page page, String selector, int timeoutMs, String step) {
        try {
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            log.warn("SESSION-VERIFY: timeout step '{}' selector '{}'", step, selector);
            return false;
        }
    }
}
