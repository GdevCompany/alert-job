package by.gdev.alert.job.notification.service.ai.parser.impl.flru;

import by.gdev.common.service.playwright.captcha.CaptchaService;
import by.gdev.common.service.playwright.captcha.TwoCaptchaClient;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureCode;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** FL.ru {@code /validate-captcha} (IP-блок, hCaptcha) — только notification / автологин FL. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlRuValidateCaptchaSolver {

    private static final Pattern SITEKEY_QUERY = Pattern.compile("[?&]sitekey=([^&]+)", Pattern.CASE_INSENSITIVE);

    private final CaptchaService captchaService;
    private final TwoCaptchaClient twoCaptchaClient;

    @Value("${captcha.yandex.two-captcha-fallback:true}")
    private boolean yandexTwoCaptchaFallback;

    public void recordFailureForValidateStuck(Page page) {
        captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_STUCK,
                "FL.ru /validate-captcha: страница блокировки не снята после капчи",
                "ensure-stuck-on-page");
    }

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
                    captchaService.isSmartCaptchaOverlayVisible(page));
        } catch (Exception e) {
            log.warn("validate-captcha DBG [{}] snapshot failed: {}", phase, e.getMessage());
        }
    }

    /**
     * FL.ru {@code /validate-captcha} после редиректа (IP-блок).
     * На странице используется <strong>hCaptcha</strong> («Я человек»), не Yandex SmartCaptcha с логина.
     */
    public boolean solveFlRuValidateCaptcha(Page page) {
        captchaService.clearLastFailure();
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
                if (captchaService.solveYandexSmartCaptchaWhenRequired(page)) {
                    captchaService.dismissSmartCaptchaOverlay(page);
                    return true;
                }
            }

            if (clickFlRuValidateHumanWidget(page)) {
                page.waitForTimeout(2000);
                captchaService.dismissSmartCaptchaOverlay(page);
                if (isFlRuValidateCaptchaLikelyPassed(page)) {
                    log.info("validate-captcha: пройдено кликом «Я человек»");
                    return true;
                }
            }

            if (yandexTwoCaptchaFallback && twoCaptchaClient.isConfigured() && captchaService.isYandexAdvancedChallenge(page)) {
                Frame frame = captchaService.findYandexSmartCaptchaFrame(page);
                Frame tokenFrame = frame != null ? frame : page.mainFrame();
                if (captchaService.solveYandexSmartCaptchaViaTwoCaptchaOnPage(page)) {
                    captchaService.dismissSmartCaptchaOverlay(page);
                    log.info("validate-captcha: пройдено через 2Captcha (Yandex advanced)");
                    return true;
                }
            } else if (yandexTwoCaptchaFallback && twoCaptchaClient.isConfigured()) {
                log.info("validate-captcha: Yandex без advanced — 2Captcha не вызываем");
            }

            log.warn("validate-captcha: не удалось пройти IP-капчу");
            logValidateCaptchaState(page, "99-fail-no-hcaptcha-path");
            captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.FLRU_VALIDATE_UNKNOWN,
                    "FL.ru /validate-captcha: не удалось пройти IP-капчу (hCaptcha/Yandex)",
                    "99-fail-no-hcaptcha-path");
            return false;
        } catch (Exception e) {
            log.error("validate-captcha: ошибка", e);
            logValidateCaptchaState(page, "99-exception");
            captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.CAPTCHA_EXCEPTION,
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
            if (captchaService.isSmartCaptchaOverlayVisible(page) || captchaService.findYandexSmartCaptchaFrame(page) != null) {
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
            captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_TWO_CAPTCHA,
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

        if (captchaService.isSmartCaptchaOverlayVisible(page) || captchaService.findYandexSmartCaptchaFrame(page) != null) {
            log.warn("validate-captcha: открыт visual challenge (картинки) — локально не решается, нужен 2Captcha");
            captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_VISUAL,
                    "FL.ru /validate-captcha: hCaptcha visual puzzle — без токена 2Captcha не пройти",
                    "29-hcaptcha-visual");
        } else if (isHCaptchaResponsePresent(page)) {
            captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_STUCK,
                    "FL.ru /validate-captcha: hCaptcha — ответ есть, но сайт не снял блокировку",
                    "29-hcaptcha-stuck");
        } else {
            captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.FLRU_VALIDATE_HCAPTCHA_VISUAL,
                    "FL.ru /validate-captcha: hCaptcha «подозрительная активность» не пройдена",
                    "29-hcaptcha-fail");
        }
        log.warn("validate-captcha: hCaptcha не пройдена");
        logValidateCaptchaState(page, "29-hcaptcha-fail");
        return false;
    }

    /** Картинки «выберите всё, что…» — Yandex SmartCaptcha overlay; решается только токеном 2Captcha. */
    private boolean trySolveValidateCaptchaVisualOverlay(Page page) {
        if (!captchaService.isSmartCaptchaOverlayVisible(page) && captchaService.findYandexSmartCaptchaFrame(page) == null) {
            return false;
        }
        if (!twoCaptchaClient.isConfigured()) {
            log.warn("validate-captcha: visual challenge без TWO_CAPTCHA_ENABLED");
            return false;
        }
        if (!captchaService.solveYandexSmartCaptchaViaTwoCaptchaOnPage(page)) {
            return false;
        }
        captchaService.dismissSmartCaptchaOverlay(page);
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
        if (captchaService.findYandexSmartCaptchaFrame(page) != null) {
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
                Frame frame = captchaService.findYandexSmartCaptchaFrame(page);
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
        if (captchaService.isSmartTokenPresentOnPage(page)) {
            return true;
        }
        try {
            Locator label = page.locator("#label:has-text('Я человек')");
            if (label.count() > 0 && label.first().isVisible()) {
                return false;
            }
            Frame frame = captchaService.findYandexSmartCaptchaFrame(page);
            if (frame != null && captchaService.isYandexSmartCaptchaFramePassed(frame)) {
                return true;
            }
        } catch (Exception e) {
            log.debug("validate-captcha: проверка результата: {}", e.getMessage());
        }
        return !captchaService.isSmartCaptchaOverlayVisible(page);
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
}
