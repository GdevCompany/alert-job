package by.gdev.alert.job.notification.service.ai.sessions.impl;

import by.gdev.alert.job.notification.model.dto.DecryptedCredential;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.alert.job.notification.service.ai.parser.youdo.YouDoPlaywrightGuards;
import by.gdev.alert.job.notification.service.ai.sessions.AbstractAutoreplySessionVerifier;
import by.gdev.common.model.SiteName;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class YouDoSessionVerifier extends AbstractAutoreplySessionVerifier {

    private static final String[] LOGIN_BUTTON_SELECTORS = {
            "data-test=LoginButton",
            "[data-test='LoginButton']",
            "text=Войти"
    };

    @Value("${parser.autoreply.headless.youdo.com:true}")
    private void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Value("${parser.autoreply.proxy.youdo.com:false}")
    private void setProxy(boolean proxy) {
        this.proxy = proxy;
    }

    @Override
    public SiteName getSiteName() {
        return SiteName.YOUDO;
    }

    @Override
    protected String sessionCookieCheckUrl() {
        return "https://youdo.com";
    }

    @Override
    public StepResult<Void> verifySessionOnPage(Page page, DecryptedCredential creds) {
        try {
            BrowserContext ctx = page.context();
            String checkUrl = sessionCookieCheckUrl();
            boolean hasAuth = contextHasCookie(ctx, checkUrl, "xcuid")
                    || contextHasCookie(ctx, "https://youdo.com", "userEmail");
            if (!hasAuth) {
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Нет auth-cookies YouDo в контексте");
            }
            safeNavigate(page, "https://youdo.com/");
            page.waitForTimeout(2000);
            if (YouDoPlaywrightGuards.isProxyBlockedPage(page)) {
                String details = YouDoPlaywrightGuards.formatBlockDetails(page);
                return StepResult.fail(StepType.SEND_AUTOREPLY, YouDoPlaywrightGuards.YOUDO_PROXY_BLOCKED_CODE,
                        YouDoPlaywrightGuards.proxyBlockedFailMessage(getSiteName(), details));
            }
            for (String selector : LOGIN_BUTTON_SELECTORS) {
                try {
                    Locator btn = page.locator(selector);
                    if (btn.count() > 0 && btn.first().isVisible()) {
                        return StepResult.fail(StepType.SEND_AUTOREPLY, "LoginButton виден при наличии cookies");
                    }
                } catch (Exception ignored) {
                }
            }
            log.info("SESSION-VERIFY: {} -> сессия активна, пользователь: {}", getSiteName(), creds.login());
            return StepResult.ok(StepType.SEND_AUTOREPLY, null);
        } catch (Exception e) {
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Ошибка проверки сессии: " + e.getMessage());
        }
    }
}
