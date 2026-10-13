package by.gdev.alert.job.notification.service.ai.parser.youdo;

import by.gdev.common.service.playwright.captcha.CaptchaService;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureCode;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * YouDo: SmartCaptcha в модалке логина (чекбокс; advanced — 2Captcha token + audio).
 * По смыслу как {@link by.gdev.alert.job.notification.service.ai.flru.FlRuValidateCaptchaSupport} для FL.ru.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouDoSmartCaptchaSupport {

    private static final String[] SMART_CAPTCHA_MARKERS = {
            "iframe[data-testid='checkbox-iframe']",
            "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']",
            "iframe[data-testid='advanced-iframe']",
            "[data-testid='smartCaptcha-container']",
            ".smart-captcha[data-sitekey]"
    };

    private final CaptchaService captchaService;

    @Value("${captcha.yandex.two-captcha-fallback:true}")
    private boolean yandexTwoCaptchaFallback;

    /**
     * Капча на любом шаге логина YouDo: видимая, скрытая (грузится), pending.
     * В т.ч. {@code data-sentry-component="SmartCaptcha"} до появления iframe.
     */
    public boolean isLoginCaptchaGate(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (isSmartCaptchaStage(page)) {
            return true;
        }
        if (captchaService.smartCaptchaTokenLength(page) > 20) {
            return false;
        }
        return isSmartCaptchaShellInLoginModal(page);
    }

    /** Капча блокирует вход (модалка, iframe, калейдоскоп, task из /check). */
    public boolean isSmartCaptchaStage(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        int tokenLen = captchaService.smartCaptchaTokenLength(page);
        // С токеном блокирует только реальный challenge (калейдоскоп / advanced), не «хвост» checkbox-iframe.
        if (tokenLen > 20) {
            return captchaService.isYandexKaleidoscopeChallenge(page)
                    || captchaService.isYandexAdvancedUiVisible(page)
                    || captchaService.isSmartCaptchaOverlayVisible(page);
        }
        if (hasVisibleSmartCaptchaUi(page)) {
            return true;
        }
        if (captchaService.isSmartCaptchaPending(page)) {
            return true;
        }
        if (captchaService.hasCapturedPuzzleTask(page)
                && captchaService.isYandexKaleidoscopeChallenge(page)) {
            return true;
        }
        return false;
    }

    /** Видимый чекбокс / advanced в модалке — капча не снята (только пока нет smart-token). */
    private boolean hasVisibleSmartCaptchaUi(Page page) {
        if (captchaService.smartCaptchaTokenLength(page) > 20) {
            return false;
        }
        if (captchaService.isYandexCheckboxVisible(page)
                || captchaService.isCheckboxIframeChallenge(page)) {
            return true;
        }
        if (captchaService.isYandexAdvancedUiVisible(page)
                && captchaService.isYandexKaleidoscopeChallenge(page)) {
            return true;
        }
        for (String selector : SMART_CAPTCHA_MARKERS) {
            try {
                Locator loc = page.locator(selector).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    if (selector.contains("advanced-iframe") || selector.contains("backend")) {
                        if (captchaService.smartCaptchaTokenLength(page) > 20
                                && !captchaService.isYandexKaleidoscopeChallenge(page)) {
                            continue;
                        }
                    }
                    return true;
                }
            } catch (Exception ignored) {
                // next selector
            }
        }
        return false;
    }

    /** Реальный challenge (пазл / оверлей / чекбокс), а не пустой iframe после токена. */
    private boolean isActiveYouDoCaptchaChallenge(Page page) {
        int tokenLen = captchaService.smartCaptchaTokenLength(page);
        if (tokenLen > 20) {
            return captchaService.isYandexKaleidoscopeChallenge(page)
                    || captchaService.isYandexAdvancedUiVisible(page)
                    || captchaService.isSmartCaptchaOverlayVisible(page);
        }
        if (captchaService.isYandexKaleidoscopeChallenge(page)) {
            return true;
        }
        if (captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.isSmartCaptchaOverlayVisible(page)) {
            return true;
        }
        return captchaService.isCheckboxIframeChallenge(page)
                || captchaService.isYandexCheckboxVisible(page);
    }

    private boolean isSmartCaptchaShellInLoginModal(Page page) {
        try {
            Object found = page.evaluate(
                    """
                            () => {
                              const captchaSel = '[data-sentry-component="SmartCaptcha"], [data-sitekey], .smart-captcha, iframe[src*="smartcaptcha"]';
                              const login = document.querySelector('input[name="login"]');
                              const code = document.querySelector('input[name="code"]');
                              if ((login && login.offsetParent !== null) || (code && code.offsetParent !== null)) {
                                return !!document.querySelector(captchaSel);
                              }
                              const buttons = [...document.querySelectorAll('button')].filter(b => b.offsetParent !== null);
                              const dal = buttons.some(b => (b.textContent || '').includes('Далее'));
                              return dal && !!document.querySelector(captchaSel);
                            }
                            """);
            return Boolean.TRUE.equals(found);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return {@code true} если капчи нет или она успешно пройдена
     */
    public boolean passLoginSmartCaptcha(Page page, String login) {
        if (!isLoginCaptchaGate(page)) {
            return true;
        }
        captchaService.waitForYandexSmartCaptchaWidget(page, 15_000);
        captchaService.revealOffScreenSmartCaptchaHosts(page);
        page.waitForTimeout(500);
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (!isLoginCaptchaGate(page)) {
                return true;
            }
            log.info("YOUDO SmartCaptcha (попытка {}/3), пользователь: {}, url={}",
                    attempt, login, page.url());
            captchaService.logSmartCaptchaState(page, "youdo-login-attempt-" + attempt + "-before");
            if (captchaService.smartCaptchaTokenLength(page) > 20
                    && !hasVisibleSmartCaptchaUi(page)
                    && !isActiveYouDoCaptchaChallenge(page)) {
                log.info("YOUDO SmartCaptcha: smart-token в DOM, видимого challenge нет — пропускаем");
                return true;
            }
            captchaService.waitForYandexSmartCaptchaWidget(page, 12_000);
            captchaService.revealOffScreenSmartCaptchaHosts(page);
            page.waitForTimeout(500);

            boolean progressed = solveCurrentChallenge(page, login, attempt);
            captchaService.dismissSmartCaptchaOverlay(page);
            page.waitForTimeout(800);
            captchaService.logSmartCaptchaState(page, "youdo-login-attempt-" + attempt + "-after");

            if (!isSmartCaptchaStage(page)) {
                captchaService.resumeYandexSmartCaptchaForForm(page);
                captchaService.dismissSmartCaptchaOverlay(page);
                log.info("YOUDO SmartCaptcha снята после попытки {}, пользователь: {}", attempt, login);
                return true;
            }
            if (!progressed) {
                log.warn("YOUDO SmartCaptcha: попытка {} не дала прогресса, пользователь: {}", attempt, login);
            }
        }
        return !isLoginCaptchaGate(page);
    }

    private boolean solveCurrentChallenge(Page page, String login, int attempt) {
        boolean any = false;

        boolean checkboxUi = captchaService.isYandexCheckboxVisible(page)
                || captchaService.isCheckboxIframeChallenge(page);
        boolean advancedUi = captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.hasCapturedPuzzleTask(page);

        if (checkboxUi) {
            log.info("YOUDO SmartCaptcha: сначала клик «Я не робот» (checkbox-iframe), попытка {}, пользователь: {}",
                    attempt, login);
            if (solveCheckbox(page)) {
                return true;
            }
        }

        if (captchaService.smartCaptchaTokenLength(page) > 20 && checkboxUi) {
            log.info("YOUDO SmartCaptcha: токен в DOM, добиваем чекбокс (попытка {}), пользователь: {}",
                    attempt, login);
            if (solveCheckbox(page)) {
                return true;
            }
        }

        // Красный «Я не робот» в модалке: сначала чекбокс; task из /check не значит пазл.
        if (checkboxUi && !advancedUi) {
            log.info("YOUDO SmartCaptcha: этап чекбокс (попытка {}), пользователь: {}", attempt, login);
            any |= solveCheckbox(page);
        }
        if (!isSmartCaptchaStage(page)) {
            return any;
        }

        if (shouldSolveAdvancedViaTwoCaptcha(page) || advancedUi) {
            log.info("YOUDO SmartCaptcha: этап advanced 2Captcha token+audio (попытка {}), networkTask={} kaleidoscope={}",
                    attempt,
                    captchaService.hasCapturedPuzzleTask(page),
                    captchaService.isYandexKaleidoscopeChallenge(page));
            any |= captchaService.solveYandexAdvancedViaTwoCaptcha(page);
        }
        if (!isSmartCaptchaStage(page)) {
            return any;
        }

        if (!advancedUi && checkboxUi) {
            any |= solveCheckbox(page);
        }

        if (!isSmartCaptchaStage(page)) {
            return any;
        }

        if (shouldSolveAdvancedViaTwoCaptcha(page)) {
            any |= captchaService.solveYandexAdvancedViaTwoCaptcha(page);
        } else if (isSmartCaptchaStage(page)) {
            log.info("YOUDO SmartCaptcha: локальный клик виджета (попытка {}), пользователь: {}", attempt, login);
            any |= captchaService.solveYandexSmartCaptcha(page);
            if (!any && yandexTwoCaptchaFallback && captchaService.isTwoCaptchaConfigured()) {
                log.info("YOUDO SmartCaptcha: fallback YandexSmartCaptchaTask (попытка {}), пользователь: {}",
                        attempt, login);
                any |= captchaService.solveYandexSmartCaptchaViaToken(page);
            }
        }
        return any;
    }

    private boolean solveCheckbox(Page page) {
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                && !captchaService.isCheckboxIframeChallenge(page)) {
            return false;
        }
        captchaService.clearLastFailure();
        if (captchaService.clickCheckboxInSmartCaptchaIframe(page)) {
            page.waitForTimeout(1200);
            if (captchaService.smartCaptchaTokenLength(page) > 20) {
                captchaService.resumeYandexSmartCaptchaForForm(page);
                return true;
            }
            if (!isSmartCaptchaStage(page)) {
                return true;
            }
        }
        if (captchaService.solveYandexSmartCaptcha(page)) {
            return true;
        }
        if (yandexTwoCaptchaFallback && captchaService.isTwoCaptchaConfigured()) {
            return captchaService.solveYandexSmartCaptchaViaToken(page);
        }
        captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.YOUDO_KALEIDOSCOPE_FAIL,
                "YouDo SmartCaptcha: не пройден чекбокс", "youdo-checkbox");
        return false;
    }

    /** Advanced: калейдоскоп / overlay / task из /check — решаем token+audio через 2Captcha. */
    public boolean shouldSolveAdvancedViaTwoCaptcha(Page page) {
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.isSmartCaptchaOverlayVisible(page)) {
            return true;
        }
        if (captchaService.isCheckboxIframeChallenge(page)
                || captchaService.isYandexCheckboxVisible(page)) {
            return false;
        }
        return captchaService.hasCapturedPuzzleTask(page)
                && captchaService.isYandexKaleidoscopeChallenge(page);
    }
}
