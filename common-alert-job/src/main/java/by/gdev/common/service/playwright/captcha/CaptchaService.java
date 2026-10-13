package by.gdev.common.service.playwright.captcha;

import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureCode;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureInfo;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Cookie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component("captchaServiceCommon")
@Slf4j
@RequiredArgsConstructor
public class CaptchaService {

    private static final String WEBLANCER_COOKIE_URL = "https://www.weblancer.net";
    private static final Pattern SITEKEY_QUERY = Pattern.compile("[?&]sitekey=([^&]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Advanced SmartCaptcha UI: слайдер-пазл (kaleidoscope), модалка YouDo/ServicePipe и т.п.
     * Часто в DOM без обёртки {@code SmartCaptcha-Overlay_visible}.
     */
    private static final String YANDEX_ADVANCED_UI_SELECTOR =
            ".AdvancedCaptcha_kaleidoscope, "
                    + ".AdvancedCaptcha-KaleidoscopeCanvas, "
                    + ".AdvancedCaptcha_audio, "
                    + ".Captcha-ModalContent, "
                    + ".CaptchaSlider, "
                    + "#captcha-slider, "
                    + "[aria-labelledby='captcha-form-label'], "
                    + "[data-testid='slider'], "
                    + "[data-testid='play'], "
                    + "button[data-testid='challenge-type'], "
                    + "text=Перемещайте слайдер";

    /** Full-screen advanced host (off-screen @ −10000) — не цель для bbox-клика «Я не робот». */
    private static final double SMART_CAPTCHA_MAX_IFRAME_CLICK_AREA = 520_000.0;

    private static final String[] SMART_CAPTCHA_CLICK_IFRAME_SELECTORS = {
            "iframe[data-testid='checkbox-iframe']",
            "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']",
            "iframe[src*='smartcaptcha.yandexcloud.net']",
            "iframe[data-testid='advanced-iframe']",
            ".smart-captcha iframe[src*='smartcaptcha']",
            "iframe[src*='captcha.yandex']",
            "iframe[src*='smartcaptcha.cloud.yandex.ru']"
    };


    private final TwoCaptchaClient twoCaptchaClient;
    private final YandexKaleidoscopeSolver kaleidoscopeSolver;
    private final YandexSmartCaptchaAudioSolver yandexAudioSolver;

    @Value("${captcha.cloudflare.enabled:true}")
    private boolean cloudflareEnabled;

    @Value("${captcha.cloudflare.timeout-ms:45000}")
    private long cloudflareTimeoutMs;

    @Value("${captcha.yandex.two-captcha-fallback:true}")
    private boolean yandexTwoCaptchaFallback;

    @Value("${captcha.yandex.two-captcha-first:false}")
    private boolean yandexTwoCaptchaFirst;

    /** PazlCaptchaTask (слайдер) — по умолчанию выкл., advanced решаем токеном + audio. */
    @Value("${captcha.yandex.pazl-enabled:false}")
    private boolean yandexPazlEnabled;

    @Value("${captcha.yandex.audio-enabled:true}")
    private boolean yandexAudioEnabled;

    private static final ThreadLocal<CaptchaFailureInfo> LAST_FAILURE = new ThreadLocal<>();
    private static final ThreadLocal<ProxyCredentials> CURRENT_PROXY = new ThreadLocal<>();

    /** Прокси текущего Playwright-запроса (в 2Captcha не передаётся). */
    public void setCurrentProxy(ProxyCredentials proxy) {
        if (proxy == null) {
            CURRENT_PROXY.remove();
        } else {
            CURRENT_PROXY.set(proxy);
        }
    }

    public void clearCurrentProxy() {
        CURRENT_PROXY.remove();
    }

    /** Последняя ошибка капчи в этом потоке (для ответа API / StepResult). */
    public Optional<CaptchaFailureInfo> getLastFailure() {
        return Optional.ofNullable(LAST_FAILURE.get());
    }

    public void clearLastFailure() {
        LAST_FAILURE.remove();
    }

    /** Остались на /validate-captcha после попыток (редирект не произошёл). */
    public void recordFailureForValidateStuck(Page page) {
        recordFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_STUCK,
                "FL.ru /validate-captcha: страница блокировки не снята после капчи",
                "ensure-stuck-on-page");
    }

    private void recordFailure(Page page, CaptchaFailureCode code, String userMessage, String phase) {
        String url = null;
        try {
            url = page != null ? page.url() : null;
        } catch (Exception ignored) {
            // page closed
        }
        LAST_FAILURE.set(new CaptchaFailureInfo(code, userMessage, phase, url));
        log.warn("Captcha failure: code={} phase={} url={} — {}", code, phase, url, userMessage);
    }

    private static final String[] YANDEX_CHECKBOX_CLICK_SELECTORS = {
            "input#js-button.CheckboxCaptcha-Button",
            "input#js-button",
            ".CheckboxCaptcha-Button",
            ".CheckboxCaptcha-Anchor",
            "[data-testid='checkbox-captcha'] .CheckboxCaptcha-Anchor",
            "#checkbox-label",
            "[role='checkbox']"
    };

    /** Frame с «Я не робот» (checkbox.ru / backend), не advanced-калейдоскоп. */
    private Frame findYandexCheckboxCaptchaFrame(Page page) {
        Frame backendFallback = null;
        for (Frame f : page.frames()) {
            String url = f.url();
            if (url == null || url.contains("hcaptcha.com")) {
                continue;
            }
            if (!url.contains("smartcaptcha") && !url.contains("captcha.yandex")) {
                continue;
            }
            if (url.contains("/advanced")) {
                continue;
            }
            if (!hasYandexCheckboxControlInFrame(f)) {
                continue;
            }
            if (url.contains("/checkbox")) {
                return f;
            }
            if (url.contains("/backend")) {
                backendFallback = f;
            }
        }
        return backendFallback;
    }

    private static boolean hasYandexCheckboxControlInFrame(Frame frame) {
        try {
            Locator loc = frame.locator(
                    "input#js-button, .CheckboxCaptcha-Button, .CheckboxCaptcha-Anchor, [data-testid='checkbox-captcha']");
            return loc.count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Frame findYandexCaptchaFrame(Page page) {
        Frame checkbox = findYandexCheckboxCaptchaFrame(page);
        if (checkbox != null) {
            return checkbox;
        }
        if (isYandexKaleidoscopeChallenge(page)) {
            for (Frame f : page.frames()) {
                String url = f.url();
                if (url != null && url.contains("/advanced") && url.contains("smartcaptcha")) {
                    return f;
                }
            }
        }
        for (Frame f : page.frames()) {
            String url = f.url();
            if (url == null || url.isBlank()) {
                continue;
            }
            if (url.contains("hcaptcha.com")) {
                continue;
            }
            if (url.contains("smartcaptcha.yandexcloud.net")
                    || url.contains("smartcaptcha.cloud.yandex.ru")
                    || url.contains("captcha.yandex")) {
                return f;
            }
            if (url.contains("yandex") && (url.contains("/checkbox") || url.contains("/advanced"))) {
                return f;
            }
        }
        return null;
    }

    /** Ждёт появления виджета SmartCaptcha (логин FL.ru, /validate-captcha и т.д.). */
    public void waitForYandexSmartCaptchaWidget(Page page, long timeoutMs) {
        try {
            page.waitForSelector(
                    "iframe[src*='smartcaptcha.yandexcloud.net'], iframe[data-testid='checkbox-iframe'], "
                            + "iframe[data-testid='advanced-iframe'], .smart-captcha, [data-sitekey]",
                    new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
            log.info("SmartCaptcha DBG [wait-widget] селектор виджета появился за ≤{} ms", timeoutMs);
        } catch (Exception e) {
            log.warn("SmartCaptcha DBG [wait-widget] виджет не дождались за {} ms: {}", timeoutMs, e.getMessage());
        }
    }

    /** Снимок DOM SmartCaptcha на форме логина / validate-captcha. */
    public void logSmartCaptchaState(Page page, String phase) {
        try {
            log.info("SmartCaptcha DBG [{}] url={}", phase, page.url());
            Frame yandexFrame = findYandexCaptchaFrame(page);
            log.info("SmartCaptcha DBG [{}] findYandexCaptchaFrame={}", phase,
                    yandexFrame != null ? yandexFrame.url() : "null");
            log.info("SmartCaptcha DBG [{}] advanced={} overlayVisible={} smartToken={}",
                    phase, isYandexAdvancedChallenge(page), isSmartCaptchaOverlayVisible(page),
                    isSmartTokenPresentOnPage(page));
            String[] iframeSelectors = {
                    "iframe[data-testid='checkbox-iframe']",
                    "iframe[data-testid='advanced-iframe']",
                    "iframe[src*='smartcaptcha.yandexcloud.net']",
                    ".smart-captcha iframe",
                    "iframe[src*='captcha.yandex']"
            };
            for (String sel : iframeSelectors) {
                Locator loc = page.locator(sel);
                int n = loc.count();
                if (n == 0) {
                    continue;
                }
                for (int i = 0; i < Math.min(n, 3); i++) {
                    Locator one = loc.nth(i);
                    boolean visible = false;
                    BoundingBox box = null;
                    String src = null;
                    try {
                        visible = one.isVisible();
                        box = one.boundingBox();
                        src = one.getAttribute("src");
                    } catch (Exception ignored) {
                        // next field
                    }
                    log.info("SmartCaptcha DBG [{}] iframe sel={} idx={} visible={} bbox={} src={}",
                            phase, sel, i, visible,
                            box == null ? "null"
                                    : String.format("%.0fx%.0f@(%.0f,%.0f)", box.width, box.height, box.x, box.y),
                            truncateForLog(src, 120));
                }
            }
            StringBuilder frameUrls = new StringBuilder();
            for (Frame f : page.frames()) {
                String u = f.url();
                if (u != null && !u.isBlank() && !"about:blank".equals(u)) {
                    if (frameUrls.length() > 0) {
                        frameUrls.append(" | ");
                    }
                    frameUrls.append(truncateForLog(u, 80));
                }
            }
            log.info("SmartCaptcha DBG [{}] allFrameUrls={}", phase, frameUrls);
            Object dom = page.evaluate(
                    """
                            () => {
                              const sc = document.querySelector('.smart-captcha, [data-sitekey]');
                              const style = sc && window.getComputedStyle(sc);
                              return JSON.stringify({
                                smartCaptchaDiv: !!sc,
                                sitekey: sc && sc.getAttribute('data-sitekey'),
                                callback: sc && sc.getAttribute('data-callback'),
                                iframeCount: document.querySelectorAll('iframe').length,
                                offsetHeight: sc && sc.offsetHeight,
                                display: style && style.display,
                                visibility: style && style.visibility
                              });
                            }
                            """);
            log.info("SmartCaptcha DBG [{}] dom={}", phase, dom);
        } catch (Exception e) {
            log.warn("SmartCaptcha DBG [{}] snapshot error: {}", phase, e.getMessage());
        }
    }

    private static String truncateForLog(String s, int maxLen) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "…";
    }

    private BoundingBox waitSmartCaptchaIframeBox(Locator iframeLocator, long timeoutMs) {
        return waitSmartCaptchaIframeElementBox(iframeLocator.first(), timeoutMs);
    }

    private BoundingBox waitSmartCaptchaIframeElementBox(Locator iframeOne, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int tick = 0;
        while (System.currentTimeMillis() < deadline) {
            tick++;
            try {
                if (!isOnScreenVisible(iframeOne)) {
                    iframeOne.scrollIntoViewIfNeeded();
                }
                if (iframeOne.isVisible()) {
                    BoundingBox box = iframeOne.boundingBox();
                    if (box != null && isReasonableCaptchaIframeSize(box)) {
                        log.info("SmartCaptcha DBG [wait-bbox] OK за {} проверок: {}x{} @ ({}, {})",
                                tick, (int) box.width, (int) box.height, (int) box.x, (int) box.y);
                        return box;
                    }
                }
                if (tick == 1 || tick % 5 == 0) {
                    log.info("SmartCaptcha DBG [wait-bbox] tick={} visible={} bbox={}",
                            tick, iframeOne.isVisible(), iframeOne.boundingBox());
                }
            } catch (Exception e) {
                log.info("SmartCaptcha DBG [wait-bbox] tick={} error: {}", tick, e.getMessage());
            }
            pageWait(iframeOne, 400);
        }
        log.warn("SmartCaptcha DBG [wait-bbox] timeout {} ms, bbox так и не получен", timeoutMs);
        return null;
    }

    private void pageWait(Locator iframeLocator, long ms) {
        try {
            iframeLocator.page().waitForTimeout(ms);
        } catch (Exception ignored) {
            // page closed
        }
    }

    private void scrollSmartCaptchaIntoView(Page page) {
        if (isYandexKaleidoscopeChallenge(page)) {
            log.debug("SmartCaptcha DBG [scroll] skip — калейдоскоп в модалке, страницу не крутим");
            return;
        }
        try {
            Locator widget = page.locator(".smart-captcha[data-sitekey], [data-sitekey].smart-captcha");
            if (widget.count() == 0) {
                return;
            }
            Locator first = widget.first();
            BoundingBox box = first.boundingBox();
            if (box != null && box.x > -400 && box.y > -400 && box.width > 2 && box.width < 900) {
                log.debug("SmartCaptcha DBG [scroll] skip — виджет уже в viewport");
                return;
            }
            first.scrollIntoViewIfNeeded();
            page.waitForTimeout(200);
            log.info("SmartCaptcha DBG [scroll] .smart-captcha прокручен (был вне экрана)");
        } catch (Exception e) {
            log.info("SmartCaptcha DBG [scroll] не удалось: {}", e.getMessage());
        }
    }

    private boolean clickInsideYandexCaptchaFrame(Frame frame) {
        String[] selectors = {
                "input#js-button",
                ".CheckboxCaptcha-Button",
                ".CheckboxCaptcha-Checkbox",
                "#checkbox",
                "[role='checkbox']",
                "label"
        };
        for (String sel : selectors) {
            try {
                Locator loc = frame.locator(sel).first();
                loc.click(new Locator.ClickOptions().setTimeout(8000).setForce(true));
                log.info("SmartCaptcha DBG [click-frame] OK selector={} frameUrl={}", sel, frame.url());
                return true;
            } catch (Exception e) {
                log.info("SmartCaptcha DBG [click-frame] FAIL {}: {}", sel, e.getMessage());
            }
        }
        for (Frame child : frame.childFrames()) {
            if (clickInsideYandexCaptchaFrame(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean clickYandexSmartCaptchaViaLocators(Page page) {
        if (isYandexKaleidoscopeChallenge(page) && !isCheckboxIframeChallenge(page)) {
            log.debug("SmartCaptcha DBG [click-frameloc] skip — открыт калейдоскоп, не кликаем чекбокс");
            return false;
        }
        String[] paths = {
                "iframe[data-testid='checkbox-iframe']",
                "iframe[src*='smartcaptcha.cloud.yandex.ru/checkbox']",
                "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']",
                "iframe[src*='smartcaptcha.yandexcloud.net']",
                ".smart-captcha iframe[src*='smartcaptcha']"
        };
        if (!isSmartCaptchaOverlayVisible(page)) {
            paths = new String[]{
                    "iframe[data-testid='checkbox-iframe']",
                    "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']",
                    "iframe[src*='smartcaptcha.yandexcloud.net']",
                    "iframe[data-testid='advanced-iframe']",
                    ".smart-captcha iframe[src*='smartcaptcha']"
            };
        }
        for (String path : paths) {
            try {
                Locator target = page.frameLocator(path).locator(
                        "input#js-button, .CheckboxCaptcha-Checkbox, #checkbox, [role='checkbox']").first();
                target.click(new Locator.ClickOptions().setTimeout(2_500).setForce(true));
                log.info("SmartCaptcha DBG [click-frameloc] OK path={}", path);
                return true;
            } catch (Exception e) {
                log.info("SmartCaptcha DBG [click-frameloc] FAIL {}: {}", path, e.getMessage());
            }
        }
        return false;
    }

    private boolean verifyYandexSmartCaptchaAfterClick(Page page, Frame frame) throws InterruptedException {
        if (isYandexKaleidoscopeChallenge(page)) {
            return false;
        }
        Frame activeFrame = findYandexCaptchaFrame(page);
        if (activeFrame == null) {
            activeFrame = frame;
        }
        try {
            activeFrame.waitForSelector("input#js-button",
                    new Frame.WaitForSelectorOptions().setTimeout(2_500));
        } catch (Exception e) {
            log.info("SmartCaptcha DBG [verify] input#js-button не дождались: {}", e.getMessage());
        }
        try {
            activeFrame.evaluate("window.postMessage(JSON.stringify({methodCall: 'start'}), '*')");
        } catch (Exception e) {
            log.info("SmartCaptcha DBG [verify] postMessage start: {}", e.getMessage());
        }
        Thread.sleep(1500);
        if (isSmartTokenPresentOnPage(page)) {
            log.info("SmartCaptcha DBG [verify] smart-token на странице — OK");
            return true;
        }
        Locator input = activeFrame.locator("input#js-button");
        String aria = input.count() > 0 ? input.getAttribute("aria-checked") : null;
        String checked = activeFrame.locator(".CheckboxCaptcha-Checkbox").count() > 0
                ? activeFrame.locator(".CheckboxCaptcha-Checkbox").getAttribute("data-checked")
                : null;
        log.info("SmartCaptcha DBG [verify] aria-checked={} data-checked={} frameUrl={}",
                aria, checked, activeFrame.url());
        if ("true".equals(aria) || "true".equals(checked)) {
            return true;
        }
        if (isYandexSmartCaptchaPassed(activeFrame)) {
            return true;
        }
        return false;
    }

    private boolean clickYandexSmartCaptcha(Page page, Frame frame) {
        try {
            if (isYandexKaleidoscopeChallenge(page) && !isCheckboxIframeChallenge(page)) {
                log.info("SmartCaptcha DBG [click] skip — калейдоскоп, нужен Pazl/слайдер");
                return false;
            }
            scrollSmartCaptchaIntoView(page);
            if (clickCheckboxInSmartCaptchaIframe(page)) {
                page.waitForTimeout(400);
                if (verifyYandexSmartCaptchaAfterClick(page, findYandexCheckboxCaptchaFrame(page))) {
                    CaptchaVendorLog.localResult("Playwright", "клик «Я не робот»", "успех (checkbox-iframe)");
                    log.info("SmartCaptcha: успешно пройдена кликом по checkbox-iframe");
                    return true;
                }
            }
            boolean clicked = false;
            Frame checkboxFrame = findYandexCheckboxCaptchaFrame(page);
            if (checkboxFrame != null) {
                clicked = clickInsideYandexCaptchaFrame(checkboxFrame);
            } else if (frame != null && !frame.url().contains("/advanced")) {
                clicked = clickInsideYandexCaptchaFrame(frame);
            }
            if (!clicked) {
                for (Frame f : page.frames()) {
                    String url = f.url();
                    if (url != null && url.contains("smartcaptcha") && url.contains("backend")) {
                        if (clickInsideYandexCaptchaFrame(f)) {
                            clicked = true;
                            break;
                        }
                    }
                }
            }
            if (!clicked) {
                clicked = clickYandexSmartCaptchaViaLocators(page);
            }
            Locator iframeOne = findBestOnScreenSmartCaptchaIframe(page);
            if (!clicked && iframeOne != null) {
                BoundingBox box = waitSmartCaptchaIframeElementBox(iframeOne, 15_000);
                if (box != null) {
                    double clickX = box.x + Math.min(28, box.width * 0.12);
                    double clickY = box.y + box.height / 2.0;
                    log.info("SmartCaptcha DBG [click] mouse bbox → ({}, {}) on-screen iframe",
                            (int) clickX, (int) clickY);
                    page.mouse().move(clickX, clickY, new Mouse.MoveOptions().setSteps(10));
                    page.waitForTimeout(150);
                    page.mouse().click(clickX, clickY);
                    clicked = true;
                } else {
                    log.warn("SmartCaptcha DBG [click] bbox=null для on-screen iframe, пробуем force-click");
                    try {
                        iframeOne.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                        log.info("SmartCaptcha DBG [click] force-click по on-screen iframe OK");
                        clicked = true;
                    } catch (Exception e) {
                        log.info("SmartCaptcha DBG [click] force-click on-screen iframe FAIL: {}", e.getMessage());
                    }
                }
            } else if (!clicked) {
                log.warn("SmartCaptcha DBG [click] on-screen iframe не найден (только off-screen advanced-host?)");
            }
            if (!clicked) {
                logSmartCaptchaState(page, "click-all-paths-failed");
                recordFailure(page, CaptchaFailureCode.FLRU_LOGIN_YANDEX_CHECKBOX,
                        "Yandex SmartCaptcha: не удалось нажать чекбокс (iframe без координат или скрыт)",
                        "click-all-paths-failed");
                return false;
            }
            page.waitForTimeout(300);
            if (verifyYandexSmartCaptchaAfterClick(page, frame)) {
                log.info("SmartCaptcha: успешно пройдена после клика");
                return true;
            }
            if (isSmartCaptchaOverlayVisible(page)) {
                log.warn("SmartCaptcha: локальный клик не прошёл — открыт advanced challenge (оверлей поверх формы)");
                recordFailure(page, CaptchaFailureCode.FLRU_LOGIN_YANDEX_ADVANCED,
                        "Yandex SmartCaptcha: открыт visual challenge на форме входа — нужен 2Captcha (advanced)",
                        "click-not-passed-advanced");
            } else {
                log.warn("SmartCaptcha: клик был, но статус не подтверждён (advanced={})",
                        isYandexAdvancedChallenge(page));
                recordFailure(page, CaptchaFailureCode.FLRU_LOGIN_YANDEX_CHECKBOX,
                        "Yandex SmartCaptcha: чекбокс на форме входа не подтверждён",
                        "click-not-passed");
            }
            logSmartCaptchaState(page, "click-not-passed");
            return false;
        } catch (Exception e) {
            log.error("SmartCaptcha: ошибка при клике", e);
            logSmartCaptchaState(page, "click-exception");
            recordFailure(page, CaptchaFailureCode.CAPTCHA_EXCEPTION,
                    "Yandex SmartCaptcha: ошибка при клике — " + e.getMessage(), "click-exception");
            return false;
        }
    }

    /** Скрывает full-screen оверлей Yandex SmartCaptcha (advanced), чтобы не перехватывал клики по «Войти». */
    public void dismissSmartCaptchaOverlay(Page page) {
        try {
            page.evaluate(
                    """
                            () => {
                              const selectors = [
                                '[data-testid="advanced-container"]',
                                '.SmartCaptcha-Overlay',
                                '.SmartCaptcha-Overlay_visible'
                              ];
                              for (const sel of selectors) {
                                document.querySelectorAll(sel).forEach((el) => {
                                  el.style.display = 'none';
                                  el.style.visibility = 'hidden';
                                  el.style.pointerEvents = 'none';
                                  el.classList.remove(
                                    'SmartCaptcha-Overlay_visible',
                                    'SmartCaptcha-Overlay_show_spinner'
                                  );
                                });
                              }
                            }
                            """);
            log.debug("SmartCaptcha: оверлей advanced скрыт (если был)");
        } catch (Exception e) {
            log.debug("SmartCaptcha: не удалось скрыть оверлей: {}", e.getMessage());
        }
    }

    public boolean isSmartCaptchaOverlayVisible(Page page) {
        try {
            Locator overlay = page.locator(
                    "[data-testid='advanced-container'].SmartCaptcha-Overlay_visible, "
                            + ".SmartCaptcha-Overlay.SmartCaptcha-Overlay_visible");
            if (overlay.count() > 0 && isOnScreenVisible(overlay.first())) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Видимый advanced UI Yandex SmartCaptcha: слайдер-пазл / аудио / модалка.
     * Off-screen leftover iframe @ −10000 не считаем.
     */
    public boolean isYandexAdvancedUiVisible(Page page) {
        try {
            if (hasOnScreenSelector(page.mainFrame(), YANDEX_ADVANCED_UI_SELECTOR)) {
                return true;
            }
            for (Frame f : page.frames()) {
                if (f == page.mainFrame()) {
                    continue;
                }
                if (hasOnScreenSelector(f, YANDEX_ADVANCED_UI_SELECTOR)) {
                    return true;
                }
            }
            Locator adv = page.locator("iframe[data-testid='advanced-iframe']");
            int n = adv.count();
            for (int i = 0; i < Math.min(n, 4); i++) {
                if (isOnScreenVisible(adv.nth(i))) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha: проверка advanced UI: {}", e.getMessage());
        }
        return false;
    }

    private boolean hasOnScreenSelector(Frame frame, String selector) {
        try {
            Locator loc = frame.locator(selector);
            int n = loc.count();
            for (int i = 0; i < Math.min(n, 6); i++) {
                if (isOnScreenVisible(loc.nth(i))) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** Playwright isVisible() бывает true у элементов с bbox вне экрана (−10000). */
    private static boolean isOnScreenVisible(Locator locator) {
        try {
            if (!locator.isVisible()) {
                return false;
            }
            BoundingBox box = locator.boundingBox();
            if (box == null || box.width < 2 || box.height < 2) {
                return false;
            }
            return box.x > -500 && box.y > -500;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isReasonableCaptchaIframeSize(BoundingBox box) {
        if (box == null || box.width < 2 || box.height < 2) {
            return false;
        }
        double area = box.width * box.height;
        return area <= SMART_CAPTCHA_MAX_IFRAME_CLICK_AREA;
    }

    /** Видимый iframe чекбокса (YouDo: backend-iframe в модалке, не full-screen advanced-host). */
    private Locator findBestOnScreenSmartCaptchaIframe(Page page) {
        String bestSel = null;
        int bestIdx = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        try {
            for (String sel : SMART_CAPTCHA_CLICK_IFRAME_SELECTORS) {
                Locator loc = page.locator(sel);
                int n = loc.count();
                for (int i = 0; i < Math.min(n, 4); i++) {
                    Locator one = loc.nth(i);
                    if (!isOnScreenVisible(one)) {
                        continue;
                    }
                    BoundingBox box = one.boundingBox();
                    if (!isReasonableCaptchaIframeSize(box)) {
                        continue;
                    }
                    double area = box.width * box.height;
                    double score = 10_000.0 - area;
                    if (sel.contains("checkbox-iframe")) {
                        score += 50_000.0;
                    }
                    if (sel.contains("backend")) {
                        score += 40_000.0;
                    }
                    if (sel.contains("advanced-iframe")) {
                        score -= 5_000.0;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestSel = sel;
                        bestIdx = i;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha: pick on-screen iframe: {}", e.getMessage());
        }
        if (bestSel == null) {
            return null;
        }
        log.info("SmartCaptcha DBG [pick-iframe] sel={} idx={} score={}", bestSel, bestIdx, (int) bestScore);
        return page.locator(bestSel).nth(bestIdx);
    }

    private boolean hasVisibleYandexCheckboxInFrame(Frame frame) {
        try {
            Locator btn = frame.locator(
                    "input#js-button, .CheckboxCaptcha-Button, #checkbox, [role='checkbox']");
            return btn.count() > 0 && btn.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Visual challenge «выберите картинки» / слайдер-пазл / advanced iframe. Простой чекбокс Yandex — не advanced.
     * 2Captcha для Yandex используем только в этом случае.
     */
    private boolean isYandexAdvancedChallenge(Page page) {
        if (isYandexAdvancedUiVisible(page)) {
            return true;
        }
        if (isSmartCaptchaOverlayVisible(page)) {
            return true;
        }
        try {
            for (Frame f : page.frames()) {
                String url = f.url();
                if (url != null && !url.contains("hcaptcha.com")
                        && url.contains("/advanced")
                        && (url.contains("smartcaptcha") || url.contains("captcha.yandex"))) {
                    // frame есть — но leftover после обхода часто остаётся; требуем on-screen UI выше
                    // здесь только если есть хоть какой-то признак challenge controls
                    if (hasOnScreenSelector(f,
                            ".AdvancedCaptcha_kaleidoscope, .CaptchaSlider, [data-testid='challenge-type'], "
                                    + "[data-testid='play'], .AdvancedCaptcha_audio")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha: проверка advanced: {}", e.getMessage());
        }
        return false;
    }

    /** Kaleidoscope (слайдер-пазл) SmartCaptcha. */
    public boolean isYandexKaleidoscopeChallenge(Page page) {
        return kaleidoscopeSolver.isKaleidoscopePuzzleVisible(page);
    }

    public boolean isTwoCaptchaConfigured() {
        return twoCaptchaClient.isConfigured();
    }

    public void recordSmartCaptchaFailure(Page page, CaptchaFailureCode code, String userMessage, String phase) {
        recordFailure(page, code, userMessage, phase);
    }

    /** Чекбокс Yandex SmartCaptcha (`input#js-button` / checkbox-iframe). */
    public boolean isYandexCheckboxVisible(Page page) {
        if (page == null) {
            return false;
        }
        try {
            if (isCheckboxIframeChallenge(page)) {
                return true;
            }
            for (Frame f : page.frames()) {
                String url = f.url();
                if (url != null && url.contains("hcaptcha.com")) {
                    continue;
                }
                if (url != null && url.contains("smartcaptcha") && url.contains("/backend")
                        && hasVisibleYandexCheckboxInFrame(f)) {
                    return true;
                }
                Locator btn = f.locator("input#js-button, .CheckboxCaptcha-Button");
                if (btn.count() > 0 && btn.first().isVisible()) {
                    return true;
                }
            }
            Locator checkboxIframe = page.locator("iframe[data-testid='checkbox-iframe']");
            if (checkboxIframe.count() > 0 && isOnScreenVisible(checkboxIframe.first())) {
                return true;
            }
            Locator robotLabel = page.locator("text=Я не робот");
            if (robotLabel.count() > 0 && robotLabel.first().isVisible()) {
                return true;
            }
            Locator backendHost = page.locator("iframe[src*='smartcaptcha.cloud.yandex.ru/backend']");
            if (backendHost.count() > 0 && isOnScreenVisible(backendHost.first())) {
                Locator btn = page.frameLocator("iframe[src*='smartcaptcha.cloud.yandex.ru/backend']")
                        .locator("input#js-button, .CheckboxCaptcha-Button, #checkbox, [role='checkbox']");
                if (btn.count() > 0 && btn.first().isVisible()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Видимый {@code checkbox-iframe} с контролом «Я не робот». */
    public boolean isCheckboxIframeChallenge(Page page) {
        if (page == null) {
            return false;
        }
        try {
            Locator cbFrame = page.locator("iframe[data-testid='checkbox-iframe']");
            if (cbFrame.count() > 0 && isOnScreenVisible(cbFrame.first())) {
                Locator btn = page.frameLocator("iframe[data-testid='checkbox-iframe']")
                        .locator("input#js-button, .CheckboxCaptcha-Button, #checkbox, [role='checkbox']");
                if (btn.count() > 0) {
                    return true;
                }
            }
            Locator backendHost = page.locator("iframe[src*='smartcaptcha.cloud.yandex.ru/backend']");
            if (backendHost.count() > 0 && isOnScreenVisible(backendHost.first())) {
                Locator btn = page.frameLocator("iframe[src*='smartcaptcha.cloud.yandex.ru/backend']")
                        .locator("input#js-button, .CheckboxCaptcha-Button, #checkbox, [role='checkbox']");
                return btn.count() > 0;
            }
            for (Frame f : page.frames()) {
                String url = f.url();
                if (url != null && url.contains("smartcaptcha") && url.contains("/backend")) {
                    if (hasVisibleYandexCheckboxInFrame(f)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    public int smartCaptchaTokenLength(Page page) {
        if (page == null) {
            return 0;
        }
        try {
            Object len = page.evaluate(
                    """
                            () => {
                              const el = document.querySelector(
                                "input[name='smart-token'], input[data-testid='smart-token'], input[name='smart_token']");
                              const v = el && el.value ? String(el.value) : '';
                              return v.length;
                            }
                            """);
            if (len instanceof Number) {
                return ((Number) len).intValue();
            }
        } catch (Exception ignored) {
            // page closed
        }
        return 0;
    }

    /**
     * SmartCaptcha блокирует сценарий: нет smart-token, но есть чекбокс, оверлей, слайдер или iframe.
     * Ответ {@code /check} в network сам по себе не считается блокировкой (у чекбокса тоже бывает task array).
     */
    public boolean isSmartCaptchaPending(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (smartCaptchaTokenLength(page) > 20 || isSmartTokenPresentOnPage(page)) {
            return false;
        }
        if (isCheckboxIframeChallenge(page) || isSmartCaptchaOverlayVisible(page)) {
            return true;
        }
        if (isYandexKaleidoscopeChallenge(page) || shouldUsePazlCaptcha(page)) {
            return true;
        }
        return hasOnScreenSmartCaptchaIframe(page);
    }

    /** Pazl: canvas или task + advanced UI; не чистый checkbox без advanced. */
    public boolean shouldUsePazlCaptcha(Page page) {
        if (!yandexPazlEnabled) {
            return false;
        }
        if (kaleidoscopeSolver.isKaleidoscopePuzzleVisible(page)) {
            return true;
        }
        if (!kaleidoscopeSolver.hasCapturedPuzzleTask(page)) {
            return false;
        }
        if (isCheckboxIframeChallenge(page) && !isYandexAdvancedChallenge(page)
                && !isSmartCaptchaOverlayVisible(page) && !isYandexKaleidoscopeChallenge(page)) {
            log.info("SmartCaptcha: network task есть, но виден только checkbox-iframe — Pazl не используем");
            return false;
        }
        return isYandexKaleidoscopeChallenge(page) || isYandexAdvancedUiVisible(page);
    }

    public boolean hasCapturedPuzzleTask(Page page) {
        if (page == null) {
            return false;
        }
        return kaleidoscopeSolver.hasCapturedPuzzleTask(page);
    }

    /** Снять off-screen позиционирование iframe SmartCaptcha (часто bbox @ -10000). */
    public void revealOffScreenSmartCaptchaHosts(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.evaluate(
                    """
                            () => {
                              const fix = (el) => {
                                let n = el;
                                for (let i = 0; i < 10 && n; i++) {
                                  const r = n.getBoundingClientRect();
                                  const st = getComputedStyle(n);
                                  if (r.x < -500 || r.y < -500
                                      || st.visibility === 'hidden' || st.display === 'none'
                                      || (st.transform && st.transform !== 'none')) {
                                    n.style.setProperty('visibility', 'visible', 'important');
                                    n.style.setProperty('display', 'block', 'important');
                                    n.style.setProperty('position', 'relative', 'important');
                                    n.style.setProperty('left', '0', 'important');
                                    n.style.setProperty('top', '0', 'important');
                                    n.style.setProperty('transform', 'none', 'important');
                                  }
                                  n = n.parentElement;
                                }
                              };
                              document.querySelectorAll(
                                "iframe[data-testid='checkbox-iframe'], "
                                + "iframe[src*='smartcaptcha.cloud.yandex.ru/backend'], "
                                + ".smart-captcha, iframe[src*='smartcaptcha']:not([data-testid='advanced-iframe'])"
                              ).forEach(fix);
                            }
                            """);
            page.waitForTimeout(350);
            log.debug("SmartCaptcha: reveal off-screen iframe hosts");
        } catch (Exception e) {
            log.debug("SmartCaptcha: reveal widget: {}", e.getMessage());
        }
    }

    public boolean clickCheckboxInSmartCaptchaIframe(Page page) {
        revealOffScreenSmartCaptchaHosts(page);
        CaptchaVendorLog.localResult("Playwright", "клик «Я не робот» (input#js-button)", "попытка");
        String[] paths = {
                "iframe[data-testid='checkbox-iframe']",
                "iframe[src*='smartcaptcha.cloud.yandex.ru/checkbox']",
                "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']"
        };
        for (String path : paths) {
            if (clickYandexCheckboxInsideIframeHost(page, path)) {
                return true;
            }
        }
        for (Frame f : page.frames()) {
            String url = f.url();
            if (url == null || !url.contains("smartcaptcha") || url.contains("/advanced")) {
                continue;
            }
            if (!url.contains("/backend") && !url.contains("/checkbox")) {
                continue;
            }
            if (!hasYandexCheckboxControlInFrame(f)) {
                continue;
            }
            for (String sel : YANDEX_CHECKBOX_CLICK_SELECTORS) {
                try {
                    f.locator(sel).first().click(new Locator.ClickOptions().setTimeout(8_000).setForce(true));
                    log.info("SmartCaptcha: клик {} во frame {}", sel, truncateForLog(url, 90));
                    CaptchaVendorLog.localResult("Playwright", "клик «Я не робот»", "OK selector=" + sel);
                    return true;
                } catch (Exception e) {
                    log.debug("SmartCaptcha: клик {} frame {}: {}", sel, truncateForLog(url, 50), e.getMessage());
                }
            }
        }
        return false;
    }

    private boolean clickYandexCheckboxInsideIframeHost(Page page, String iframeSelector) {
        try {
            Locator host = page.locator(iframeSelector).first();
            if (host.count() == 0) {
                return false;
            }
            if (!isOnScreenVisible(host)) {
                host.scrollIntoViewIfNeeded();
                page.waitForTimeout(200);
            }
            for (String sel : YANDEX_CHECKBOX_CLICK_SELECTORS) {
                try {
                    Locator target = page.frameLocator(iframeSelector).locator(sel).first();
                    target.click(new Locator.ClickOptions().setTimeout(8_000).setForce(true));
                    log.info("SmartCaptcha: клик «Я не робот» {} в {}", sel, iframeSelector);
                    CaptchaVendorLog.localResult("Playwright", "клик «Я не робот»", "OK " + iframeSelector + " → " + sel);
                    return true;
                } catch (Exception e) {
                    log.debug("SmartCaptcha: клик {} в {}: {}", sel, iframeSelector, e.getMessage());
                }
            }
            BoundingBox box = host.boundingBox();
            if (box != null && box.width > 20 && box.height > 20) {
                double x = box.x + Math.min(32, box.width * 0.14);
                double y = box.y + box.height / 2.0;
                page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(8));
                page.waitForTimeout(120);
                page.mouse().click(x, y);
                log.info("SmartCaptcha: клик мышью по checkbox iframe {} @ ({}, {})", iframeSelector, (int) x, (int) y);
                CaptchaVendorLog.localResult("Playwright", "клик «Я не робот»", "OK mouse @ iframe " + iframeSelector);
                return true;
            }
        } catch (Exception e) {
            log.info("SmartCaptcha: checkbox iframe {}: {}", iframeSelector, e.getMessage());
        }
        return false;
    }

    /**
     * Yandex SmartCaptcha token через 2Captcha (без требования advanced UI на странице).
     */
    public boolean solveYandexSmartCaptchaViaToken(Page page) {
        if (page == null || page.isClosed()) {
            log.warn("SmartCaptcha/2Captcha: страница закрыта до применения токена");
            return false;
        }
        Optional<String> siteKey = extractYandexSiteKey(page);
        if (siteKey.isEmpty()) {
            log.warn("SmartCaptcha/2Captcha: sitekey не найден");
            return false;
        }
        String userAgent = page.evaluate("() => navigator.userAgent").toString();
        String cookies = page.context().cookies(page.url()).stream()
                .map(c -> c.name + "=" + c.value)
                .collect(Collectors.joining("; "));
        log.info("SmartCaptcha/2Captcha: createTask YandexSmartCaptchaTaskProxyless sitekey={}",
                truncateForLog(siteKey.get(), 28));

        Optional<String> token = twoCaptchaClient.solveYandexSmartCaptcha(
                page.url(), siteKey.get(), userAgent, cookies);
        if (token.isEmpty()) {
            return false;
        }
        Frame frame = findYandexCaptchaFrame(page);
        Frame tokenFrame = frame != null ? frame : page.mainFrame();
        applyYandexSmartCaptchaToken(page, tokenFrame, token.get());
        dismissSmartCaptchaOverlay(page);
        page.waitForTimeout(1500);
        if (!isSmartCaptchaPending(page)) {
            return true;
        }
        if (smartCaptchaTokenLength(page) > 20) {
            return true;
        }
        return frame != null && isYandexSmartCaptchaPassed(frame);
    }

    private boolean hasOnScreenSmartCaptchaIframe(Page page) {
        if (page == null) {
            return false;
        }
        try {
            if (findBestOnScreenSmartCaptchaIframe(page) != null) {
                return true;
            }
            String[] selectors = {
                    "iframe[data-testid='checkbox-iframe']",
                    "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']",
                    "iframe[data-testid='advanced-iframe']",
                    "iframe[src*='smartcaptcha.yandexcloud.net']",
                    ".smart-captcha iframe"
            };
            for (String sel : selectors) {
                Locator loc = page.locator(sel);
                int n = loc.count();
                for (int i = 0; i < Math.min(n, 4); i++) {
                    if (isOnScreenVisible(loc.nth(i))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha: on-screen iframe check: {}", e.getMessage());
        }
        return false;
    }

    /** Перехват ответов SmartCaptcha до клика «Далее» (task array уходит в network раньше solve). */
    public void beginKaleidoscopeNetworkCapture(Page page) {
        kaleidoscopeSolver.beginNetworkCapture(page);
    }

    public void endKaleidoscopeNetworkCapture(Page page) {
        kaleidoscopeSolver.endNetworkCapture(page);
    }

    /**
     * Advanced / калейдоскоп: 2Captcha token ({@code YandexSmartCaptchaTaskProxyless}), затем audio ({@code AudioTask}).
     * Pazl — только если {@code captcha.yandex.pazl-enabled=true}.
     */
    public boolean solveYandexKaleidoscopeViaPazl(Page page) {
        return solveYandexAdvancedViaTwoCaptcha(page);
    }

    public boolean solveYandexAdvancedViaTwoCaptcha(Page page) {
        clearLastFailure();
        if (!twoCaptchaClient.isConfigured()) {
            recordFailure(page, CaptchaFailureCode.YOUDO_KALEIDOSCOPE_FAIL,
                    "Advanced SmartCaptcha: 2Captcha выключен или нет API key", "2captcha-not-configured");
            return false;
        }
        boolean advanced = isYandexAdvancedChallenge(page)
                || isYandexKaleidoscopeChallenge(page)
                || kaleidoscopeSolver.hasCapturedPuzzleTask(page);
        if (!advanced) {
            log.info("SmartCaptcha/2Captcha advanced: пропуск — нет advanced/kaleidoscope/task");
            return false;
        }
        revealOffScreenSmartCaptchaHosts(page);
        CaptchaVendorLog.route("Yandex advanced/kaleidoscope",
                "1) YandexSmartCaptchaTaskProxyless → smart-token"
                        + (yandexAudioEnabled ? "; 2) AudioTask" : "")
                        + (yandexPazlEnabled ? "; опц. PazlCaptchaTask" : ""));

        log.info("SmartCaptcha/2Captcha: advanced token-only (canvas={} networkTask={})",
                kaleidoscopeSolver.isKaleidoscopePuzzleVisible(page),
                kaleidoscopeSolver.hasCapturedPuzzleTask(page));

        boolean ok = solveYandexSmartCaptchaViaToken(page);
        if (!ok && yandexAudioEnabled) {
            log.info("SmartCaptcha/2Captcha: token не снял challenge — пробуем audio");
            ok = yandexAudioSolver.solve(page);
            if (ok && isSmartTokenPresentOnPage(page)) {
                dismissSmartCaptchaOverlay(page);
            }
        }
        if (!ok && yandexPazlEnabled && shouldUsePazlCaptcha(page)) {
            log.info("SmartCaptcha Pazl: включён captcha.yandex.pazl-enabled — PazlCaptchaTask");
            ok = kaleidoscopeSolver.solve(page);
        }
        if (!ok) {
            recordFailure(page, CaptchaFailureCode.YOUDO_KALEIDOSCOPE_FAIL,
                    "Advanced SmartCaptcha: 2Captcha token/audio не помогли", "advanced-2captcha-fail");
        }
        return ok;
    }

    /** Снимок SmartCaptcha на форме логина (отладка). */
    public void logSmartCaptchaLoginDebug(Page page, String phase) {
        try {
            Frame frame = findYandexCaptchaFrame(page);
            Optional<String> siteKey = extractYandexSiteKey(page);
            Object snap = page.evaluate(
                    """
                            () => {
                              const q = (s) => document.querySelector(s);
                              const vis = (el) => {
                                if (!el) return false;
                                const r = el.getBoundingClientRect();
                                const st = getComputedStyle(el);
                                return r.width > 2 && r.height > 2 && r.x > -500 && r.y > -500
                                  && st.display !== 'none' && st.visibility !== 'hidden';
                              };
                              const tok = q('input[name="smart-token"], input[data-testid="smart-token"], input[name="smart_token"]');
                              const adv = q("iframe[data-testid='advanced-iframe']");
                              const cb = q("iframe[data-testid='checkbox-iframe']");
                              const sc = q("[data-testid='smartCaptcha-container'], .smart-captcha[data-sitekey]");
                              return {
                                url: location.href,
                                otpField: !!q("input[name='code']"),
                                emailField: !!q("input[name='login']"),
                                loginBtn: !!q("[data-test='LoginButton']"),
                                emailLoginBtn: !!q("[data-test='LoginWithEmailButton']"),
                                nextBtnText: Array.from(document.querySelectorAll('button'))
                                  .some(b => (b.innerText || '').includes('Далее')),
                                smartTokenLen: tok && tok.value ? tok.value.length : 0,
                                smartCaptchaContainer: !!sc,
                                smartCaptchaContainerVisible: vis(sc),
                                checkboxIframe: !!cb,
                                checkboxIframeVisible: vis(cb),
                                advancedIframe: !!adv,
                                advancedIframeVisible: vis(adv),
                                advancedIframeSrc: adv ? (adv.getAttribute('src') || '').slice(0, 120) : null,
                                overlayVisible: !!q('.SmartCaptcha-Overlay_visible, [data-testid="advanced-container"].SmartCaptcha-Overlay_visible'),
                                kaleidoscope: !!q('.AdvancedCaptcha_kaleidoscope, .CaptchaSlider, #captcha-slider'),
                                captchaOnMail: !!q('[data-testid="smartCaptcha-container"]'),
                                iframeCount: document.querySelectorAll('iframe').length
                              };
                            }
                            """);
            log.info("SmartCaptcha-LOGIN DBG [{}] siteKeyPresent={} frameUrl={} advancedChallenge={} overlay={} advancedUi={} smartToken={} snap={}",
                    phase,
                    siteKey.isPresent(),
                    frame != null ? truncateForLog(frame.url(), 100) : "null",
                    isYandexAdvancedChallenge(page),
                    isSmartCaptchaOverlayVisible(page),
                    isYandexAdvancedUiVisible(page),
                    isSmartTokenPresentOnPage(page),
                    snap);
        } catch (Exception e) {
            log.warn("SmartCaptcha-LOGIN DBG [{}] snapshot failed: {}", phase, e.getMessage());
        }
    }

    /** Капча на форме логина FL.ru (Yandex SmartCaptcha). Если виджета нет — считаем, что капчи нет. */
    public boolean solveYandexSmartCaptcha(Page page) {
        return solveYandexSmartCaptchaInternal(page, false);
    }

    /**
     * Снимок DOM/виджета для отладки {@code /validate-captcha} (смотри строки {@code validate-captcha DBG} в логе).
     */
    public void logValidateCaptchaState(Page page, String phase) {
        try {
            log.info("validate-captcha DBG [{}] url={}", phase, page.url());
            int hcaptchaIframes = page.locator("iframe[src*='hcaptcha.com']").count();
            log.info("validate-captcha DBG [{}] hcaptcha-iframes={} h-captcha-div={}",
                    phase, hcaptchaIframes, page.locator(".h-captcha").count());
            if (hcaptchaIframes > 0) {
                BoundingBox box = page.locator("iframe[src*='hcaptcha.com']").first().boundingBox();
                if (box != null) {
                    log.info("validate-captcha DBG [{}] iframe-bbox x={} y={} w={} h={} clickTarget=({}, {})",
                            phase, (int) box.x, (int) box.y, (int) box.width, (int) box.height,
                            (int) (box.x + Math.min(28, box.width * 0.12)), (int) (box.y + box.height / 2));
                } else {
                    log.info("validate-captcha DBG [{}] iframe-bbox=null (не виден / не отрисован)", phase);
                }
            }
            Object dom = page.evaluate(
                    """
                            () => {
                              const cap = document.querySelector('.h-captcha');
                              const ta = document.querySelector('textarea[name="h-captcha-response"]');
                              return JSON.stringify({
                                hcaptchaApi: typeof window.hcaptcha,
                                sendCaptcha: typeof window.sendCaptcha,
                                captchaForm: !!document.getElementById('captcha-form'),
                                csrfToken: !!(document.querySelector('input[name="_token"]')),
                                sitekey: cap ? cap.getAttribute('data-sitekey') : null,
                                callback: cap ? cap.getAttribute('data-callback') : null,
                                responseLen: ta && ta.value ? ta.value.length : 0,
                                yandexOverlay: !!document.querySelector('.SmartCaptcha-Overlay_visible')
                              });
                            }
                            """);
            log.info("validate-captcha DBG [{}] dom={}", phase, dom);
            log.info("validate-captcha DBG [{}] twoCaptchaOn={} responseField={} yandexOverlayVisible={}",
                    phase, twoCaptchaClient.isConfigured(), isHCaptchaResponsePresent(page),
                    isSmartCaptchaOverlayVisible(page));
        } catch (Exception e) {
            log.warn("validate-captcha DBG [{}] snapshot failed: {}", phase, e.getMessage());
        }
    }

    /**
     * FL.ru {@code /validate-captcha} после редиректа (IP-блок).
     * На странице используется <strong>hCaptcha</strong> («Я человек»), не Yandex SmartCaptcha с логина.
     */
    public boolean solveFlRuValidateCaptcha(Page page) {
        clearLastFailure();
        try {
            log.info("FL.ru validate-captcha: сценарий IP-блокировки (не форма логина)");
            logValidateCaptchaState(page, "01-start");
            waitForFlRuValidateCaptchaWidget(page);
            logValidateCaptchaState(page, "02-after-wait-widget");

            if (isHCaptchaPresent(page)) {
                boolean ok = solveFlRuValidateHCaptcha(page);
                logValidateCaptchaState(page, ok ? "99-hcaptcha-ok" : "99-hcaptcha-fail");
                return ok;
            }

            if (hasYandexSmartCaptchaWidget(page)) {
                log.debug("validate-captcha: найден Yandex SmartCaptcha");
                if (solveYandexSmartCaptchaInternal(page, true)) {
                    dismissSmartCaptchaOverlay(page);
                    return true;
                }
            }

            if (clickFlRuValidateHumanWidget(page)) {
                page.waitForTimeout(2000);
                dismissSmartCaptchaOverlay(page);
                if (isFlRuValidateCaptchaLikelyPassed(page)) {
                    log.info("validate-captcha: пройдено кликом «Я человек»");
                    return true;
                }
            }

            if (yandexTwoCaptchaFallback && twoCaptchaClient.isConfigured() && isYandexAdvancedChallenge(page)) {
                Frame frame = findYandexCaptchaFrame(page);
                Frame tokenFrame = frame != null ? frame : page.mainFrame();
                if (extractYandexSiteKey(page).isPresent()
                        && solveYandexSmartCaptchaViaTwoCaptcha(page, tokenFrame)) {
                    dismissSmartCaptchaOverlay(page);
                    log.info("validate-captcha: пройдено через 2Captcha (Yandex advanced)");
                    return true;
                }
            } else if (yandexTwoCaptchaFallback && twoCaptchaClient.isConfigured()) {
                log.info("validate-captcha: Yandex без advanced — 2Captcha не вызываем");
            }

            log.warn("validate-captcha: не удалось пройти IP-капчу");
            logValidateCaptchaState(page, "99-fail-no-hcaptcha-path");
            recordFailure(page, CaptchaFailureCode.FLRU_VALIDATE_UNKNOWN,
                    "FL.ru /validate-captcha: не удалось пройти IP-капчу (hCaptcha/Yandex)",
                    "99-fail-no-hcaptcha-path");
            return false;
        } catch (Exception e) {
            log.error("validate-captcha: ошибка", e);
            logValidateCaptchaState(page, "99-exception");
            recordFailure(page, CaptchaFailureCode.CAPTCHA_EXCEPTION,
                    "FL.ru /validate-captcha: " + e.getMessage(), "99-exception");
            return false;
        }
    }

    private boolean solveFlRuValidateHCaptcha(Page page) {
        log.info("validate-captcha: обнаружена hCaptcha (FL.ru IP-блок)");
        logValidateCaptchaState(page, "10-hcaptcha-start");

        // Сначала 2Captcha без клика по чекбоксу — иначе откроется visual challenge с картинками,
        // который локально не решается.
        if (twoCaptchaClient.isConfigured()) {
            log.info("validate-captcha DBG [11] шаг: 2Captcha hCaptcha (без клика по чекбоксу)");
            boolean twoCapOk = solveHCaptchaViaTwoCaptcha(page);
            logValidateCaptchaState(page, "12-after-2captcha-hcaptcha ok=" + twoCapOk);
            if (twoCapOk && waitFlRuValidateCaptchaCleared(page)) {
                log.info("validate-captcha: hCaptcha пройдена через 2Captcha (без puzzle)");
                return true;
            }
            if (isSmartCaptchaOverlayVisible(page) || findYandexCaptchaFrame(page) != null) {
                log.info("validate-captcha DBG [13] 2Captcha hCaptcha не увёл со страницы, пробуем Yandex overlay");
                if (trySolveValidateCaptchaVisualOverlay(page) && waitFlRuValidateCaptchaCleared(page)) {
                    log.info("validate-captcha: visual challenge пройден через 2Captcha (Yandex token)");
                    return true;
                }
                logValidateCaptchaState(page, "14-after-2captcha-yandex-fallback");
            } else {
                log.warn("validate-captcha DBG [13] 2Captcha не вернул токен в срок — локальный клик не делаем "
                        + "(откроет hCaptcha puzzle без токена). Попробуйте другой прокси или увеличьте "
                        + "captcha.two-captcha.timeout-ms / task-max-attempts");
                logValidateCaptchaState(page, "14-2captcha-exhausted-no-local");
            }
            log.warn("validate-captcha: hCaptcha не пройдена (2Captcha)");
            logValidateCaptchaState(page, "29-hcaptcha-fail");
            recordFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_TWO_CAPTCHA,
                    "FL.ru /validate-captcha: hCaptcha не пройдена — 2Captcha не вернула токен в срок "
                            + "(попробуйте другой прокси или увеличьте captcha.two-captcha.timeout-ms)",
                    "29-hcaptcha-fail-2cap");
            return false;
        } else {
            log.warn("validate-captcha DBG [11] 2Captcha выключен — только локальный клик");
        }

        log.info("validate-captcha DBG [20] шаг: локальный клик по чекбоксу hCaptcha");
        logValidateCaptchaState(page, "20-before-checkbox-click");
        if (clickHCaptchaCheckbox(page)) {
            page.waitForTimeout(2500);
            logValidateCaptchaState(page, "21-after-checkbox-click");
            if (trySolveValidateCaptchaVisualOverlay(page) && waitFlRuValidateCaptchaCleared(page)) {
                return true;
            }
            if (waitFlRuValidateCaptchaCleared(page)) {
                log.info("validate-captcha: hCaptcha пройдена локальным кликом (без puzzle)");
                return true;
            }
            log.info("validate-captcha DBG [22] после клика — sendCaptcha/submit");
            submitFlRuValidateCaptchaForm(page);
            page.waitForTimeout(2000);
            logValidateCaptchaState(page, "23-after-submit");
            if (trySolveValidateCaptchaVisualOverlay(page) && waitFlRuValidateCaptchaCleared(page)) {
                return true;
            }
            if (waitFlRuValidateCaptchaCleared(page)) {
                log.info("validate-captcha: hCaptcha + submit формы");
                return true;
            }
        } else {
            log.warn("validate-captcha DBG [20] локальный клик по чекбоксу не выполнен");
        }

        if (isSmartCaptchaOverlayVisible(page) || findYandexCaptchaFrame(page) != null) {
            log.warn("validate-captcha: открыт visual challenge (картинки) — локально не решается, нужен 2Captcha");
            recordFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_VISUAL,
                    "FL.ru /validate-captcha: hCaptcha visual puzzle — без токена 2Captcha не пройти",
                    "29-hcaptcha-visual");
        } else if (isHCaptchaResponsePresent(page)) {
            recordFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_STUCK,
                    "FL.ru /validate-captcha: hCaptcha — ответ есть, но сайт не снял блокировку",
                    "29-hcaptcha-stuck");
        } else {
            recordFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_VISUAL,
                    "FL.ru /validate-captcha: hCaptcha «подозрительная активность» не пройдена",
                    "29-hcaptcha-fail");
        }
        log.warn("validate-captcha: hCaptcha не пройдена");
        logValidateCaptchaState(page, "29-hcaptcha-fail");
        return false;
    }

    /** Картинки «выберите всё, что…» — Yandex SmartCaptcha overlay; решается только токеном 2Captcha. */
    private boolean trySolveValidateCaptchaVisualOverlay(Page page) {
        if (!isSmartCaptchaOverlayVisible(page) && findYandexCaptchaFrame(page) == null) {
            return false;
        }
        if (!twoCaptchaClient.isConfigured()) {
            log.warn("validate-captcha: visual challenge без TWO_CAPTCHA_ENABLED");
            return false;
        }
        Optional<String> siteKey = extractYandexSiteKey(page);
        if (siteKey.isEmpty()) {
            log.warn("validate-captcha: visual challenge — sitekey Yandex не найден");
            return false;
        }
        Frame frame = findYandexCaptchaFrame(page);
        Frame tokenFrame = frame != null ? frame : page.mainFrame();
        if (!solveYandexSmartCaptchaViaTwoCaptcha(page, tokenFrame)) {
            return false;
        }
        dismissSmartCaptchaOverlay(page);
        submitFlRuValidateCaptchaForm(page);
        page.waitForTimeout(1500);
        return true;
    }

    private boolean waitFlRuValidateCaptchaCleared(Page page) {
        long deadline = System.currentTimeMillis() + 20_000;
        int tick = 0;
        while (System.currentTimeMillis() < deadline) {
            tick++;
            if (!page.url().contains("validate-captcha") && !isHCaptchaPresent(page)) {
                log.info("validate-captcha DBG [wait] страница покинута за {} проверок, url={}", tick, page.url());
                return true;
            }
            if (isHCaptchaResponsePresent(page)) {
                log.info("validate-captcha DBG [wait] h-captcha-response заполнен (tick={})", tick);
                page.waitForTimeout(1500);
                if (!page.url().contains("validate-captcha")) {
                    return true;
                }
            }
            if (tick == 1 || tick % 10 == 0) {
                log.info("validate-captcha DBG [wait] tick={} url={} response={}",
                        tick, page.url(), isHCaptchaResponsePresent(page));
            }
            page.waitForTimeout(400);
        }
        boolean left = !page.url().contains("validate-captcha");
        log.warn("validate-captcha DBG [wait] timeout 20s, left={}, url={}", left, page.url());
        return left;
    }

    private boolean isHCaptchaPresent(Page page) {
        try {
            if (page.locator("iframe[src*='hcaptcha.com']").count() > 0) {
                return true;
            }
            if (page.locator(".h-captcha, [data-hcaptcha-widget-id]").count() > 0) {
                return true;
            }
            return page.locator("text=hCaptcha").count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHCaptchaResponsePresent(Page page) {
        try {
            Object len = page.evaluate(
                    """
                            () => {
                              const ta = document.querySelector('textarea[name="h-captcha-response"]');
                              if (ta && ta.value && ta.value.length > 20) return ta.value.length;
                              const ta2 = document.querySelector('[name="g-recaptcha-response"]');
                              if (ta2 && ta2.value && ta2.value.length > 20) return ta2.value.length;
                              return 0;
                            }
                            """);
            return len instanceof Number number && number.longValue() > 20;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForHcaptchaApi(Page page) {
        try {
            log.info("validate-captcha DBG [api] ожидание window.hcaptcha / textarea…");
            page.waitForFunction(
                    "() => typeof window.hcaptcha !== 'undefined' || "
                            + "document.querySelector('textarea[name=\"h-captcha-response\"]') !== null",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(20_000));
            log.info("validate-captcha DBG [api] hcaptcha API готов");
        } catch (Exception e) {
            log.warn("validate-captcha DBG [api] api.js не загрузился за 20 с: {}", e.getMessage());
        }
    }

    private Optional<String> extractHCaptchaSiteKey(Page page) {
        String[] selectors = {".h-captcha[data-sitekey]", "div.h-captcha[data-sitekey]", "[data-sitekey]"};
        for (String selector : selectors) {
            Locator loc = page.locator(selector);
            if (loc.count() == 0) {
                continue;
            }
            try {
                String key = loc.first().getAttribute("data-sitekey");
                if (key != null && !key.isBlank()) {
                    return Optional.of(key);
                }
            } catch (Exception ignored) {
                // next
            }
        }
        try {
            Locator iframe = page.locator("iframe[src*='hcaptcha.com']");
            if (iframe.count() > 0) {
                String src = iframe.first().getAttribute("src");
                Optional<String> fromSrc = siteKeyFromUrl(src);
                if (fromSrc.isPresent()) {
                    return fromSrc;
                }
            }
        } catch (Exception e) {
            log.debug("hCaptcha: sitekey из iframe: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private boolean clickHCaptchaCheckbox(Page page) {
        String iframeSel = "iframe[src*='hcaptcha.com']";
        try {
            Locator outerIframe = page.locator(iframeSel);
            int outerCount = outerIframe.count();
            log.info("validate-captcha DBG [click] outer iframes hcaptcha.com={}", outerCount);
            if (outerCount > 0) {
                outerIframe.first().scrollIntoViewIfNeeded();
                page.waitForTimeout(400);

                var outerFrame = page.frameLocator(iframeSel).first();
                String[] innerSelectors = {"#checkbox", "[role='checkbox']", "#anchor-wr", ".check"};
                for (String innerSel : innerSelectors) {
                    try {
                        outerFrame.frameLocator("iframe").locator(innerSel).first()
                                .click(new Locator.ClickOptions().setTimeout(8000));
                        log.info("validate-captcha DBG [click] OK nested-iframe selector={}", innerSel);
                        return true;
                    } catch (Exception e) {
                        log.info("validate-captcha DBG [click] FAIL nested-iframe {}: {}", innerSel, e.getMessage());
                    }
                }
                for (String innerSel : innerSelectors) {
                    try {
                        outerFrame.locator(innerSel).first()
                                .click(new Locator.ClickOptions().setTimeout(8000));
                        log.info("validate-captcha DBG [click] OK outer-frame selector={}", innerSel);
                        return true;
                    } catch (Exception e) {
                        log.info("validate-captcha DBG [click] FAIL outer-frame {}: {}", innerSel, e.getMessage());
                    }
                }

                BoundingBox box = outerIframe.first().boundingBox();
                if (box != null) {
                    double x = box.x + Math.min(28, box.width * 0.12);
                    double y = box.y + box.height / 2.0;
                    page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(14));
                    page.waitForTimeout(300);
                    page.mouse().down();
                    page.waitForTimeout(100);
                    page.mouse().up();
                    log.info("validate-captcha DBG [click] mouse on iframe bbox → ({}, {})", (int) x, (int) y);
                    return true;
                }
                log.warn("validate-captcha DBG [click] iframe bbox=null");
            }
            Locator widget = page.locator(".h-captcha");
            log.info("validate-captcha DBG [click] .h-captcha count={}", widget.count());
            if (widget.count() > 0) {
                widget.first().scrollIntoViewIfNeeded();
                BoundingBox box = widget.first().boundingBox();
                if (box != null) {
                    double x = box.x + Math.min(28, box.width * 0.12);
                    double y = box.y + box.height / 2.0;
                    page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(14));
                    page.waitForTimeout(300);
                    page.mouse().click(x, y);
                    log.info("validate-captcha DBG [click] mouse on .h-captcha → ({}, {})", (int) x, (int) y);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("validate-captcha DBG [click] exception: {}", e.getMessage());
        }
        return false;
    }

    private boolean solveHCaptchaViaTwoCaptcha(Page page) {
        Optional<String> siteKey = extractHCaptchaSiteKey(page);
        if (siteKey.isEmpty()) {
            log.warn("validate-captcha DBG [2cap] sitekey не извлечён");
            return false;
        }
        waitForHcaptchaApi(page);
        log.info("validate-captcha DBG [2cap] sitekey={} url={}", siteKey.get(), page.url());
        String userAgent = page.evaluate("() => navigator.userAgent").toString();
        String cookies = page.context().cookies(page.url()).stream()
                .map(c -> c.name + "=" + c.value)
                .collect(Collectors.joining("; "));
        log.info("validate-captcha DBG [2cap] запрос токена у 2Captcha…");
        Optional<String> token = twoCaptchaClient.solveHCaptcha(
                page.url(), siteKey.get(), userAgent, cookies);
        if (token.isEmpty()) {
            log.warn("validate-captcha DBG [2cap] токен не получен (timeout или ошибка API)");
            return false;
        }
        log.info("validate-captcha DBG [2cap] токен получен, len={}", token.get().length());
        applyHCaptchaToken(page, token.get());
        logValidateCaptchaState(page, "2cap-after-apply-token");
        page.waitForTimeout(500);
        submitFlRuValidateCaptchaForm(page);
        waitForValidateCaptchaRedirect(page);
        boolean ok = !page.url().contains("validate-captcha");
        log.info("validate-captcha DBG [2cap] done responseField={} url={} success={}",
                isHCaptchaResponsePresent(page), page.url(), ok);
        return ok;
    }

    private void waitForValidateCaptchaRedirect(Page page) {
        try {
            page.waitForURL(
                    url -> url != null && !url.contains("validate-captcha"),
                    new Page.WaitForURLOptions().setTimeout(25_000));
            log.info("validate-captcha: редирект после POST, url={}", page.url());
        } catch (Exception e) {
            log.warn("validate-captcha: редирект не произошёл за 25 с, url={}", page.url());
        }
    }

    private void applyHCaptchaToken(Page page, String token) {
        page.evaluate(
                """
                        (token) => {
                          const form = document.getElementById('captcha-form') || document.querySelector('form');
                          const ensureTextarea = (name) => {
                            let el = form && form.querySelector(`textarea[name="${name}"], input[name="${name}"]`);
                            if (!el && form) {
                              el = document.createElement('textarea');
                              el.name = name;
                              el.style.display = 'none';
                              form.appendChild(el);
                            }
                            if (el) {
                              el.value = token;
                              el.innerHTML = token;
                              el.dispatchEvent(new Event('input', { bubbles: true }));
                              el.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                          };
                          ensureTextarea('h-captcha-response');
                          ensureTextarea('g-recaptcha-response');

                          if (window.hcaptcha && typeof window.hcaptcha.setResponse === 'function') {
                            const ids = new Set();
                            document.querySelectorAll('[data-hcaptcha-widget-id]').forEach((el) => {
                              ids.add(el.getAttribute('data-hcaptcha-widget-id'));
                            });
                            document.querySelectorAll('iframe[data-hcaptcha-widget-id]').forEach((el) => {
                              ids.add(el.getAttribute('data-hcaptcha-widget-id'));
                            });
                            if (ids.size === 0) {
                              ids.add('0');
                              ids.add(0);
                            }
                            ids.forEach((id) => {
                              try {
                                window.hcaptcha.setResponse(id, token);
                              } catch (e) { /* ignore */ }
                            });
                          }
                        }
                        """,
                token);
        log.info("hCaptcha/2Captcha: response применён на странице");
    }

    /** FL.ru: {@code data-callback="sendCaptcha"} → {@code #captcha-form}.submit() */
    private void submitFlRuValidateCaptchaForm(Page page) {
        try {
            Object mode = page.evaluate(
                    """
                            () => {
                              if (typeof window.sendCaptcha === 'function') {
                                window.sendCaptcha();
                                return 'sendCaptcha';
                              }
                              const form = document.getElementById('captcha-form') || document.querySelector('form');
                              if (!form) return 'no-form';
                              if (typeof form.requestSubmit === 'function') {
                                form.requestSubmit();
                                return 'requestSubmit';
                              }
                              form.submit();
                              return 'submit';
                            }
                            """);
            log.info("validate-captcha DBG [submit] отправка формы mode={} url={}", mode, page.url());
        } catch (Exception e) {
            log.warn("validate-captcha: submit формы: {}", e.getMessage());
        }
    }

    private void waitForFlRuValidateCaptchaWidget(Page page) {
        long deadline = System.currentTimeMillis() + 15_000;
        String[] cssSelectors = {
                "iframe[src*='hcaptcha.com']",
                ".h-captcha",
                "iframe[src*='smartcaptcha.yandexcloud.net']"
        };
        while (System.currentTimeMillis() < deadline) {
            for (String selector : cssSelectors) {
                try {
                    if (page.locator(selector).count() > 0 && page.locator(selector).first().isVisible()) {
                        log.info("validate-captcha DBG [wait-widget] найден: {}", selector);
                        return;
                    }
                } catch (Exception ignored) {
                    // next
                }
            }
            try {
                if (page.getByText("подозрительная активность").count() > 0) {
                    log.debug("validate-captcha: текст IP-блока найден");
                    return;
                }
            } catch (Exception ignored) {
                // next
            }
            page.waitForTimeout(300);
        }
        log.warn("validate-captcha: виджет капчи не появился за 15 с");
    }

    private boolean hasYandexSmartCaptchaWidget(Page page) {
        if (findYandexCaptchaFrame(page) != null) {
            return true;
        }
        return page.locator("iframe[src*='smartcaptcha.yandexcloud.net'], .smart-captcha").count() > 0;
    }

    private boolean clickFlRuValidateHumanWidget(Page page) {
        String[] selectors = {
                "iframe[src*='smartcaptcha.yandexcloud.net']",
                "#label",
                "label-td",
                "text=Я человек"
        };
        for (String selector : selectors) {
            try {
                Locator loc = page.locator(selector);
                if (loc.count() == 0) {
                    continue;
                }
                Locator target = loc.first();
                if (selector.startsWith("iframe")) {
                    BoundingBox box = target.boundingBox();
                    if (box == null) {
                        continue;
                    }
                    double x = box.x + Math.min(28, box.width / 3);
                    double y = box.y + box.height / 2;
                    page.mouse().click(x, y);
                    log.debug("validate-captcha: клик по iframe SmartCaptcha ({}, {})", (int) x, (int) y);
                } else {
                    if (!target.isVisible()) {
                        continue;
                    }
                    target.click(new Locator.ClickOptions().setTimeout(5000));
                    log.debug("validate-captcha: клик по {}", selector);
                }
                page.waitForTimeout(800);
                Frame frame = findYandexCaptchaFrame(page);
                if (frame != null) {
                    try {
                        frame.evaluate("window.postMessage(JSON.stringify({methodCall: 'start'}), '*')");
                    } catch (Exception ignored) {
                        // optional
                    }
                }
                return true;
            } catch (Exception e) {
                log.debug("validate-captcha: клик {} не удался: {}", selector, e.getMessage());
            }
        }
        return false;
    }

    private boolean isFlRuValidateCaptchaLikelyPassed(Page page) {
        if (isSmartTokenPresentOnPage(page)) {
            return true;
        }
        try {
            Locator label = page.locator("#label:has-text('Я человек')");
            if (label.count() > 0 && label.first().isVisible()) {
                return false;
            }
            Frame frame = findYandexCaptchaFrame(page);
            if (frame != null && isYandexSmartCaptchaPassed(frame)) {
                return true;
            }
        } catch (Exception e) {
            log.debug("validate-captcha: проверка результата: {}", e.getMessage());
        }
        return !isSmartCaptchaOverlayVisible(page);
    }

    private boolean solveYandexSmartCaptchaInternal(Page page, boolean captchaRequired) {
        clearLastFailure();
        try {
            waitForYandexSmartCaptchaWidget(page, 12_000);
            logSmartCaptchaState(page, "01-start");
            Frame frame = findYandexCaptchaFrame(page);
            if (frame == null) {
                if (captchaRequired) {
                    log.warn("SmartCaptcha: виджет обязателен, но iframe не найден");
                    logSmartCaptchaState(page, "01-no-frame");
                    return false;
                }
                log.info("SmartCaptcha DBG [01] iframe не найден — капча отсутствует, url={}", page.url());
                return true;
            }
            log.info("SmartCaptcha DBG [02] frame найден url={}, captchaRequired={}", frame.url(), captchaRequired);

            if (yandexTwoCaptchaFirst && twoCaptchaClient.isConfigured() && isYandexAdvancedChallenge(page)) {
                if (solveYandexSmartCaptchaViaTwoCaptcha(page, frame)) {
                    log.info("SmartCaptcha: пройдена через 2Captcha (primary, advanced)");
                    return true;
                }
                log.warn("SmartCaptcha: 2Captcha (primary) не помог, пробуем локальный клик");
            }

            if (clickYandexSmartCaptcha(page, frame)) {
                log.info("SmartCaptcha: успешно пройдена локальным кликом");
                return true;
            }

            if (!isYandexAdvancedChallenge(page)) {
                if (isSmartTokenPresentOnPage(page) || isYandexSmartCaptchaPassed(frame)) {
                    dismissSmartCaptchaOverlay(page);
                    log.info("SmartCaptcha: чекбокс — smart-token/статус OK без advanced, 2Captcha не нужен");
                    return true;
                }
                log.warn("SmartCaptcha: чекбокс не пройден локально; 2Captcha только для advanced — не вызываем");
                logSmartCaptchaState(page, "03-checkbox-fail-no-advanced");
                if (getLastFailure().isEmpty()) {
                    recordFailure(page, CaptchaFailureCode.FLRU_LOGIN_YANDEX_CHECKBOX,
                            "Yandex SmartCaptcha на входе: чекбокс не пройден (виджет не отрисован или клик не сработал)",
                            "03-checkbox-fail-no-advanced");
                }
                return false;
            }

            if (yandexTwoCaptchaFallback && twoCaptchaClient.isConfigured()) {
                if (solveYandexSmartCaptchaViaTwoCaptcha(page, frame)) {
                    log.info("SmartCaptcha: пройдена через 2Captcha (fallback, advanced)");
                    return true;
                }
            }

            log.warn("SmartCaptcha: не удалось пройти (advanced)");
            recordFailure(page, CaptchaFailureCode.FLRU_LOGIN_YANDEX_ADVANCED,
                    "Yandex SmartCaptcha advanced на входе: 2Captcha не помогла", "advanced-fail");
            return false;

        } catch (Exception e) {
            log.error("SmartCaptcha: ошибка при обходе", e);
            recordFailure(page, CaptchaFailureCode.CAPTCHA_EXCEPTION,
                    "Yandex SmartCaptcha: " + e.getMessage(), "solve-internal-exception");
            return false;
        }
    }

    private boolean solveYandexSmartCaptchaViaTwoCaptcha(Page page, Frame frame) {
        if (!isYandexAdvancedChallenge(page)) {
            log.info("SmartCaptcha/2Captcha: пропуск — на странице нет advanced challenge");
            return false;
        }
        Optional<String> siteKey = extractYandexSiteKey(page);
        if (siteKey.isEmpty()) {
            log.warn("SmartCaptcha/2Captcha: sitekey не извлечён");
            return false;
        }

        log.info("SmartCaptcha/2Captcha: advanced challenge — запрос токена");
        String userAgent = page.evaluate("() => navigator.userAgent").toString();
        String cookies = page.context().cookies(page.url()).stream()
                .map(c -> c.name + "=" + c.value)
                .collect(Collectors.joining("; "));

        Optional<String> token = twoCaptchaClient.solveYandexSmartCaptcha(
                page.url(), siteKey.get(), userAgent, cookies);
        if (token.isEmpty()) {
            return false;
        }

        applyYandexSmartCaptchaToken(page, frame, token.get());
        dismissSmartCaptchaOverlay(page);
        page.waitForTimeout(1500);
        if (isYandexSmartCaptchaPassed(frame)) {
            return true;
        }
        if (isSmartTokenPresentOnPage(page)) {
            if (isSmartCaptchaOverlayVisible(page)) {
                log.warn("SmartCaptcha/2Captcha: smart-token есть, но advanced-оверлей ещё виден — скрываем и продолжаем");
                dismissSmartCaptchaOverlay(page);
                page.waitForTimeout(500);
            }
            log.info("SmartCaptcha/2Captcha: чекбокс в iframe не отметился, но smart-token на странице есть — продолжаем логин");
            return true;
        }
        log.warn("SmartCaptcha/2Captcha: токен получен, но не подтверждён ни UI, ни полем smart-token");
        return false;
    }

    private Optional<String> extractYandexSiteKey(Page page) {
        String[] iframeSelectors = {
                "iframe[data-testid='checkbox-iframe']",
                "iframe[data-testid='advanced-iframe']",
                "iframe[src*='smartcaptcha.yandexcloud.net']",
                "iframe[src*='captcha.yandex']"
        };
        for (String selector : iframeSelectors) {
            Locator iframe = page.locator(selector);
            if (iframe.count() == 0) {
                continue;
            }
            try {
                String src = iframe.first().getAttribute("src");
                Optional<String> fromSrc = siteKeyFromUrl(src);
                if (fromSrc.isPresent()) {
                    return fromSrc;
                }
            } catch (Exception e) {
                log.debug("SmartCaptcha: sitekey из iframe {} не получен: {}", selector, e.getMessage());
            }
        }

        try {
            Locator container = page.locator("[data-sitekey]");
            if (container.count() > 0) {
                String key = container.first().getAttribute("data-sitekey");
                if (key != null && !key.isBlank()) {
                    return Optional.of(key);
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha: data-sitekey не найден: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> siteKeyFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = SITEKEY_QUERY.matcher(url);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8));
    }

    private void applyYandexSmartCaptchaToken(Page page, Frame captchaFrame, String token) {
        page.evaluate(
                """
                        (token) => {
                          const setHidden = (root) => {
                            const selectors = [
                              'input[name="smart-token"]',
                              'input[name="smart_token"]',
                              'input[data-testid="smart-token"]'
                            ];
                            for (const sel of selectors) {
                              const el = root.querySelector(sel);
                              if (el) {
                                el.value = token;
                                el.dispatchEvent(new Event('input', { bubbles: true }));
                                el.dispatchEvent(new Event('change', { bubbles: true }));
                                return true;
                              }
                            }
                            return false;
                          };
                          if (!setHidden(document)) {
                            const form = document.querySelector('form');
                            if (form) {
                              const input = document.createElement('input');
                              input.type = 'hidden';
                              input.name = 'smart-token';
                              input.value = token;
                              form.appendChild(input);
                            }
                          }
                          const containers = document.querySelectorAll('.smart-captcha, [data-sitekey]');
                          containers.forEach((container) => {
                            setHidden(container);
                            const cbName = container.getAttribute('data-callback');
                            if (cbName && typeof window[cbName] === 'function') {
                              window[cbName](token);
                            }
                          });
                          if (window.smartCaptcha && typeof window.smartCaptcha.render === 'function') {
                            try {
                              const resp = window.smartCaptcha.getResponse && window.smartCaptcha.getResponse();
                              if (!resp && window.smartCaptcha.execute) {
                                /* noop — token injected manually */
                              }
                            } catch (e) { /* ignore */ }
                          }
                          window.dispatchEvent(new CustomEvent('smart-captcha-token', { detail: token }));
                          window.postMessage(JSON.stringify({ event: 'smart-captcha', token }), '*');
                          const submit = document.querySelector('#submit-button, button[type="submit"]');
                          if (submit) {
                            submit.removeAttribute('disabled');
                            submit.disabled = false;
                            submit.classList.remove('disabled');
                          }
                        }
                        """,
                token);
        try {
            captchaFrame.evaluate(
                    """
                            (token) => {
                              window.postMessage(JSON.stringify({ methodCall: 'success', token }), '*');
                            }
                            """,
                    token);
        } catch (Exception e) {
            log.debug("SmartCaptcha/2Captcha: postMessage во frame: {}", e.getMessage());
        }
        dismissSmartCaptchaOverlay(page);
        resumeYandexSmartCaptchaForForm(page);
        log.debug("SmartCaptcha/2Captcha: token применён на странице");
    }

    /**
     * После внешнего токена (2Captcha): сообщить виджету YouDo/React об успехе и снять блокировку «Далее».
     */
    public void resumeYandexSmartCaptchaForForm(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            page.evaluate(
                    """
                            () => {
                              const pickToken = () => {
                                const sel = [
                                  'input[name="smart-token"]',
                                  'input[name="smart_token"]',
                                  'input[data-testid="smart-token"]'
                                ];
                                for (const s of sel) {
                                  const el = document.querySelector(s);
                                  if (el && el.value && el.value.length > 20) return String(el.value);
                                }
                                if (window.smartCaptcha && typeof window.smartCaptcha.getResponse === 'function') {
                                  const r = window.smartCaptcha.getResponse();
                                  if (r && r.length > 20) return r;
                                }
                                return '';
                              };
                              const token = pickToken();
                              if (!token) return false;
                              const msgs = [
                                JSON.stringify({ event: 'smart-captcha', token }),
                                JSON.stringify({ methodCall: 'success', token }),
                                JSON.stringify({ type: 'smart-captcha', token })
                              ];
                              for (const m of msgs) {
                                window.postMessage(m, '*');
                              }
                              document.querySelectorAll('iframe[src*="smartcaptcha"]').forEach((iframe) => {
                                try {
                                  iframe.contentWindow.postMessage(msgs[1], '*');
                                } catch (e) { /* cross-origin */ }
                              });
                              document.querySelectorAll('.smart-captcha, [data-sitekey]').forEach((container) => {
                                const cbName = container.getAttribute('data-callback');
                                if (cbName && typeof window[cbName] === 'function') {
                                  try { window[cbName](token); } catch (e) { /* ignore */ }
                                }
                              });
                              window.dispatchEvent(new CustomEvent('smart-captcha-token', { detail: token }));
                              for (const b of document.querySelectorAll('button')) {
                                const text = (b.textContent || '').trim();
                                if (!text.includes('Далее')) continue;
                                b.disabled = false;
                                b.removeAttribute('aria-busy');
                                b.classList.remove('disabled');
                              }
                              return true;
                            }
                            """);
        } catch (Exception e) {
            log.debug("SmartCaptcha: resume после токена: {}", e.getMessage());
        }
        for (Frame frame : page.frames()) {
            String url = frame.url();
            if (url == null || !url.contains("smartcaptcha")) {
                continue;
            }
            try {
                frame.evaluate(
                        """
                                () => {
                                  const tokenEl = document.querySelector(
                                    "input[name='smart-token'], input[name='smart_token']");
                                  const token = tokenEl && tokenEl.value ? tokenEl.value : '';
                                  if (!token || token.length < 20) return;
                                  const msg = JSON.stringify({ methodCall: 'success', token });
                                  window.postMessage(msg, '*');
                                  if (window.parent && window.parent !== window) {
                                    window.parent.postMessage(msg, '*');
                                  }
                                }
                                """);
            } catch (Exception e) {
                log.debug("SmartCaptcha: resume frame {}: {}", url, e.getMessage());
            }
        }
    }

    private boolean isSmartTokenPresentOnPage(Page page) {
        try {
            Object len = page.evaluate(
                    """
                            () => {
                              const pick = (root) => {
                                const sel = ['input[name="smart-token"]', 'input[name="smart_token"]'];
                                for (const s of sel) {
                                  const el = root.querySelector(s);
                                  if (el && el.value && el.value.length > 20) return el.value.length;
                                }
                                return 0;
                              };
                              let n = pick(document);
                              if (n > 0) return n;
                              for (const c of document.querySelectorAll('.smart-captcha, [data-sitekey]')) {
                                n = pick(c);
                                if (n > 0) return n;
                              }
                              if (window.smartCaptcha && typeof window.smartCaptcha.getResponse === 'function') {
                                const r = window.smartCaptcha.getResponse();
                                if (r && r.length > 20) return r.length;
                              }
                              return 0;
                            }
                            """);
            if (len instanceof Number number) {
                return number.longValue() > 20;
            }
            return false;
        } catch (Exception e) {
            log.debug("SmartCaptcha/2Captcha: проверка smart-token: {}", e.getMessage());
            return false;
        }
    }

    private boolean isYandexSmartCaptchaPassed(Frame frame) {
        try {
            Locator input = frame.locator("input#js-button");
            if (input.count() > 0 && "true".equals(input.getAttribute("aria-checked"))) {
                return true;
            }
            Locator checkbox = frame.locator(".CheckboxCaptcha-Checkbox");
            if (checkbox.count() > 0 && "true".equals(checkbox.getAttribute("data-checked"))) {
                return true;
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha: проверка после 2Captcha: {}", e.getMessage());
        }
        return findYandexCaptchaFrame(frame.page()) == null;
    }

    public boolean isCloudflareChallengePresent(Page page) {
        try {
            for (Frame f : page.frames()) {
                String url = f.url();
                if (url != null && (url.contains("challenges.cloudflare.com") || url.contains("turnstile"))) {
                    log.debug("Cloudflare Turnstile: frame detected {}", url);
                    return true;
                }
            }
            String[] textSelectors = {
                    "text=Я человек",
                    "text=Verify you are human",
                    "text=Just a moment",
                    "text=Checking your browser"
            };
            for (String sel : textSelectors) {
                Locator loc = page.locator(sel);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    log.debug("Cloudflare Turnstile: текст challenge найден ({})", sel);
                    return true;
                }
            }
            if (page.locator("iframe[src*='challenges.cloudflare.com']").count() > 0) {
                return true;
            }
            if (page.locator("iframe[src*='turnstile']").count() > 0) {
                return true;
            }
            if (page.locator(".cf-turnstile, [data-turnstile-widget]").count() > 0) {
                return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("Cloudflare Turnstile: ошибка проверки presence: {}", e.getMessage());
            return false;
        }
    }

    public boolean waitForCfClearance(Page page, String cookieDomain, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hasCfClearanceCookie(page, cookieDomain)) {
                log.info("Cloudflare Turnstile: cf_clearance получен для {}", cookieDomain);
                return true;
            }
            page.waitForTimeout(500);
        }
        log.warn("Cloudflare Turnstile: timeout ожидания cf_clearance ({} ms)", timeoutMs);
        return false;
    }

    private boolean hasCfClearanceCookie(Page page, String cookieDomain) {
        try {
            List<Cookie> cookies = page.context().cookies(cookieDomain);
            return cookies.stream().anyMatch(c -> "cf_clearance".equals(c.name));
        } catch (Exception e) {
            return false;
        }
    }

    private Locator findTurnstileWidget(Page page) {
        String[] selectors = {
                "iframe[src*='challenges.cloudflare.com']",
                "iframe[src*='turnstile']",
                ".cf-turnstile",
                "[data-turnstile-widget]"
        };
        for (String sel : selectors) {
            Locator loc = page.locator(sel);
            if (loc.count() > 0) {
                return loc.first();
            }
        }
        return null;
    }

    private void humanLikeClickOnWidget(Page page, Locator widget) {
        BoundingBox box = widget.boundingBox();
        if (box == null) {
            log.warn("Cloudflare Turnstile: boundingBox виджета не получен");
            return;
        }
        double cx = box.x + box.width / 2 + (Math.random() * 10 - 5);
        double cy = box.y + box.height / 2 + (Math.random() * 10 - 5);
        int steps = 8 + (int) (Math.random() * 8);
        page.mouse().move(cx, cy, new Mouse.MoveOptions().setSteps(steps));
        page.waitForTimeout(200 + (int) (Math.random() * 300));
        page.mouse().click(cx, cy);
        log.info("Cloudflare Turnstile: клик по виджету ({}, {})", (int) cx, (int) cy);
    }

    private void tryClickInsideTurnstileFrame(Page page) {
        String[] iframeSelectors = {
                "iframe[src*='challenges.cloudflare.com']",
                "iframe[src*='turnstile']"
        };
        for (String sel : iframeSelectors) {
            if (page.locator(sel).count() == 0) {
                continue;
            }
            try {
                Locator inner = page.frameLocator(sel)
                        .locator("input[type=checkbox], .ctp-checkbox-label, label, [role=checkbox]")
                        .first();
                inner.click(new Locator.ClickOptions().setTimeout(5000));
                log.info("Cloudflare Turnstile: клик внутри iframe ({})", sel);
                return;
            } catch (Exception e) {
                log.debug("Cloudflare Turnstile: клик внутри iframe не удался ({}): {}", sel, e.getMessage());
            }
        }
    }

    private boolean isLoginFormVisible(Page page) {
        try {
            Locator login = page.locator("input[name='login']");
            return login.count() > 0 && login.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean solveCloudflareTurnstile(Page page) {
        if (!cloudflareEnabled) {
            log.debug("Cloudflare Turnstile: отключено в конфиге");
            return true;
        }
        try {
            if (!isCloudflareChallengePresent(page)) {
                log.debug("Cloudflare Turnstile: challenge не обнаружен");
                return true;
            }
            log.info("Cloudflare Turnstile: начинаем прохождение...");

            Locator widget = findTurnstileWidget(page);
            if (widget != null) {
                humanLikeClickOnWidget(page, widget);
            } else {
                log.warn("Cloudflare Turnstile: виджет не найден, пробуем клик внутри iframe");
            }
            tryClickInsideTurnstileFrame(page);

            page.waitForTimeout(1500);

            if (waitForCfClearance(page, WEBLANCER_COOKIE_URL, cloudflareTimeoutMs)) {
                return true;
            }
            if (!isCloudflareChallengePresent(page) && isLoginFormVisible(page)) {
                log.info("Cloudflare Turnstile: challenge исчез, форма логина доступна");
                return true;
            }
            if (!isCloudflareChallengePresent(page)) {
                log.info("Cloudflare Turnstile: challenge исчез после клика");
                return true;
            }
            log.warn("Cloudflare Turnstile: не удалось пройти");
            return false;
        } catch (Exception e) {
            log.error("Cloudflare Turnstile: ошибка при обходе", e);
            return false;
        }
    }
}
