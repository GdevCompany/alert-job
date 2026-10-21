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
        if (!captchaService.isYandexSmartCaptchaReadyForYouDoSubmit(page)) {
            log.info("YOUDO post-token submit: пропуск — виджет не принял капчу (getResponse), tokenAPI={}",
                    captchaService.smartCaptchaApiTokenLength(page));
            return false;
        }
        captchaService.clearCapturedPuzzleTasks(page);
        ensureLoginEmail(page, expectedLoginEmail);
        if (shouldTryBrowserPazl(page)) {
            log.warn("YOUDO post-token: видим пазл — Pazl в сессии браузера");
            captchaService.solveYouDoBrowserSessionKaleidoscope(page);
        }
        captchaService.prepareYouDoEmailSubmitAfterSmartToken(page);
        siteBridge.commitExternalSmartToken(page);
        captchaService.dispatchYandexWidgetSuccess(page, null);
        ensureLoginEmail(page, expectedLoginEmail);

        siteBridge.logNextButtonMarkup(page, "post-token-before-click");
        log.info("YOUDO post-token: диалог до клика probe={}", siteBridge.probeLoginDialog(page));

        if (postSignInEmailApi(page, expectedLoginEmail)) {
            if (siteBridge.waitForOtpStepInDialog(page, 15_000)) {
                log.info("YOUDO post-token: OTP после POST signin/email");
                return true;
            }
        }

        if (siteBridge.waitForOtpStepInDialog(page, 3_000)) {
            log.info("YOUDO post-token: сайт сам перешёл на ввод кода (OTP)");
            return true;
        }

        if (isNextButtonLoading(page)) {
            log.info("YOUDO post-token: «Далее» в loading — resume через React (внешний токен)");
            if (siteBridge.completeEmailSubmitAfterExternalToken(page)) {
                if (siteBridge.waitForOtpStepInDialog(page, 8_000)) {
                    return true;
                }
            }
            if (isNextButtonLoading(page)) {
                log.info("YOUDO post-token: loading — POST signin/email (как браузер при «Далее»)");
                if (postSignInEmailApi(page, expectedLoginEmail)
                        && siteBridge.waitForOtpStepInDialog(page, 15_000)) {
                    return true;
                }
                log.info("YOUDO post-token: loading не снят — recover через «Назад»");
                if (siteBridge.recoverStuckEmailSubmitViaBack(page, expectedLoginEmail)) {
                    return siteBridge.waitForOtpStepInDialog(page, 12_000);
                }
            }
        }

        if (!waitForEmailNextReady(page, 12_000)) {
            log.warn("YOUDO post-token: «Далее» не готова — пробуем сброс loading + React, probe={}",
                    siteBridge.probeLoginDialog(page));
            siteBridge.completeEmailSubmitAfterExternalToken(page);
            if (siteBridge.waitForOtpStepInDialog(page, 10_000)) {
                return true;
            }
            if (isNextButtonLoading(page)) {
                siteBridge.recoverStuckEmailSubmitViaBack(page, expectedLoginEmail);
                if (siteBridge.waitForOtpStepInDialog(page, 12_000)) {
                    return true;
                }
            }
            triggerEmailFormSubmit(page);
            if (siteBridge.waitForOtpStepInDialog(page, 8_000)) {
                return true;
            }
            log.warn("YOUDO post-token: OTP не появился, probe={}", siteBridge.probeLoginDialog(page));
            return false;
        }

        siteBridge.commitExternalSmartToken(page);
        ensureLoginEmail(page, expectedLoginEmail);
        boolean clicked = clickNextInLoginModal(page);
        page.waitForTimeout(600);
        siteBridge.logNextButtonMarkup(page, "post-token-after-click");
        if (!clicked) {
            clicked = siteBridge.clickNextViaReact(page) || jsClickNextInDialog(page);
        }
        if (!siteBridge.waitForOtpStepInDialog(page, 3_000)) {
            triggerEmailFormSubmit(page);
        }
        log.info("YOUDO post-token submit: clickNext={} probeAfter={}",
                clicked, siteBridge.probeLoginDialog(page));
        return clicked || siteBridge.waitForOtpStepInDialog(page, 12_000);
    }

    private boolean shouldTryBrowserPazl(Page page) {
        return captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page);
    }

    /** Ждём снятия спиннера; при внешнем токене не ждём tokenAPI и не крутим Pazl без canvas. */
    private boolean waitForEmailNextReady(Page page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !page.isClosed()) {
            if (siteBridge.waitForOtpStepInDialog(page, 200)) {
                return true;
            }
            int apiLen = captchaService.smartCaptchaApiTokenLength(page);
            boolean loading = isNextButtonLoading(page);
            if (!loading && apiLen > 20) {
                return true;
            }
            if (loading && apiLen > 20) {
                siteBridge.commitExternalSmartToken(page);
            }
            page.waitForTimeout(450);
        }
        return !isNextButtonLoading(page) && captchaService.smartCaptchaApiTokenLength(page) > 20;
    }

    private boolean isNextButtonLoading(Page page) {
        try {
            Object loading = page.evaluate(
                    """
                            () => {
                              const nextBtn = [...document.querySelectorAll('button')]
                                .find((b) => b.offsetParent !== null && (b.textContent || '').includes('Далее'));
                              if (!nextBtn) return false;
                              const cls = String(nextBtn.className || '');
                              const loader = nextBtn.querySelector(
                                '[class*="Loader"], [class*="loader"], [class*="Spinner"], [class*="spinner"]');
                              return nextBtn.disabled || cls.includes('isLoading') || cls.includes('IsLoading') || !!loader;
                            }
                            """);
            return Boolean.TRUE.equals(loading);
        } catch (Exception e) {
            return false;
        }
    }

    public void logNextButtonMarkup(Page page, String phase) {
        siteBridge.logNextButtonMarkup(page, phase);
    }

    public void resetNextButtonMarkupBaseline(Page page) {
        siteBridge.resetNextButtonMarkupBaseline(page);
    }

    public void resetStuckEmailSubmitUi(Page page) {
        siteBridge.resetStuckEmailSubmitUi(page);
    }

    public boolean recoverStuckSubmitViaBack(Page page, String expectedLoginEmail) {
        return siteBridge.recoverStuckEmailSubmitViaBack(page, expectedLoginEmail);
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
            clicked = jsClickNextInDialog(page) || siteBridge.clickNextViaReact(page);
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

    /**
     * Тот же запрос, что React шлёт после капчи (см. captures/login-api.json).
     */
    private boolean postSignInEmailApi(Page page, String expectedLoginEmail) {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            Object raw = page.evaluate(
                    """
                            async (expectedEmail) => {
                              const pickToken = () => {
                                const el = document.querySelector(
                                  'input[name="smart-token"], input[name="smart_token"]');
                                if (el && el.value && el.value.length > 20) return el.value;
                                try {
                                  if (window.smartCaptcha && typeof window.smartCaptcha.getResponse === 'function') {
                                    const r = window.smartCaptcha.getResponse();
                                    if (r && r.length > 20) return r;
                                  }
                                } catch (e) { /* ignore */ }
                                return '';
                              };
                              const token = pickToken();
                              if (!token) return { ok: false, reason: 'no-token' };
                              const loginInput = document.querySelector('input[name="login"]');
                              const login = (expectedEmail && expectedEmail.includes('@'))
                                ? expectedEmail
                                : (loginInput && loginInput.value) || '';
                              if (!login || !login.includes('@')) {
                                return { ok: false, reason: 'no-login', tokenLen: token.length };
                              }
                              const body = {
                                login,
                                email: login,
                                smartToken: token,
                                'smart-token': token,
                              };
                              const res = await fetch('https://youdo.com/api/users/signin/email/', {
                                method: 'POST',
                                credentials: 'include',
                                headers: {
                                  accept: 'application/json',
                                  'content-type': 'application/json',
                                },
                                body: JSON.stringify(body),
                              });
                              let preview = '';
                              try {
                                preview = (await res.text()).slice(0, 400);
                              } catch (e) {
                                preview = '<unreadable>';
                              }
                              return {
                                ok: res.ok,
                                status: res.status,
                                bodyPreview: preview,
                                tokenLen: token.length,
                              };
                            }
                            """,
                    expectedLoginEmail);
            log.info("YOUDO signin/email API → {}", raw);
            if (raw instanceof java.util.Map<?, ?> m) {
                Object preview = m.get("bodyPreview");
                String body = preview != null ? preview.toString() : "";
                boolean isSuccess = body.contains("\"IsSuccess\":true")
                        || body.contains("\"IsSuccess\": true")
                        || body.contains("\"isSuccess\":true");
                if (isSuccess) {
                    return true;
                }
                if (body.contains("IsSuccess") || body.contains("isSuccess")) {
                    log.warn("YOUDO signin/email API: HTTP ok, но IsSuccess=false — капча/токен не приняты");
                    return false;
                }
                Object ok = m.get("ok");
                Object status = m.get("status");
                if (Boolean.TRUE.equals(ok) && status instanceof Number n) {
                    return n.intValue() >= 200 && n.intValue() < 300;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("YOUDO signin/email API failed: {}", e.getMessage());
            return false;
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
