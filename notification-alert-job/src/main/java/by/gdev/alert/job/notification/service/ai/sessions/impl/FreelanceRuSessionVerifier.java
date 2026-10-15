package by.gdev.alert.job.notification.service.ai.sessions.impl;

import by.gdev.alert.job.notification.model.dto.DecryptedCredential;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.alert.job.notification.service.ai.sessions.AbstractAutoreplySessionVerifier;
import by.gdev.common.model.SiteName;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FreelanceRuSessionVerifier extends AbstractAutoreplySessionVerifier {

    @Value("${parser.autoreply.headless.freelance.ru:true}")
    private void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Value("${parser.autoreply.proxy.freelance.ru:false}")
    private void setProxy(boolean proxy) {
        this.proxy = proxy;
    }

    @Override
    public SiteName getSiteName() {
        return SiteName.FREELANCERU;
    }

    @Override
    protected String sessionCookieCheckUrl() {
        return "https://freelance.ru";
    }

    @Override
    public StepResult<Void> verifySessionOnPage(Page page, DecryptedCredential creds) {
        try {
            safeNavigate(page, "https://freelance.ru/auth/login");
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(1500);
            if (!page.url().contains("/auth/login")) {
                if (isLoggedInOnSite(page)) {
                    log.info("SESSION-VERIFY: {} -> сессия активна (редирект с login), пользователь: {}",
                            getSiteName(), creds.login());
                    return StepResult.ok(StepType.SEND_AUTOREPLY, null);
                }
                log.warn("SESSION-VERIFY: {} -> редирект с login, но в шапке «Вход» — гостевая/просроченная сессия, пользователь: {}",
                        getSiteName(), creds.login());
                return StepResult.fail(StepType.SEND_AUTOREPLY,
                        "Сессия не авторизована (гость), нужен вход по логину и паролю");
            }
            if (waitOrFail(page, "input[placeholder='логин или email']", 3000, "Поле логина")) {
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Форма логина на /auth/login");
            }
            // Редиректа не было, но и форму логина определить не удалось — сессию не подтверждаем
            return StepResult.fail(StepType.SEND_AUTOREPLY,
                    "Остались на /auth/login, состояние сессии не подтверждено");
        } catch (Exception e) {
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Ошибка проверки сессии: " + e.getMessage());
        }
    }

    /** Редирект с /auth/login бывает и у анонима — проверяем UI на главной. */
    private boolean isLoggedInOnSite(Page page) {
        safeNavigate(page, "https://freelance.ru/");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.waitForTimeout(800);
        try {
            // Анонимы часто имеют plrs_anon; у авторизованных обычно есть sid
            boolean hasSid = page.context().cookies("https://freelance.ru").stream()
                    .anyMatch(c -> "sid".equals(c.name) && c.value != null && !c.value.isBlank());
            if (hasSid) {
                return true;
            }
            Locator loginLink = page.locator("a[href='/auth/login']");
            if (loginLink.count() > 0 && loginLink.first().isVisible()) {
                return false;
            }
            Locator registerLink = page.locator("a[href='/auth/register']");
            if (registerLink.count() > 0 && registerLink.first().isVisible()) {
                return false;
            }
            return true;
        } catch (Exception e) {
            log.debug("SESSION-VERIFY: {} -> не удалось проверить шапку: {}", getSiteName(), e.getMessage());
            return false;
        }
    }
}
