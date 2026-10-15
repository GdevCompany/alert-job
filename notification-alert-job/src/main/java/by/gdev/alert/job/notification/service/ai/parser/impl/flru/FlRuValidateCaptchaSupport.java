package by.gdev.alert.job.notification.service.ai.parser.impl.flru;

import by.gdev.common.service.playwright.captcha.CaptchaService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * FL.ru /validate-captcha («подозрительная активность») может появиться после навигации,
 * при восстановленной сессии, после OTP и на любом другом шаге.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlRuValidateCaptchaSupport {

    public static final String VALIDATE_CAPTCHA_PATH = "/validate-captcha";

    private static final String[] VALIDATE_CAPTCHA_MARKERS = {
            "text=подозрительная активность",
            "text=подтвердите, что вы не робот",
            "#label:has-text('Я человек')"
    };

    private final CaptchaService captchaService;
    private final FlRuValidateCaptchaSolver validateCaptchaSolver;

    public boolean isValidateCaptchaPage(Page page) {
        if (page == null) {
            return false;
        }
        try {
            String url = page.url().toLowerCase(Locale.ROOT);
            if (url.contains(VALIDATE_CAPTCHA_PATH)) {
                return true;
            }
            for (String selector : VALIDATE_CAPTCHA_MARKERS) {
                if (page.locator(selector).count() > 0) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("FLRU validate-captcha: проверка страницы: {}", e.getMessage());
        }
        return false;
    }

    /**
     * @return {@code true} если страницы нет или капча успешно пройдена
     */
    public boolean passValidateCaptcha(Page page, String login) {
        if (!isValidateCaptchaPage(page)) {
            return true;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (!isValidateCaptchaPage(page)) {
                return true;
            }
            log.info("FLRU validate-captcha / IP-блок (попытка {}), пользователь: {}", attempt, login);
            validateCaptchaSolver.logValidateCaptchaState(page, "gate-attempt-" + attempt + "-before");
            if (!validateCaptchaSolver.solveFlRuValidateCaptcha(page)) {
                validateCaptchaSolver.logValidateCaptchaState(page, "gate-attempt-" + attempt + "-failed");
                return false;
            }
            captchaService.dismissSmartCaptchaOverlay(page);
            if (isValidateCaptchaPage(page)) {
                submitFlRuLoginForm(page, login);
                clickValidateCaptchaContinue(page, login);
            }
            waitUntilLeftValidateCaptcha(page, 15_000);
            page.waitForTimeout(800);
            validateCaptchaSolver.logValidateCaptchaState(page, "gate-attempt-" + attempt + "-after-wait");
            if (!isValidateCaptchaPage(page)) {
                log.info("FLRU validate-captcha снята после попытки {}, url={}, пользователь: {}",
                        attempt, page.url(), login);
                return true;
            }
        }
        if (isValidateCaptchaPage(page)) {
            validateCaptchaSolver.recordFailureForValidateStuck(page);
            return false;
        }
        return true;
    }

    private void clickValidateCaptchaContinue(Page page, String login) {
        String[] selectors = {
                "button[type='submit']",
                "input[type='submit']",
                "button:has-text('Продолжить')",
                "button:has-text('Отправить')",
                "a.btn:has-text('Продолжить')"
        };
        for (String selector : selectors) {
            Locator loc = page.locator(selector);
            if (loc.count() == 0) {
                continue;
            }
            try {
                if (loc.first().isVisible() && !loc.first().isDisabled()) {
                    loc.first().click(new Locator.ClickOptions().setTimeout(5000));
                    log.info("FLRU validate-captcha: нажато «{}», пользователь: {}", selector, login);
                    return;
                }
            } catch (Exception e) {
                log.debug("FLRU validate-captcha клик '{}': {}", selector, e.getMessage());
            }
        }
    }

    private void waitUntilLeftValidateCaptcha(Page page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isValidateCaptchaPage(page)) {
                return;
            }
            page.waitForTimeout(400);
        }
        try {
            page.waitForURL(url -> url != null && !url.contains(VALIDATE_CAPTCHA_PATH),
                    new Page.WaitForURLOptions().setTimeout(3000));
        } catch (Exception ignored) {
            // проверка isValidateCaptchaPage ниже по циклу passValidateCaptcha
        }
    }

    private boolean submitFlRuLoginForm(Page page, String login) {
        try {
            Object submitted = page.evaluate(
                    """
                            () => {
                              const form = document.querySelector('form');
                              if (!form) return false;
                              const btn = document.querySelector('#submit-button, button[type="submit"]');
                              if (typeof form.requestSubmit === 'function') {
                                if (btn) form.requestSubmit(btn);
                                else form.requestSubmit();
                                return true;
                              }
                              form.submit();
                              return true;
                            }
                            """);
            if (Boolean.TRUE.equals(submitted)) {
                log.info("FLRU validate-captcha: форма отправлена (requestSubmit/submit), пользователь: {}", login);
                return true;
            }
        } catch (Exception e) {
            log.warn("FLRU validate-captcha: submit формы не удался: {}", e.getMessage());
        }
        return false;
    }
}
