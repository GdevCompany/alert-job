package by.gdev.alert.job.notification.service.ai.parser.youdo;

import by.gdev.common.service.playwright.captcha.CaptchaService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * После 2Captcha: снять блокировки клика, нажать «Далее» в модалке логина, отправить React-форму email.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouDoPostCaptchaSubmit {

    static final String[] NEXT_BUTTON_SELECTORS = {
            "[role='dialog'] button.NewButton_button__2D_5n:has-text('Далее')",
            "[role='dialog'] button:has-text('Далее')",
            "button.NewButton_button__2D_5n:has-text('Далее')",
            "button:has-text('Далее')"
    };

    private final CaptchaService captchaService;

    private final YouDoLoginSiteBridge siteBridge;

    /**
     * @return {@code true} если клик «Далее» или сайт сам открыл шаг OTP
     */
    public void ensureLoginEmail(Page page, String expectedEmail) {
        siteBridge.ensureLoginEmail(page, expectedEmail);
    }

    public boolean submitEmailAfterSmartToken(Page page, String expectedLoginEmail) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (captchaService.smartCaptchaTokenLength(page) < 20) {
            log.info("YOUDO post-token submit: пропуск — нет smart-token в DOM");
            return false;
        }
        ensureLoginEmail(page, expectedLoginEmail);
        captchaService.prepareYouDoEmailSubmitAfterSmartToken(page);
        siteBridge.commitExternalSmartToken(page);
        ensureLoginEmail(page, expectedLoginEmail);
        page.waitForTimeout(900);

        siteBridge.logNextButtonMarkup(page, "post-token-before-click");
        log.info("YOUDO post-token: диалог до клика probe={}", siteBridge.probeLoginDialog(page));

        if (siteBridge.waitForOtpStepInDialog(page, 10_000)) {
            log.info("YOUDO post-token: сайт сам перешёл на ввод кода (OTP)");
            return true;
        }

        boolean siteEnabledNext = siteBridge.waitForSiteEnabledNext(page, 22_000);
        if (!siteEnabledNext) {
            log.warn("YOUDO post-token: React не разблокировал «Далее» — повтор commit (без снятия спиннера в DOM)");
            siteBridge.commitExternalSmartToken(page);
            ensureLoginEmail(page, expectedLoginEmail);
            page.waitForTimeout(400);
        }

        boolean clicked = clickNextInLoginModal(page);
        page.waitForTimeout(400);
        siteBridge.logNextButtonMarkup(page, "post-token-after-click");
        triggerEmailFormSubmit(page);
        if (!clicked) {
            clicked = jsClickNextInDialog(page);
            if (clicked) {
                page.waitForTimeout(500);
                triggerEmailFormSubmit(page);
            }
        }
        log.info("YOUDO post-token submit: siteEnabledNext={} clickNext={} probeAfter={}",
                siteEnabledNext, clicked, siteBridge.probeLoginDialog(page));
        return clicked || siteBridge.waitForOtpStepInDialog(page, 8_000);
    }

    public void logNextButtonMarkup(Page page, String phase) {
        siteBridge.logNextButtonMarkup(page, phase);
    }

    public void resetNextButtonMarkupBaseline(Page page) {
        siteBridge.resetNextButtonMarkupBaseline(page);
    }

    public void watchNextButtonWhileLoading(Page page, String phasePrefix, long timeoutMs) {
        siteBridge.watchNextButtonWhileLoading(page, phasePrefix, timeoutMs);
    }

    public Locator locateNextButton(Page page) {
        if (page == null || page.isClosed()) {
            return null;
        }
        for (String selector : NEXT_BUTTON_SELECTORS) {
            try {
                Locator loc = page.locator(selector);
                int count = loc.count();
                for (int i = 0; i < count; i++) {
                    Locator candidate = loc.nth(i);
                    if (candidate.isVisible()) {
                        return candidate;
                    }
                }
            } catch (Exception ignored) {
                // next selector
            }
        }
        return null;
    }

    /** Клик «Далее» в модалке (до или после капчи). */
    public boolean clickLoginModalNext(Page page, boolean afterSmartToken) {
        if (afterSmartToken) {
            captchaService.prepareYouDoEmailSubmitAfterSmartToken(page);
            page.waitForTimeout(300);
        }
        boolean clicked = clickNextInLoginModal(page);
        if (!clicked) {
            clicked = jsClickNextInDialog(page);
        }
        return clicked;
    }

    public boolean clickNextInLoginModal(Page page) {
        siteBridge.logNextButtonMarkup(page, "click-next-before");
        Locator next = locateNextButton(page);
        if (next == null) {
            log.info("YOUDO post-token: кнопка «Далее» не найдена (видимая)");
            siteBridge.logNextButtonMarkup(page, "click-next-not-found");
            return false;
        }
        try {
            next.scrollIntoViewIfNeeded();
            try {
                next.click(new Locator.ClickOptions().setTimeout(10_000));
                log.info("YOUDO post-token: клик «Далее» (обычный, как у пользователя)");
                page.waitForTimeout(250);
                siteBridge.logNextButtonMarkup(page, "click-next-after-normal");
                return true;
            } catch (Exception normalClick) {
                log.info("YOUDO post-token: обычный клик не прошёл ({}), пробуем force — часто iframe капчи перекрывает кнопку",
                        normalClick.getMessage());
                next.click(new Locator.ClickOptions().setForce(true).setTimeout(10_000));
                log.info("YOUDO post-token: клик «Далее» (force — только обход перекрытия, не другая кнопка)");
                return true;
            }
        } catch (Exception e) {
            log.warn("YOUDO post-token: клик «Далее» не удался: {}", e.getMessage());
            return false;
        }
    }

    public void triggerEmailFormSubmit(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            Object ok = page.evaluate(
                    """
                            () => {
                              const login = document.querySelector('input[name="login"]');
                              if (!login || login.offsetParent === null) return false;
                              const form = login.closest('form');
                              if (form && typeof form.requestSubmit === 'function') {
                                form.requestSubmit();
                                return true;
                              }
                              login.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
                              login.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
                              return true;
                            }
                            """);
            if (Boolean.TRUE.equals(ok)) {
                log.info("YOUDO post-token: requestSubmit/Enter для формы email");
            }
        } catch (Exception e) {
            log.debug("YOUDO post-token: requestSubmit: {}", e.getMessage());
        }
    }

    private boolean jsClickNextInDialog(Page page) {
        try {
            Object ok = page.evaluate(
                    """
                            () => {
                              const dialog = document.querySelector('[role="dialog"]') || document;
                              const buttons = [...dialog.querySelectorAll('button')]
                                .filter(b => b.offsetParent !== null);
                              for (const b of buttons) {
                                const text = (b.textContent || '').trim();
                                if (!text.includes('Далее')) continue;
                                b.disabled = false;
                                b.removeAttribute('aria-busy');
                                b.click();
                                return true;
                              }
                              return false;
                            }
                            """);
            if (Boolean.TRUE.equals(ok)) {
                log.info("YOUDO post-token: JS click «Далее» в dialog");
            }
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.debug("YOUDO post-token: JS click: {}", e.getMessage());
            return false;
        }
    }

}
