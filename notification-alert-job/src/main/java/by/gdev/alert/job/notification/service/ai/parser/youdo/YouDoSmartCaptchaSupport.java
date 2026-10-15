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
 * По смыслу как {@link by.gdev.alert.job.notification.service.ai.parser.impl.flru.FlRuValidateCaptchaSupport} для FL.ru.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouDoSmartCaptchaSupport {

    public enum CaptchaKind {
        NONE,
        CHECKBOX,
        KALEIDOSCOPE,
        HIDDEN_SHELL
    }

    private static final String[] SMART_CAPTCHA_MARKERS = {
            "iframe[data-testid='checkbox-iframe']",
            "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']",
            "iframe[data-testid='advanced-iframe']",
            "[data-testid='smartCaptcha-container']",
            ".smart-captcha[data-sitekey]"
    };

    private final CaptchaService captchaService;

    private final YouDoModalLayoutFix modalLayoutFix;

    @Value("${captcha.yandex.two-captcha-fallback:true}")
    private boolean yandexTwoCaptchaFallback;

    /** YouDo: сначала 2Captcha для checkbox и advanced; локальный клик — только fallback. */
    @Value("${captcha.youdo.two-captcha-first:true}")
    private boolean youdoTwoCaptchaFirst;

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
            // После kaleidoscope-токена часто остаётся чекбокс «Я не робот» — не считаем капчу снятой.
            return captchaService.isCheckboxIframeChallenge(page)
                    || captchaService.isYandexCheckboxVisible(page);
        }
        return isSmartCaptchaShellPresent(page);
    }

    /** SmartCaptcha в DOM (в т.ч. скрытый div до iframe) на форме логина. */
    public boolean isSmartCaptchaShellPresent(Page page) {
        return isSmartCaptchaShellInLoginModal(page) || isSmartCaptchaShellAnywhere(page);
    }

    public CaptchaKind detectCaptchaKind(Page page) {
        if (page == null || page.isClosed()) {
            return CaptchaKind.NONE;
        }
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.hasCapturedPuzzleTask(page)) {
            return CaptchaKind.KALEIDOSCOPE;
        }
        if (captchaService.isYandexCheckboxVisible(page)
                || captchaService.isCheckboxIframeChallenge(page)) {
            return CaptchaKind.CHECKBOX;
        }
        if (isSmartCaptchaShellPresent(page) || captchaService.isSmartCaptchaPending(page)) {
            return CaptchaKind.HIDDEN_SHELL;
        }
        if (isLoginCaptchaGate(page)) {
            return CaptchaKind.HIDDEN_SHELL;
        }
        return CaptchaKind.NONE;
    }

    /** Капча блокирует вход (модалка, iframe, калейдоскоп, task из /check). */
    public boolean isSmartCaptchaStage(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        int tokenLen = captchaService.smartCaptchaTokenLength(page);
        if (tokenLen > 20) {
            return captchaService.isCheckboxIframeChallenge(page)
                    || captchaService.isYandexCheckboxVisible(page)
                    || captchaService.isYandexKaleidoscopeChallenge(page)
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
        if (captchaService.isYandexCheckboxVisible(page)
                || captchaService.isCheckboxIframeChallenge(page)) {
            return true;
        }
        if (captchaService.smartCaptchaTokenLength(page) > 20) {
            return false;
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
            return captchaService.isCheckboxIframeChallenge(page)
                    || captchaService.isYandexCheckboxVisible(page)
                    || captchaService.isYandexKaleidoscopeChallenge(page)
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

    private boolean isSmartCaptchaShellAnywhere(Page page) {
        try {
            Object found = page.evaluate(
                    """
                            () => !!document.querySelector(
                              '[data-sentry-component="SmartCaptcha"], [data-sitekey].smart-captcha, '
                              + 'iframe[src*="smartcaptcha"]');
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
        modalLayoutFix.apply(page);
        captchaService.waitForYandexSmartCaptchaWidget(page, 15_000);
        captchaService.revealYouDoLoginCaptchaIframe(page);
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
                captchaService.logSmartCaptchaResolutionVerdict(page,
                        "youdo-login-attempt-" + attempt + "-skip-token-in-dom user=" + login);
                return true;
            }
            captchaService.waitForYandexSmartCaptchaWidget(page, 12_000);
            captchaService.revealYouDoLoginCaptchaIframe(page);
            page.waitForTimeout(500);

            logDetectedCaptchaUi(page, login, attempt, "перед-solve");
            boolean progressed = solveCurrentChallenge(page, login, attempt);
            log.info("YOUDO SmartCaptcha: solveCurrentChallenge попытка {} → progressed={}, gate={}, stage={}",
                    attempt, progressed, isLoginCaptchaGate(page), isSmartCaptchaStage(page));
            if (!isLoginCaptchaGate(page)) {
                captchaService.dismissSmartCaptchaOverlay(page);
            }
            page.waitForTimeout(800);
            captchaService.logSmartCaptchaState(page, "youdo-login-attempt-" + attempt + "-after");
            captchaService.logSmartCaptchaResolutionVerdict(page,
                    "youdo-login-attempt-" + attempt + "-after user=" + login);

            if (!isSmartCaptchaStage(page)) {
                page.waitForTimeout(1200);
                if (isLoginCaptchaGate(page)) {
                    log.info("YOUDO SmartCaptcha: после solve снова gate (чекбокс?), попытка {}, пользователь: {}",
                            attempt, login);
                    continue;
                }
                captchaService.resumeYandexSmartCaptchaForForm(page);
                captchaService.dismissSmartCaptchaOverlay(page);
                log.info("YOUDO SmartCaptcha снята после попытки {}, пользователь: {}", attempt, login);
                captchaService.logSmartCaptchaResolutionVerdict(page,
                        "youdo-login-attempt-" + attempt + "-stage-clear user=" + login);
                return true;
            }
            if (!progressed) {
                log.warn("YOUDO SmartCaptcha: попытка {} не дала прогресса, пользователь: {}", attempt, login);
            }
        }
        boolean gateOpen = !isLoginCaptchaGate(page);
        captchaService.logSmartCaptchaResolutionVerdict(page,
                "youdo-login-final user=" + login + " gateOpen=" + gateOpen);
        return gateOpen;
    }

    private void logDetectedCaptchaUi(Page page, String login, int attempt, String phase) {
        log.info(
                "YOUDO SmartCaptcha [{}] попытка {} user={}: tokenLen={} loginGate={} smartStage={} | "
                        + "checkbox={} checkboxIframe={} kaleidoscope={} advancedUi={} overlay={} pending={} "
                        + "networkTask={} shellInModal={} twoCaptchaFirst={}",
                phase,
                attempt,
                login,
                captchaService.smartCaptchaTokenLength(page),
                isLoginCaptchaGate(page),
                isSmartCaptchaStage(page),
                captchaService.isYandexCheckboxVisible(page),
                captchaService.isCheckboxIframeChallenge(page),
                captchaService.isYandexKaleidoscopeChallenge(page),
                captchaService.isYandexAdvancedUiVisible(page),
                captchaService.isSmartCaptchaOverlayVisible(page),
                captchaService.isSmartCaptchaPending(page),
                captchaService.hasCapturedPuzzleTask(page),
                isSmartCaptchaShellInLoginModal(page),
                youdoTwoCaptchaFirst);
    }

    private boolean solveCurrentChallenge(Page page, String login, int attempt) {
        CaptchaKind kind = detectCaptchaKind(page);
        YouDoLoginFsmState.log(log,
                kind == CaptchaKind.KALEIDOSCOPE ? YouDoLoginFsmState.CAPTCHA_KALEIDOSCOPE
                        : kind == CaptchaKind.CHECKBOX ? YouDoLoginFsmState.CAPTCHA_CHECKBOX
                        : YouDoLoginFsmState.CAPTCHA_GUARD,
                login,
                "kind=" + kind + " attempt=" + attempt);

        boolean any = false;

        boolean checkboxUi = captchaService.isYandexCheckboxVisible(page)
                || captchaService.isCheckboxIframeChallenge(page);
        boolean advancedUi = captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.hasCapturedPuzzleTask(page);

        log.info(
                "YOUDO SmartCaptcha → ветвление (попытка {}): kind={} checkboxUi={} advancedUi={} shouldAdvanced2Captcha={}",
                attempt, kind, checkboxUi, advancedUi, shouldSolveAdvancedViaTwoCaptcha(page));

        if (youdoTwoCaptchaFirst && captchaService.isTwoCaptchaConfigured()) {
            YouDoLoginFsmState.log(log, YouDoLoginFsmState.CAPTCHA_TWO_CAPTCHA, login,
                    "только 2Captcha, attempt=" + attempt);
            boolean twoCaptchaAll = captchaService.solveYandexForYouDoViaTwoCaptcha(page);
            log.info("YOUDO SmartCaptcha → solveYandexForYouDoViaTwoCaptcha → {}", twoCaptchaAll ? "ok" : "не снято");
            any |= twoCaptchaAll;
            if (!isSmartCaptchaStage(page) && !isLoginCaptchaGate(page)) {
                log.info("YOUDO SmartCaptcha → после 2Captcha gate снят, выход");
                return any;
            }
            if (advancedUi || kind == CaptchaKind.KALEIDOSCOPE) {
                boolean adv = captchaService.solveYandexAdvancedViaTwoCaptcha(page);
                log.info("YOUDO SmartCaptcha → solveYandexAdvancedViaTwoCaptcha → {}", adv ? "ok" : "не снято");
                any |= adv;
            }
            if (!isSmartCaptchaStage(page) && !isLoginCaptchaGate(page)) {
                return any;
            }
            log.warn("YOUDO SmartCaptcha: 2Captcha не снял капчу (без локального клика), attempt={}", attempt);
            return any;
        }
        if (youdoTwoCaptchaFirst) {
            log.warn("YOUDO SmartCaptcha: twoCaptchaFirst=true, но 2Captcha не настроен");
            return false;
        }

        if (checkboxUi && !youdoTwoCaptchaFirst) {
            log.info("YOUDO SmartCaptcha: сначала клик «Я не робот» (checkbox-iframe), попытка {}, пользователь: {}",
                    attempt, login);
            if (solveCheckbox(page, login, attempt, "early-checkbox-click")) {
                return true;
            }
        }

        if (captchaService.smartCaptchaTokenLength(page) > 20 && checkboxUi) {
            log.info("YOUDO SmartCaptcha: токен в DOM, добиваем чекбокс (попытка {}), пользователь: {}",
                    attempt, login);
            if (solveCheckbox(page, login, attempt, "token-plus-checkbox")) {
                return true;
            }
        }

        // Красный «Я не робот» в модалке: сначала чекбокс; task из /check не значит пазл.
        if (checkboxUi && !advancedUi) {
            log.info("YOUDO SmartCaptcha: этап чекбокс (попытка {}), пользователь: {}", attempt, login);
            any |= solveCheckbox(page, login, attempt, "checkbox-only");
        }
        if (!isSmartCaptchaStage(page)) {
            log.info("YOUDO SmartCaptcha → после чекбокса smartStage=false");
            return any;
        }

        if (shouldSolveAdvancedViaTwoCaptcha(page) || advancedUi) {
            log.info("YOUDO SmartCaptcha: этап advanced 2Captcha token+audio (попытка {}), networkTask={} kaleidoscope={}",
                    attempt,
                    captchaService.hasCapturedPuzzleTask(page),
                    captchaService.isYandexKaleidoscopeChallenge(page));
            boolean advancedOk = captchaService.solveYandexAdvancedViaTwoCaptcha(page);
            log.info("YOUDO SmartCaptcha → solveYandexAdvancedViaTwoCaptcha → {}", advancedOk ? "ok" : "не снято");
            any |= advancedOk;
        }
        if (!isSmartCaptchaStage(page)) {
            log.info("YOUDO SmartCaptcha → после advanced 2Captcha smartStage=false");
            return any;
        }

        if (!advancedUi && checkboxUi) {
            log.info("YOUDO SmartCaptcha → повтор чекбокса после advanced (попытка {})", attempt);
            any |= solveCheckbox(page, login, attempt, "checkbox-after-advanced");
        }

        if (!isSmartCaptchaStage(page)) {
            return any;
        }

        if (shouldSolveAdvancedViaTwoCaptcha(page)) {
            log.info("YOUDO SmartCaptcha → повтор advanced 2Captcha (попытка {})", attempt);
            boolean retryAdvanced = captchaService.solveYandexAdvancedViaTwoCaptcha(page);
            log.info("YOUDO SmartCaptcha → повтор solveYandexAdvancedViaTwoCaptcha → {}",
                    retryAdvanced ? "ok" : "не снято");
            any |= retryAdvanced;
        } else if (isSmartCaptchaStage(page)) {
            log.info("YOUDO SmartCaptcha: локальный клик виджета (попытка {}), пользователь: {}", attempt, login);
            boolean localWidget = captchaService.solveYandexSmartCaptcha(page);
            log.info("YOUDO SmartCaptcha → solveYandexSmartCaptcha (локально) → {}",
                    localWidget ? "ok" : "не снято");
            any |= localWidget;
            if (!any && yandexTwoCaptchaFallback && captchaService.isTwoCaptchaConfigured()) {
                log.info("YOUDO SmartCaptcha: fallback YandexSmartCaptchaTask (попытка {}), пользователь: {}",
                        attempt, login);
                boolean tokenFallback = captchaService.solveYandexSmartCaptchaViaToken(page);
                log.info("YOUDO SmartCaptcha → solveYandexSmartCaptchaViaToken → {}",
                        tokenFallback ? "ok" : "не снято");
                any |= tokenFallback;
            }
        }
        logDetectedCaptchaUi(page, login, attempt, "после-solve");
        return any;
    }

    private boolean solveCheckbox(Page page, String login, int attempt, String reason) {
        log.info("YOUDO SmartCaptcha → checkbox [{}] попытка {} user={}", reason, attempt, login);
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                && !captchaService.isCheckboxIframeChallenge(page)) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: пропуск (калейдоскоп без checkbox-iframe)", reason);
            return false;
        }
        captchaService.clearLastFailure();
        if (youdoTwoCaptchaFirst && captchaService.isTwoCaptchaConfigured()) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: 2Captcha для checkbox", reason);
            if (captchaService.solveYandexForYouDoViaTwoCaptcha(page)) {
                page.waitForTimeout(1200);
                if (!isSmartCaptchaStage(page) || captchaService.smartCaptchaTokenLength(page) > 20) {
                    log.info("YOUDO SmartCaptcha → checkbox [{}]: 2Captcha ok, resumeYandexSmartCaptchaForForm",
                            reason);
                    captchaService.resumeYandexSmartCaptchaForForm(page);
                    return true;
                }
            }
            log.info("YOUDO SmartCaptcha → checkbox [{}]: 2Captcha не завершил checkbox", reason);
        }
        log.info("YOUDO SmartCaptcha → checkbox [{}]: клик в checkbox-iframe", reason);
        if (captchaService.clickCheckboxInSmartCaptchaIframe(page)) {
            page.waitForTimeout(1200);
            if (captchaService.smartCaptchaTokenLength(page) > 20) {
                log.info("YOUDO SmartCaptcha → checkbox [{}]: клик ok, tokenLen>20, resume формы", reason);
                captchaService.resumeYandexSmartCaptchaForForm(page);
                return true;
            }
            if (!isSmartCaptchaStage(page)) {
                log.info("YOUDO SmartCaptcha → checkbox [{}]: клик ok, smartStage=false", reason);
                return true;
            }
            log.info("YOUDO SmartCaptcha → checkbox [{}]: клик выполнен, капча ещё на экране", reason);
        } else {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: клик в iframe не удался", reason);
        }
        log.info("YOUDO SmartCaptcha → checkbox [{}]: solveYandexSmartCaptcha (локально)", reason);
        if (captchaService.solveYandexSmartCaptcha(page)) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: локальный виджет ok", reason);
            return true;
        }
        if (yandexTwoCaptchaFallback && captchaService.isTwoCaptchaConfigured()) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: fallback token 2Captcha", reason);
            boolean tokenOk = captchaService.solveYandexSmartCaptchaViaToken(page);
            log.info("YOUDO SmartCaptcha → checkbox [{}]: solveYandexSmartCaptchaViaToken → {}",
                    reason, tokenOk ? "ok" : "не снято");
            return tokenOk;
        }
        captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.YOUDO_KALEIDOSCOPE_FAIL,
                "YouDo SmartCaptcha: не пройден чекбокс", "youdo-checkbox");
        log.warn("YOUDO SmartCaptcha → checkbox [{}]: все способы исчерпаны", reason);
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
