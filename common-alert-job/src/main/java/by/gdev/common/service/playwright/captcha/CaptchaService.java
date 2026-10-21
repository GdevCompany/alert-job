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
import com.microsoft.playwright.options.WaitForSelectorState;
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
     * YouDo/React: после внешнего токена (2Captcha) нужно вызвать {@code callback} из {@code smartCaptcha.render}
     * и подписчиков {@code subscribe(..., 'success')} — иначе {@code getResponse()=0} и спиннер «Далее» не снимается.
     */
    private static final String DISPATCH_YANDEX_WIDGET_SUCCESS_JS = """
            (token) => {
              if (!token || token.length < 20) return JSON.stringify({ hits: 0, reason: 'no-token' });
              let hits = 0;
              const setHidden = (root) => {
                const sel = ['input[name="smart-token"]', 'input[name="smart_token"]'];
                const desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                const setter = desc && desc.set;
                for (const s of sel) {
                  const el = (root || document).querySelector(s);
                  if (el) {
                    if (setter) setter.call(el, token); else el.value = token;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                  }
                }
                return false;
              };
              setHidden(document);
              document.querySelectorAll('[data-sentry-component="SmartCaptcha"], .smart-captcha, [data-sitekey]')
                .forEach((c) => setHidden(c));
              const walkFiber = (dom, visitor) => {
                if (!dom) return;
                const key = Object.keys(dom).find((k) => k.startsWith('__reactFiber$'));
                if (!key) return;
                let fiber = dom[key];
                for (let i = 0; i < 160 && fiber; i++) {
                  visitor(fiber);
                  fiber = fiber.return;
                }
              };
              const tryCall = (fn, t) => {
                if (typeof fn !== 'function') return false;
                try { fn(t); return true; } catch (e1) {
                  try { fn(); return true; } catch (e2) { return false; }
                }
              };
              const invokeProps = (props, t) => {
                if (!props || typeof props !== 'object') return 0;
                let n = 0;
                for (const [name, fn] of Object.entries(props)) {
                  if (typeof fn !== 'function') continue;
                  if (/callback|onSuccess|onCaptcha|onVerify|onToken|success|smartCaptcha/i.test(name)) {
                    if (tryCall(fn, t)) n++;
                  }
                }
                return n;
              };
              const roots = document.querySelectorAll(
                '[role="dialog"], [data-sentry-component="SmartCaptcha"], input[name="login"], [data-sitekey]');
              roots.forEach((dom) => {
                walkFiber(dom, (fiber) => {
                  const tn = fiber.type && (fiber.type.displayName || fiber.type.name
                    || (fiber.elementType && (fiber.elementType.displayName || fiber.elementType.name))) || '';
                  const props = fiber.memoizedProps || fiber.pendingProps;
                  if (!props) return;
                  if (/SignIn|SmartCaptcha|Captcha|Email|Login/i.test(String(tn))) {
                    hits += invokeProps(props, token);
                  }
                  hits += invokeProps(props, token);
                });
                const propKey = Object.keys(dom).find((k) => k.startsWith('__reactProps$'));
                if (propKey && dom[propKey]) hits += invokeProps(dom[propKey], token);
              });
              document.querySelectorAll('.smart-captcha, [data-sitekey]').forEach((container) => {
                const cb = container.getAttribute('data-callback');
                if (cb && typeof window[cb] === 'function') {
                  try { window[cb](token); hits++; } catch (e) { /* ignore */ }
                }
              });
              if (window.smartCaptcha) {
                const collectIds = () => {
                  const ids = [];
                  const push = (v) => { if (v !== undefined && v !== null && v !== '') ids.push(v); };
                  try {
                    const maps = [
                      window.smartCaptcha._widgetIdMap,
                      window.smartCaptcha._widgetIds,
                      window.smartCaptcha._widgets,
                      window.smartCaptcha._clients
                    ];
                    for (const m of maps) {
                      if (!m) continue;
                      if (typeof m === 'object') {
                        for (const v of Object.values(m)) push(v);
                      }
                    }
                  } catch (e) { /* ignore */ }
                  document.querySelectorAll('[data-smart-captcha-widget-id]').forEach((el) => {
                    push(el.getAttribute('data-smart-captcha-widget-id'));
                  });
                  return [...new Set(ids)];
                };
                for (const id of collectIds()) {
                  try {
                    if (typeof window.smartCaptcha.setResponse === 'function') {
                      window.smartCaptcha.setResponse(id, token);
                      hits++;
                    }
                  } catch (e) { /* ignore */ }
                }
                try {
                  if (typeof window.smartCaptcha.setResponse === 'function') {
                    window.smartCaptcha.setResponse(token);
                    hits++;
                  }
                } catch (e) { /* ignore */ }
              }
              const payloads = [
                { event: 'smart-captcha', token },
                { methodCall: 'success', token },
                { type: 'smart-captcha', token },
                { status: 'success', token },
                { eventType: 'captcha-success', token }
              ];
              for (const p of payloads) {
                window.postMessage(JSON.stringify(p), '*');
                window.dispatchEvent(new CustomEvent('smart-captcha-token', { detail: token }));
              }
              document.querySelectorAll('iframe[src*="smartcaptcha"]').forEach((iframe) => {
                for (const p of payloads) {
                  try { iframe.contentWindow.postMessage(JSON.stringify(p), '*'); } catch (e) { /* cross-origin */ }
                }
              });
              let apiLen = 0;
              try {
                if (window.smartCaptcha && typeof window.smartCaptcha.getResponse === 'function') {
                  const r = window.smartCaptcha.getResponse();
                  apiLen = r && r.length > 20 ? r.length : 0;
                }
              } catch (e) { /* ignore */ }
              return JSON.stringify({ hits, tokenApiLen: apiLen });
            }
            """;

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

    /** Прокси текущего Playwright-запроса (для {@code YandexSmartCaptchaTask} в 2Captcha, если включено). */
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
        if (page == null || page.isClosed()) {
            return;
        }
        revealOffScreenSmartCaptchaHosts(page);
        String selector =
                "iframe[src*='smartcaptcha.yandexcloud.net'], iframe[src*='smartcaptcha.cloud.yandex.ru'], "
                        + "iframe[data-testid='checkbox-iframe'], iframe[data-testid='advanced-iframe'], "
                        + ".smart-captcha, [data-sitekey], [data-sentry-component='SmartCaptcha']";
        try {
            page.waitForSelector(
                    selector,
                    new Page.WaitForSelectorOptions()
                            .setTimeout(timeoutMs)
                            .setState(WaitForSelectorState.ATTACHED));
            revealOffScreenSmartCaptchaHosts(page);
            log.info("SmartCaptcha DBG [wait-widget] селектор виджета появился за ≤{} ms", timeoutMs);
        } catch (Exception e) {
            revealOffScreenSmartCaptchaHosts(page);
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
            log.info("SmartCaptcha DBG [{}] advanced={} overlayVisible={} tokenВПоле={} tokenAPI={}",
                    phase,
                    isYandexAdvancedChallenge(page),
                    isSmartCaptchaOverlayVisible(page),
                    smartCaptchaTokenLength(page),
                    readYandexSmartCaptchaApiResponseLength(page));
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

    /** Показать full-screen advanced/kaleidoscope после того как {@link #dismissSmartCaptchaOverlay} спрятал оверлей. */
    public void revealSmartCaptchaOverlay(Page page) {
        try {
            page.evaluate(
                    """
                            () => {
                              const selectors = [
                                '[data-testid="advanced-container"]',
                                '.SmartCaptcha-Overlay',
                                'iframe[data-testid="advanced-iframe"]',
                                'iframe[src*="smartcaptcha.cloud.yandex.ru/advanced"]'
                              ];
                              for (const sel of selectors) {
                                document.querySelectorAll(sel).forEach((el) => {
                                  el.style.removeProperty('display');
                                  el.style.removeProperty('visibility');
                                  el.style.removeProperty('pointer-events');
                                  el.style.removeProperty('opacity');
                                  el.style.removeProperty('z-index');
                                  el.classList.add('SmartCaptcha-Overlay_visible');
                                });
                              }
                            }
                            """);
            log.info("SmartCaptcha: оверлей advanced показан (если был в DOM)");
        } catch (Exception e) {
            log.debug("SmartCaptcha: не удалось показать оверлей: {}", e.getMessage());
        }
    }

    /**
     * YouDo: после 2Captcha full-screen {@code advanced-iframe} часто остаётся в DOM и перехватывает клик по «Далее».
     */
    public void suppressYouDoLoginCaptchaClickBlockers(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        dismissSmartCaptchaOverlay(page);
        try {
            page.evaluate(
                    """
                            () => {
                              const hide = (el) => {
                                if (!el) return;
                                el.style.setProperty('pointer-events', 'none', 'important');
                                el.style.setProperty('visibility', 'hidden', 'important');
                                el.style.setProperty('opacity', '0', 'important');
                                el.style.setProperty('z-index', '-1', 'important');
                              };
                              document.querySelectorAll(
                                'iframe[data-testid="advanced-iframe"], iframe[src*="smartcaptcha"]'
                              ).forEach((iframe) => {
                                const r = iframe.getBoundingClientRect();
                                if (r.width > 400 || r.height > 400) {
                                  hide(iframe);
                                }
                              });
                              document.querySelectorAll(
                                '[data-sentry-component="SmartCaptcha"], [data-testid="smartCaptcha-container"], .smart-captcha'
                              ).forEach((host) => {
                                const dialog = document.querySelector('[role="dialog"]');
                                if (dialog && dialog.contains(host)) {
                                  host.style.setProperty('pointer-events', 'none', 'important');
                                  host.style.setProperty('max-height', '1px', 'important');
                                  host.style.setProperty('overflow', 'hidden', 'important');
                                }
                              });
                            }
                            """);
            log.debug("SmartCaptcha/YouDo: click-blockers сняты (iframe/host)");
        } catch (Exception e) {
            log.debug("SmartCaptcha/YouDo: suppress blockers: {}", e.getMessage());
        }
    }

    /** Подготовка формы email YouDo сразу после внешнего smart-token. */
    public void prepareYouDoEmailSubmitAfterSmartToken(Page page) {
        suppressYouDoLoginCaptchaClickBlockers(page);
        resumeYandexSmartCaptchaForForm(page);
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
    public boolean isYandexAdvancedChallenge(Page page) {
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

    /** Длина токена в {@code window.smartCaptcha.getResponse()} (виджет принял капчу в сессии браузера). */
    public int smartCaptchaApiTokenLength(Page page) {
        return readYandexSmartCaptchaApiResponseLength(page);
    }

    /**
     * Капча пройдена по контракту Yandex/2Captcha: токен в {@code smartCaptcha.getResponse()},
     * а не только в hidden {@code smart-token} (см. 2captcha.com/api-docs/yandex-smart-captcha).
     */
    public boolean isYandexSmartCaptchaAcceptedByWidget(Page page) {
        return smartCaptchaApiTokenLength(page) > 20;
    }

    /** YouDo: виджет принял токен и нет видимого advanced/kaleidoscope. */
    public boolean isYandexSmartCaptchaReadyForYouDoSubmit(Page page) {
        if (!isYandexSmartCaptchaAcceptedByWidget(page)) {
            return false;
        }
        try {
            return !isYandexKaleidoscopeChallenge(page)
                    && !isYandexAdvancedUiVisible(page)
                    && !isSmartCaptchaOverlayVisible(page);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isYouDoPage(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            String url = page.url();
            return url != null && url.contains("youdo");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * YouDo: не прячем калейдоскоп/оверлей, пока {@code getResponse()} пустой — иначе кажется, что капча «снята».
     */
    public void dismissSmartCaptchaOverlayIfWidgetAccepted(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        if (isYandexSmartCaptchaAcceptedByWidget(page)) {
            dismissSmartCaptchaOverlay(page);
            if (isYouDoPage(page)) {
                suppressYouDoLoginCaptchaClickBlockers(page);
                clearCapturedPuzzleTasks(page);
            }
            return;
        }
        if (isYouDoPage(page)) {
            ensureYouDoCaptchaChallengeVisible(page);
        }
    }

    /** Вернуть challenge на экран, если внешний токен в DOM, но виджет ещё не принял. */
    public void ensureYouDoCaptchaChallengeVisible(Page page) {
        if (page == null || page.isClosed() || !isYouDoPage(page)) {
            return;
        }
        if (isYandexSmartCaptchaAcceptedByWidget(page)) {
            return;
        }
        revealYouDoLoginCaptchaIframe(page);
        revealOffScreenSmartCaptchaHosts(page);
        revealSmartCaptchaOverlay(page);
        log.info("SmartCaptcha/YouDo: challenge оставлен/показан — getResponse() ещё пустой (tokenВПоле={})",
                smartCaptchaTokenLength(page));
    }

    private static final String SMART_CAPTCHA_API_RESPONSE_LEN_JS = """
            () => {
              if (!window.smartCaptcha || typeof window.smartCaptcha.getResponse !== 'function') {
                return 0;
              }
              const len = (r) => (r && r.length > 20 ? r.length : 0);
              try {
                const r = window.smartCaptcha.getResponse();
                const n = len(r);
                if (n > 0) return n;
              } catch (e) { /* try per-widget id */ }
              const idMaps = [
                window.smartCaptcha._widgetIdMap,
                window.smartCaptcha._widgetIds,
                window.smartCaptcha._widgets
              ];
              for (const ids of idMaps) {
                if (!ids || typeof ids !== 'object') continue;
                for (const id of Object.values(ids)) {
                  try {
                    const r = window.smartCaptcha.getResponse(id);
                    const n = len(r);
                    if (n > 0) return n;
                  } catch (err) { /* next id */ }
                }
              }
              return 0;
            }
            """;

    /**
     * YouDo: калейдоскоп с task из {@code /check} — Pazl в той же сессии, что и виджет (не {@code YandexSmartCaptchaTask}).
     */
    public boolean solveYouDoBrowserSessionKaleidoscope(Page page) {
        if (page == null || page.isClosed() || !twoCaptchaClient.isConfigured()) {
            return false;
        }
        if (smartCaptchaTokenLength(page) > 20
                && !isYandexKaleidoscopeChallenge(page)
                && !isYandexAdvancedUiVisible(page)) {
            log.info("SmartCaptcha/YouDo: smart-token уже в DOM, видимого пазла нет — Pazl в сессии не нужен");
            clearCapturedPuzzleTasks(page);
            return false;
        }
        if (!kaleidoscopeSolver.hasCapturedPuzzleTask(page)
                && !isYandexKaleidoscopeChallenge(page)
                && !isYandexAdvancedUiVisible(page)) {
            return false;
        }
        revealYouDoLoginCaptchaIframe(page);
        revealOffScreenSmartCaptchaHosts(page);
        revealSmartCaptchaOverlay(page);
        log.info("SmartCaptcha/YouDo: PazlCaptchaTask по network task (сессия браузера)");
        boolean ok = kaleidoscopeSolver.solve(page);
        if (!ok && yandexAudioEnabled) {
            if (kaleidoscopeSolver.lastPazlAbortedForAudio()) {
                log.info("SmartCaptcha/YouDo: Pazl solution=0 — переключаемся на AudioTask (сессия браузера)");
            } else {
                log.info("SmartCaptcha/YouDo: Pazl не прошёл — пробуем AudioTask");
            }
            // voice из /check на калейдоскопе — не аудио-задание; только после challenge-type + voice?key=
            ok = yandexAudioSolver.solve(page);
            log.info("SmartCaptcha/YouDo: AudioTask после Pazl → {}", ok ? "ok" : "не снято");
        }
        if (ok && isYandexSmartCaptchaAcceptedByWidget(page)) {
            dismissSmartCaptchaOverlay(page);
            resumeYandexSmartCaptchaForForm(page);
            page.waitForTimeout(800);
        } else if (ok) {
            ensureYouDoCaptchaChallengeVisible(page);
        }
        boolean widgetOk = isYandexSmartCaptchaAcceptedByWidget(page);
        logSmartCaptchaResolutionVerdict(page, widgetOk ? "youdo-browser-pazl-ok" : "youdo-browser-pazl-fail");
        return widgetOk;
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

    public void clearCapturedPuzzleTasks(Page page) {
        kaleidoscopeSolver.clearCapturedPuzzleTasks(page);
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
                                + ".smart-captcha, [data-sentry-component='SmartCaptcha'], "
                                + "iframe[src*='smartcaptcha']:not([data-testid='advanced-iframe'])"
                              ).forEach(fix);
                            }
                            """);
            page.waitForTimeout(350);
            log.debug("SmartCaptcha: reveal off-screen iframe hosts");
        } catch (Exception e) {
            log.debug("SmartCaptcha: reveal widget: {}", e.getMessage());
        }
    }

    /**
     * YouDo (Chromium): только сдвинуть checkbox/backend iframe, если он за экраном.
     * Не трогаем родителей {@code display:block !important} и не трогаем advanced-iframe — иначе ломается модалка.
     */
    public void revealYouDoLoginCaptchaIframe(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            Object moved = page.evaluate(
                    """
                            () => {
                              let n = 0;
                              const sel = "iframe[data-testid='checkbox-iframe'], "
                                + "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']";
                              for (const iframe of document.querySelectorAll(sel)) {
                                const r = iframe.getBoundingClientRect();
                                if (r.x > -400 && r.y > -400 && r.width > 2) continue;
                                iframe.style.position = 'relative';
                                iframe.style.left = '0';
                                iframe.style.top = '0';
                                iframe.style.transform = 'none';
                                iframe.style.visibility = 'visible';
                                n++;
                              }
                              return n;
                            }
                            """);
            log.info("SmartCaptcha/YouDo: мягкий reveal checkbox-iframe (сдвинуто: {})", moved);
        } catch (Exception e) {
            log.debug("SmartCaptcha/YouDo: мягкий reveal: {}", e.getMessage());
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
        ProxyCredentials browserProxy = CURRENT_PROXY.get();
        log.info("SmartCaptcha/2Captcha: createTask Yandex SmartCaptcha sitekey={} browserProxy={}",
                truncateForLog(siteKey.get(), 28),
                browserProxy != null && browserProxy.getHost() != null
                        ? browserProxy.getHost() + ":" + browserProxy.getPort()
                        : "нет");

        Optional<String> token = twoCaptchaClient.solveYandexSmartCaptcha(
                page.url(), siteKey.get(), userAgent, cookies, browserProxy);
        if (token.isEmpty()) {
            return false;
        }
        Frame frame = findYandexCaptchaFrame(page);
        Frame tokenFrame = frame != null ? frame : page.mainFrame();
        String tokenStr = token.get();
        applyYandexSmartCaptchaToken(page, tokenFrame, tokenStr);
        waitForYandexSmartCaptchaWidgetAcceptance(page, tokenStr, 6_000);
        if (isYandexSmartCaptchaAcceptedByWidget(page)) {
            dismissSmartCaptchaOverlayIfWidgetAccepted(page);
            logSmartCaptchaResolutionVerdict(page, "after-2captcha-token-viaToken");
            return true;
        }
        boolean checkboxUi = frame != null && isYandexSmartCaptchaPassed(frame);
        if (checkboxUi && !isYandexKaleidoscopeChallenge(page) && !isYandexAdvancedUiVisible(page)) {
            dismissSmartCaptchaOverlayIfWidgetAccepted(page);
            logSmartCaptchaResolutionVerdict(page, "after-2captcha-token-viaToken-checkbox-ui");
            return true;
        }
        if (isYouDoPage(page)) {
            ensureYouDoCaptchaChallengeVisible(page);
        }
        logSmartCaptchaResolutionVerdict(page, "after-2captcha-token-viaToken-fail");
        return false;
    }

    /** После внешнего токена: ждём {@code getResponse()} и повторяем dispatch колбэков виджета. */
    private void waitForYandexSmartCaptchaWidgetAcceptance(Page page, String token, long timeoutMs) {
        if (page == null || page.isClosed()) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !page.isClosed()) {
            if (isYandexSmartCaptchaAcceptedByWidget(page)) {
                return;
            }
            dispatchYandexWidgetSuccess(page, token);
            page.waitForTimeout(450);
        }
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
        revealYouDoLoginCaptchaIframe(page);
        revealSmartCaptchaOverlay(page);
        CaptchaVendorLog.route("Yandex advanced/kaleidoscope",
                "1) YandexSmartCaptchaTaskProxyless → smart-token"
                        + (yandexAudioEnabled ? "; 2) AudioTask" : "")
                        + (yandexPazlEnabled ? "; опц. PazlCaptchaTask" : ""));

        log.info("SmartCaptcha/2Captcha: advanced token-only (canvas={} networkTask={})",
                kaleidoscopeSolver.isKaleidoscopePuzzleVisible(page),
                kaleidoscopeSolver.hasCapturedPuzzleTask(page));

        boolean ok = solveYandexSmartCaptchaViaToken(page);
        log.info("SmartCaptcha/2Captcha advanced: YandexSmartCaptchaTaskProxyless → {}", ok ? "ok" : "не снято");
        if (!ok && yandexAudioEnabled) {
            log.info("SmartCaptcha/2Captcha: token не снял challenge — пробуем audio");
            ok = yandexAudioSolver.solve(page);
            log.info("SmartCaptcha/2Captcha advanced: AudioTask → {}", ok ? "ok" : "не снято");
            if (ok && isSmartTokenPresentOnPage(page)) {
                if (isYouDoPage(page)) {
                    dismissSmartCaptchaOverlayIfWidgetAccepted(page);
                } else {
                    dismissSmartCaptchaOverlay(page);
                }
            }
        }
        if (!ok && yandexPazlEnabled && shouldUsePazlCaptcha(page)) {
            log.info("SmartCaptcha Pazl: включён captcha.yandex.pazl-enabled — PazlCaptchaTask");
            ok = kaleidoscopeSolver.solve(page);
            log.info("SmartCaptcha/2Captcha advanced: PazlCaptchaTask → {}", ok ? "ok" : "не снято");
        }
        if (!ok) {
            recordFailure(page, CaptchaFailureCode.YOUDO_KALEIDOSCOPE_FAIL,
                    "Advanced SmartCaptcha: 2Captcha token/audio не помогли", "advanced-2captcha-fail");
        }
        boolean widgetOk = isYandexSmartCaptchaAcceptedByWidget(page);
        logSmartCaptchaResolutionVerdict(page, widgetOk ? "after-advanced-2captcha-ok" : "after-advanced-2captcha-fail");
        return widgetOk;
    }

    /**
     * YouDo: checkbox и advanced — сначала {@code YandexSmartCaptchaTaskProxyless}, затем audio/pazl при advanced.
     */
    public boolean solveYandexForYouDoViaTwoCaptcha(Page page) {
        clearLastFailure();
        if (!twoCaptchaClient.isConfigured()) {
            log.warn("SmartCaptcha/YouDo/2Captcha: выключен или нет API key");
            return false;
        }
        revealYouDoLoginCaptchaIframe(page);
        revealSmartCaptchaOverlay(page);
        boolean kaleidoscopeSession = kaleidoscopeSolver.hasCapturedPuzzleTask(page)
                || isYandexKaleidoscopeChallenge(page)
                || isYandexAdvancedUiVisible(page)
                || isSmartCaptchaOverlayVisible(page);
        if (kaleidoscopeSession) {
            CaptchaVendorLog.route("YOUDO Yandex SmartCaptcha",
                    "1) PazlCaptchaTask (network /check task, сессия браузера)"
                            + (yandexAudioEnabled ? "; 2) AudioTask" : ""));
            boolean pazlOk = solveYouDoBrowserSessionKaleidoscope(page);
            int apiLen = smartCaptchaApiTokenLength(page);
            log.info("SmartCaptcha/YouDo: после Pazl в сессии → ok={} tokenAPI={} tokenВПоле={}",
                    pazlOk, apiLen, smartCaptchaTokenLength(page));
            if (pazlOk) {
                return true;
            }
            recordFailure(page, CaptchaFailureCode.YOUDO_KALEIDOSCOPE_FAIL,
                    "YouDo SmartCaptcha: Pazl в сессии браузера не дал getResponse", "youdo-browser-pazl");
            logSmartCaptchaResolutionVerdict(page, "youdo-pazl-session-fail");
            log.info("SmartCaptcha/YouDo: Pazl не завершил сессию — YandexSmartCaptchaTask (прокси браузера)");
            boolean advancedOk = solveYandexAdvancedViaTwoCaptcha(page);
            log.info("SmartCaptcha/YouDo: после Pazl → advanced 2Captcha → {}", advancedOk ? "ok" : "не снято");
            return advancedOk && isYandexSmartCaptchaAcceptedByWidget(page);
        }
        CaptchaVendorLog.route("YOUDO Yandex SmartCaptcha", "1) YandexSmartCaptchaTaskProxyless (checkbox)");
        log.info("SmartCaptcha/YouDo/2Captcha: checkbox — YandexSmartCaptchaTask");
        boolean ok = solveYandexSmartCaptchaViaToken(page);
        log.info("SmartCaptcha/YouDo/2Captcha: YandexSmartCaptchaTaskProxyless → {}", ok ? "ok" : "не снято");
        if (ok && isYandexSmartCaptchaReadyForYouDoSubmit(page)) {
            log.info("SmartCaptcha/YouDo/2Captcha: checkbox — getResponse OK, challenge нет");
            logSmartCaptchaResolutionVerdict(page, "youdo-2captcha-checkbox-ok");
            return true;
        }
        if (!ok) {
            recordFailure(page, CaptchaFailureCode.YOUDO_KALEIDOSCOPE_FAIL,
                    "YouDo SmartCaptcha: checkbox 2Captcha не помог", "youdo-2captcha-checkbox");
        }
        logSmartCaptchaResolutionVerdict(page, ok ? "youdo-2captcha-checkbox-ok" : "youdo-2captcha-checkbox-fail");
        return ok && isYandexSmartCaptchaAcceptedByWidget(page);
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

    /** Капча на форме логина (Yandex SmartCaptcha). Если виджета нет — считаем, что капчи нет. */
    public boolean solveYandexSmartCaptcha(Page page) {
        return solveYandexSmartCaptchaInternal(page, false);
    }

    /** Виджет обязателен (сайтовые сценарии вроде FL /validate-captcha). */
    public boolean solveYandexSmartCaptchaWhenRequired(Page page) {
        return solveYandexSmartCaptchaInternal(page, true);
    }

    public Frame findYandexSmartCaptchaFrame(Page page) {
        return findYandexCaptchaFrame(page);
    }

    public boolean isYandexSmartCaptchaFramePassed(Frame frame) {
        return isYandexSmartCaptchaPassed(frame);
    }

    public boolean solveYandexSmartCaptchaViaTwoCaptchaOnPage(Page page) {
        Frame frame = findYandexCaptchaFrame(page);
        Frame tokenFrame = frame != null ? frame : page.mainFrame();
        return solveYandexSmartCaptchaViaTwoCaptcha(page, tokenFrame);
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
                page.url(), siteKey.get(), userAgent, cookies, CURRENT_PROXY.get());
        if (token.isEmpty()) {
            return false;
        }

        String tokenStr = token.get();
        applyYandexSmartCaptchaToken(page, frame, tokenStr);
        waitForYandexSmartCaptchaWidgetAcceptance(page, tokenStr, 6_000);
        if (isYandexSmartCaptchaAcceptedByWidget(page)) {
            dismissSmartCaptchaOverlayIfWidgetAccepted(page);
            logSmartCaptchaResolutionVerdict(page, "after-2captcha-token-ui-passed");
            return true;
        }
        if (isYandexSmartCaptchaPassed(frame)
                && !isYandexKaleidoscopeChallenge(page)
                && !isYandexAdvancedUiVisible(page)) {
            dismissSmartCaptchaOverlayIfWidgetAccepted(page);
            logSmartCaptchaResolutionVerdict(page, "after-2captcha-token-checkbox-ui");
            return true;
        }
        if (isSmartTokenPresentOnPage(page)) {
            log.warn("SmartCaptcha/2Captcha: токен только в DOM (getResponse=0), виджет не принял");
        }
        if (isYouDoPage(page)) {
            ensureYouDoCaptchaChallengeVisible(page);
        }
        logSmartCaptchaResolutionVerdict(page, "after-2captcha-token-unconfirmed");
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
                            const desc = Object.getOwnPropertyDescriptor(
                              window.HTMLInputElement.prototype, 'value');
                            const setter = desc && desc.set;
                            for (const sel of selectors) {
                              const el = root.querySelector(sel);
                              if (el) {
                                if (setter) {
                                  setter.call(el, token);
                                } else {
                                  el.value = token;
                                }
                                el.dispatchEvent(new Event('input', { bubbles: true }));
                                el.dispatchEvent(new Event('change', { bubbles: true }));
                                return true;
                              }
                            }
                            return false;
                          };
                          const roots = [];
                          const dialog = document.querySelector('[role="dialog"]');
                          if (dialog) roots.push(dialog);
                          document.querySelectorAll('[data-sentry-component="SmartCaptcha"], .smart-captcha, [data-sitekey]')
                            .forEach((el) => roots.push(el));
                          roots.push(document);
                          let placed = false;
                          for (const root of roots) {
                            if (setHidden(root)) {
                              placed = true;
                            }
                          }
                          if (!placed) {
                            const login = document.querySelector('input[name="login"]');
                            const form = login ? login.closest('form') : document.querySelector('form');
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
        if (!isYouDoPage(page)) {
            dismissSmartCaptchaOverlay(page);
        }
        resumeYandexSmartCaptchaForForm(page);
        dispatchYandexWidgetSuccess(page, token);
        log.info("SmartCaptcha/2Captcha: token применён на странице, len={}", token != null ? token.length() : 0);
        logSmartCaptchaResolutionVerdict(page, "after-apply-token");
    }

    /**
     * Сообщает React/виджету Yandex об успешной капче (колбэки render/subscribe), не только hidden input.
     *
     * @return число сработавших колбэков (0 — виджет мог не принять внешний токен)
     */
    public int dispatchYandexWidgetSuccess(Page page, String token) {
        if (page == null || page.isClosed()) {
            return 0;
        }
        try {
            String t = token;
            if (t == null || t.length() < 20) {
                t = page.evaluate(
                        """
                                () => {
                                  const el = document.querySelector(
                                    'input[name="smart-token"], input[name="smart_token"]');
                                  return el && el.value && el.value.length > 20 ? el.value : '';
                                }
                                """).toString();
            }
            if (t == null || t.length() < 20) {
                return 0;
            }
            Object raw = page.evaluate(DISPATCH_YANDEX_WIDGET_SUCCESS_JS, t);
            String json = raw != null ? raw.toString() : "{}";
            log.info("SmartCaptcha/YouDo: dispatch widget success → {}", json);
            if (json.contains("\"hits\":")) {
                int idx = json.indexOf("\"hits\":");
                int end = json.indexOf(',', idx);
                if (end < 0) {
                    end = json.indexOf('}', idx);
                }
                try {
                    return Integer.parseInt(json.substring(idx + 7, end).trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha: dispatch widget success: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Единая строка: YouDo/виджет принял капчу ({@code getResponse()>20}, нет overlay/kaleidoscope).
     * Не путать с «2Captcha getTaskResult=ready» — это только ответ API.
     */
    public void logSmartCaptchaResolutionVerdict(Page page, String context) {
        if (page == null || page.isClosed()) {
            log.info("SmartCaptcha ИТОГ [{}] YOUDO_КАПЧА_СНЯТА=НЕТ (страница закрыта)", context);
            return;
        }
        int tokenFieldLen = smartCaptchaTokenLength(page);
        int tokenApiLen = readYandexSmartCaptchaApiResponseLength(page);
        boolean overlay;
        boolean advancedUi;
        boolean kaleidoscope;
        boolean pending;
        try {
            overlay = isSmartCaptchaOverlayVisible(page);
            advancedUi = isYandexAdvancedUiVisible(page);
            kaleidoscope = isYandexKaleidoscopeChallenge(page);
            pending = isSmartCaptchaPending(page);
        } catch (Exception e) {
            log.info(
                    "SmartCaptcha ИТОГ [{}] YOUDO_КАПЧА_СНЯТА=НЕТ | tokenВПоле={} tokenAPI(getResponse)={} | probeError={}",
                    context, tokenFieldLen, tokenApiLen, e.getMessage());
            return;
        }
        boolean solved = tokenApiLen > 20
                && !pending
                && !kaleidoscope
                && !advancedUi
                && !overlay;
        String verdict = solved ? "ДА" : "НЕТ";
        log.info(
                "SmartCaptcha ИТОГ [{}] YOUDO_КАПЧА_СНЯТА={} | tokenВПоле={} tokenAPI(getResponse)={} | pending={} | overlay={} | advancedUi={} | kaleidoscope={} | url={}",
                context,
                verdict,
                tokenFieldLen,
                tokenApiLen,
                pending,
                overlay,
                advancedUi,
                kaleidoscope,
                truncateForLog(page.url(), 80));
    }

    /** Длина ответа {@code window.smartCaptcha.getResponse()} без ошибки «Widget with id … does not exist». */
    private int readYandexSmartCaptchaApiResponseLength(Page page) {
        if (page == null || page.isClosed()) {
            return 0;
        }
        try {
            Object len = page.evaluate(SMART_CAPTCHA_API_RESPONSE_LEN_JS);
            if (len instanceof Number number) {
                return number.intValue();
            }
        } catch (Exception ignored) {
            // page navigated / closed
        }
        return 0;
    }

    /**
     * После внешнего токена (2Captcha): сообщить виджету YouDo/React об успехе и снять блокировку «Далее».
     */
    public void resumeYandexSmartCaptchaForForm(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        dispatchYandexWidgetSuccess(page, null);
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
                                  try {
                                    const r = window.smartCaptcha.getResponse();
                                    if (r && r.length > 20) return r;
                                  } catch (e) { /* виджет ещё не смонтирован */ }
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

    public boolean isSmartTokenPresentOnPage(Page page) {
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
                                try {
                                  const r = window.smartCaptcha.getResponse();
                                  if (r && r.length > 20) return r.length;
                                } catch (e) { /* виджет ещё не смонтирован */ }
                              }
                              return 0;
                            }
                            """);
            if (len instanceof Number number) {
                return number.longValue() > 20;
            }
            return false;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (!msg.contains("Widget with id") && !msg.contains("does not exist")) {
                log.debug("SmartCaptcha: проверка smart-token: {}", msg);
            }
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
