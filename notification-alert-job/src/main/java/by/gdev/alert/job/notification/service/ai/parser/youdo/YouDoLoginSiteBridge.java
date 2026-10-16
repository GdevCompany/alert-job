package by.gdev.alert.job.notification.service.ai.parser.youdo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Связка внешнего smart-token (2Captcha) с формой логина YouDo в React, без обхода логики сайта.
 */
@Slf4j
@Component
public class YouDoLoginSiteBridge {

    private static final Gson GSON = new Gson();

    /** Последний снимок primary-кнопки «Далее» по странице (для diff в логе). */
    private final Map<Page, JsonObject> lastNextPrimaryByPage = new ConcurrentHashMap<>();

    private static final String COMMIT_TOKEN_JS = """
            (token) => {
              const findLoginScope = () => {
                const login = document.querySelector('input[name="login"]');
                if (!login) {
                  const dialog = document.querySelector('[role="dialog"]');
                  return dialog || document;
                }
                return login.closest('form')
                  || login.closest('[data-sentry-component="SmartCaptcha"]')?.parentElement
                  || login.closest('[class*="Modal"]')
                  || login.closest('[class*="Login"]')
                  || document.querySelector('[role="dialog"]')
                  || document;
              };
              const scope = findLoginScope();
              const setReactValue = (el, value) => {
                if (!el) return false;
                const desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                const setter = desc && desc.set;
                if (setter) {
                  setter.call(el, value);
                } else {
                  el.value = value;
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return true;
              };
              const invokeCaptchaReactHandlers = (dom, t) => {
                if (!dom) return false;
                const handlerNames = ['onSuccess', 'onCaptchaSuccess', 'onVerify', 'onToken', 'callback'];
                let hit = false;
                const propKey = Object.keys(dom).find((k) => k.startsWith('__reactProps$'));
                if (propKey && dom[propKey]) {
                  const props = dom[propKey];
                  for (const name of handlerNames) {
                    if (typeof props[name] === 'function') {
                      try { props[name](t); hit = true; } catch (e) { /* ignore */ }
                    }
                  }
                }
                const fiberKey = Object.keys(dom).find((k) => k.startsWith('__reactFiber$'));
                if (fiberKey) {
                  let fiber = dom[fiberKey];
                  for (let i = 0; i < 90 && fiber; i++) {
                    const props = fiber.memoizedProps || fiber.pendingProps;
                    if (props) {
                      for (const name of handlerNames) {
                        if (typeof props[name] === 'function') {
                          try {
                            props[name](t);
                            hit = true;
                          } catch (e) { /* ignore */ }
                        }
                      }
                    }
                    fiber = fiber.return;
                  }
                }
                return hit;
              };
              const findOrCreateTokenInput = () => {
                const names = ['smart-token', 'smart_token'];
                for (const name of names) {
                  const el = scope.querySelector(`input[name="${name}"]`)
                    || document.querySelector(`input[name="${name}"]`);
                  if (el) return el;
                }
                const login = document.querySelector('input[name="login"]');
                const form = login ? login.closest('form') : scope.querySelector('form');
                if (!form) return null;
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = 'smart-token';
                form.appendChild(input);
                return input;
              };
              const tokenInput = findOrCreateTokenInput();
              if (tokenInput) {
                setReactValue(tokenInput, token);
              }
              let reactHooks = 0;
              let hosts = scope.querySelectorAll(
                '[data-sentry-component="SmartCaptcha"], [data-sitekey], .smart-captcha, [data-testid="smartCaptcha-container"]'
              );
              if (hosts.length === 0) {
                hosts = document.querySelectorAll(
                  '[data-sentry-component="SmartCaptcha"], [data-testid="smartCaptcha-container"]');
              }
              hosts.forEach((host) => {
                setReactValue(host.querySelector('input[name="smart-token"], input[name="smart_token"]'), token);
                if (invokeCaptchaReactHandlers(host, token)) reactHooks++;
                if (invokeCaptchaReactHandlers(host.parentElement, token)) reactHooks++;
                const cb = host.getAttribute('data-callback');
                if (cb && typeof window[cb] === 'function') {
                  try { window[cb](token); reactHooks++; } catch (e) { /* ignore */ }
                }
              });
              const payloads = [
                { event: 'smart-captcha', token },
                { methodCall: 'success', token },
                { type: 'smart-captcha', token },
                { method: 'captcha-success', token },
                { status: 'success', token },
                { event: 'challenge-complete', token }
              ];
              for (const p of payloads) {
                window.postMessage(JSON.stringify(p), '*');
              }
              document.querySelectorAll('iframe[src*="smartcaptcha"]').forEach((iframe) => {
                for (const p of payloads) {
                  try { iframe.contentWindow.postMessage(JSON.stringify(p), '*'); } catch (e) { /* cross-origin */ }
                }
              });
              window.dispatchEvent(new CustomEvent('smart-captcha-token', { detail: token }));
              let apiLen = 0;
              if (window.smartCaptcha && typeof window.smartCaptcha.getResponse === 'function') {
                try {
                  const r = window.smartCaptcha.getResponse();
                  apiLen = r && r.length > 20 ? r.length : 0;
                } catch (e) {
                  const ids = window.smartCaptcha._widgetIds || window.smartCaptcha._widgets;
                  if (ids && typeof ids === 'object') {
                    for (const id of Object.values(ids)) {
                      try {
                        const r = window.smartCaptcha.getResponse(id);
                        if (r && r.length > 20) apiLen = r.length;
                      } catch (err) { /* next id */ }
                    }
                  }
                }
              }
              const nextBtn = [...scope.querySelectorAll('button')]
                .find((b) => b.offsetParent !== null && (b.textContent || '').includes('Далее'));
              return JSON.stringify({
                tokenInScope: tokenInput ? (tokenInput.value || '').length : 0,
                tokenApiLen: apiLen,
                reactHooks,
                nextFound: !!nextBtn,
                nextDisabled: nextBtn ? nextBtn.disabled : null,
                nextAriaBusy: nextBtn ? nextBtn.getAttribute('aria-busy') : null
              });
            }
            """;

    private static final String PROBE_JS = """
            () => {
              const login = document.querySelector('input[name="login"]');
              const dialog = document.querySelector('[role="dialog"]');
              const scope = login
                ? (login.closest('form') || login.closest('[class*="Modal"]') || dialog || document)
                : (dialog || document);
              const code = scope.querySelector('input[name="code"]')
                || document.querySelector('input[name="code"]');
              const token = document.querySelector('input[name="smart-token"], input[name="smart_token"]')
                || scope.querySelector('input[name="smart-token"], input[name="smart_token"]');
              const nextBtn = [...document.querySelectorAll('button')]
                .find((b) => b.offsetParent !== null && (b.textContent || '').includes('Далее'));
              const captchaHost = document.querySelector('[data-sentry-component="SmartCaptcha"]')
                || scope.querySelector('[data-sentry-component="SmartCaptcha"]');
              const tokenVal = token ? (token.value || '') : '';
              const fp = tokenVal.length >= 12
                ? tokenVal.length + '@' + tokenVal.slice(0, 6) + '…' + tokenVal.slice(-6)
                : String(tokenVal.length);
              const loginVal = login ? (login.value || '') : '';
              const loginCorrupt = loginVal.length > 0
                && (!loginVal.includes('@') || loginVal.length > 120);
              const nextCls = nextBtn ? String(nextBtn.className || '') : '';
              const nextLoader = nextBtn
                ? !!nextBtn.querySelector('[class*="Loader"], [class*="loader"], [class*="Spinner"], [class*="spinner"]')
                : null;
              return JSON.stringify({
                dialogOpen: !!dialog,
                emailVisible: !!(login && login.offsetParent !== null),
                loginLen: loginVal.length,
                loginCorrupt,
                otpVisible: !!(code && code.offsetParent !== null),
                tokenLen: tokenVal.length,
                tokenFingerprint: fp,
                nextFound: !!nextBtn,
                nextDisabled: nextBtn ? nextBtn.disabled : null,
                nextAriaBusy: nextBtn ? nextBtn.getAttribute('aria-busy') : null,
                nextIsLoadingClass: nextBtn ? (nextCls.includes('isLoading') || nextCls.includes('IsLoading')) : null,
                nextLoaderInDom: nextLoader,
                nextChildCount: nextBtn ? nextBtn.childElementCount : null,
                nextTextVisible: nextBtn ? String(nextBtn.innerText || '').includes('Далее') : null,
                smartCaptchaInDialog: !!captchaHost,
                smartCaptchaApiLen: (() => {
                  try {
                    if (!window.smartCaptcha || typeof window.smartCaptcha.getResponse !== 'function') return 0;
                    const r = window.smartCaptcha.getResponse();
                    return r && r.length > 20 ? r.length : 0;
                  } catch (e) { return 0; }
                })()
              });
            }
            """;

    private static final String NEXT_BUTTON_MARKUP_JS = """
            () => {
              const login = document.querySelector('input[name="login"]');
              const form = login ? login.closest('form') : null;
              const dialog = document.querySelector('[role="dialog"]');
              const describe = (b, idx) => {
                const text = (b.textContent || '').replace(/\\s+/g, ' ').trim();
                const inner = (b.innerText || '').replace(/\\s+/g, ' ').trim();
                const rect = b.getBoundingClientRect();
                const visible = !!(b.offsetParent !== null && rect.width > 2 && rect.height > 2);
                const children = [...b.children].slice(0, 8).map((c) => ({
                  tag: c.tagName,
                  class: String(c.className || '').slice(0, 100),
                  text: String(c.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 50),
                  hidden: c.offsetParent === null
                }));
                return {
                  idx,
                  visible,
                  inForm: !!(form && form.contains(b)),
                  inDialog: !!(dialog && dialog.contains(b)),
                  matchDalee: text.includes('Далее') || inner.includes('Далее'),
                  disabled: b.disabled,
                  ariaBusy: b.getAttribute('aria-busy'),
                  ariaDisabled: b.getAttribute('aria-disabled'),
                  type: b.type || '',
                  dataTest: b.getAttribute('data-test'),
                  className: String(b.className || '').slice(0, 160),
                  textContent: text.slice(0, 80),
                  innerText: inner.slice(0, 80),
                  textVisible: inner.includes('Далее'),
                  htmlSnippet: String(b.innerHTML || '').replace(/\\s+/g, ' ').slice(0, 320),
                  childCount: b.childElementCount,
                  svgCount: b.querySelectorAll('svg').length,
                  loaderLike: !!b.querySelector(
                    '[class*="Loader"], [class*="loader"], [class*="Spinner"], [class*="spinner"]'),
                  onlySpinnerUi: !inner.includes('Далее')
                    && b.querySelectorAll('svg, [class*="Loader"], [class*="Spinner"]').length > 0
                };
              };
              const buttons = [...document.querySelectorAll('button')];
              const candidates = buttons
                .map(describe)
                .filter((c) => c.matchDalee || c.inForm || (c.className && c.className.includes('NewButton')));
              const primary = candidates.find((c) => c.visible && c.matchDalee)
                || candidates.find((c) => c.visible && c.inForm)
                || candidates.find((c) => c.visible);
              return JSON.stringify({
                formFound: !!form,
                dialogOpen: !!dialog,
                loginVisible: !!(login && login.offsetParent !== null),
                candidateCount: candidates.length,
                primary,
                candidates: candidates.slice(0, 6)
              });
            }
            """;

    /** Снимок DOM кнопки «Далее» (текст vs спиннер, классы, data-test). */
    public void logNextButtonMarkup(Page page, String phase) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            Object raw = page.evaluate(NEXT_BUTTON_MARKUP_JS);
            String json = raw != null ? raw.toString() : "{}";
            log.info("YOUDO «Далее» markup [{}]: {}", phase, json);
            logNextButtonDiff(page, phase, json);
            log.info("YOUDO «Далее» probe [{}]: {}", phase, probeLoginDialog(page));
        } catch (Exception e) {
            log.warn("YOUDO «Далее» markup [{}]: evaluate failed: {}", phase, e.getMessage());
        }
    }

    /** Сброс diff (новый цикл логина на той же вкладке). */
    public void resetNextButtonMarkupBaseline(Page page) {
        if (page != null) {
            lastNextPrimaryByPage.remove(page);
        }
    }

    private void logNextButtonDiff(Page page, String phase, String markupJson) {
        try {
            JsonObject root = GSON.fromJson(markupJson, JsonObject.class);
            if (root == null || !root.has("primary") || root.get("primary").isJsonNull()) {
                log.info("YOUDO «Далее» diff [{}]: primary-кнопка не найдена", phase);
                return;
            }
            JsonObject primary = root.getAsJsonObject("primary");
            JsonObject prev = lastNextPrimaryByPage.get(page);
            if (prev == null) {
                log.info("YOUDO «Далее» diff [{}]: базовый снимок (первая фиксация primary)", phase);
            } else {
                StringBuilder changes = new StringBuilder();
                appendFieldDiff(changes, "disabled", prev, primary);
                appendFieldDiff(changes, "ariaBusy", prev, primary);
                appendFieldDiff(changes, "loaderLike", prev, primary);
                appendFieldDiff(changes, "onlySpinnerUi", prev, primary);
                appendFieldDiff(changes, "textVisible", prev, primary);
                appendFieldDiff(changes, "childCount", prev, primary);
                appendFieldDiff(changes, "svgCount", prev, primary);
                appendFieldDiff(changes, "className", prev, primary);
                appendFieldDiff(changes, "htmlSnippet", prev, primary);
                if (changes.isEmpty()) {
                    log.info("YOUDO «Далее» diff [{}]: DOM primary без изменений с прошлого снимка", phase);
                } else {
                    log.info("YOUDO «Далее» diff [{}]: {}", phase, changes.toString().trim());
                }
            }
            lastNextPrimaryByPage.put(page, primary.deepCopy());
        } catch (Exception e) {
            log.debug("YOUDO «Далее» diff [{}]: parse failed: {}", phase, e.getMessage());
        }
    }

    private static void appendFieldDiff(StringBuilder out, String field, JsonObject prev, JsonObject now) {
        String was = prev.has(field) && !prev.get(field).isJsonNull() ? prev.get(field).toString() : "null";
        String cur = now.has(field) && !now.get(field).isJsonNull() ? now.get(field).toString() : "null";
        if (!was.equals(cur)) {
            if (!out.isEmpty()) {
                out.append(" | ");
            }
            out.append(field).append(':').append(was).append("→").append(cur);
        }
    }

    /**
     * Пока крутится спиннер — логируем только если разметка/состояние кнопки реально изменилось.
     */
    public void watchNextButtonWhileLoading(Page page, String phasePrefix, long timeoutMs) {
        if (page == null || page.isClosed()) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        int tick = 0;
        while (System.currentTimeMillis() < deadline && !page.isClosed()) {
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
                                  return cls.includes('isLoading') || cls.includes('IsLoading') || !!loader;
                                }
                                """);
                if (!Boolean.TRUE.equals(loading)) {
                    logNextButtonMarkup(page, phasePrefix + "-loading-done");
                    return;
                }
            } catch (Exception ignored) {
                return;
            }
            tick++;
            if (tick % 8 == 0) {
                logNextButtonMarkup(page, phasePrefix + "-loading-tick-" + tick);
            }
            page.waitForTimeout(450);
        }
        logNextButtonMarkup(page, phasePrefix + "-loading-timeout");
    }

    /**
     * Восстанавливает email в {@code input[name=login]} (после commit токена React иногда подставлял smart-token).
     */
    public void ensureLoginEmail(Page page, String expectedEmail) {
        if (page == null || page.isClosed() || expectedEmail == null || expectedEmail.isBlank()) {
            return;
        }
        try {
            Object raw = page.evaluate(
                    """
                            (expected) => {
                              const login = document.querySelector('input[name="login"]');
                              if (!login || login.offsetParent === null) {
                                return JSON.stringify({ ok: false, reason: 'no-visible-login' });
                              }
                              const cur = login.value || '';
                              const emailOk = /^[^\\s@]+@[^\\s@]+$/.test(cur) && cur === expected;
                              if (emailOk) {
                                return JSON.stringify({ ok: true, action: 'unchanged', loginLen: cur.length });
                              }
                              const desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                              const setter = desc && desc.set;
                              if (setter) {
                                setter.call(login, expected);
                              } else {
                                login.value = expected;
                              }
                              login.dispatchEvent(new Event('input', { bubbles: true }));
                              login.dispatchEvent(new Event('change', { bubbles: true }));
                              const corrupt = cur.length > 40 && !cur.includes('@');
                              return JSON.stringify({
                                ok: true,
                                action: 'fixed',
                                wasLen: cur.length,
                                loginLen: expected.length,
                                wasCorrupt: corrupt || (cur.length > 0 && cur !== expected)
                              });
                            }
                            """,
                    expectedEmail);
            log.info("YOUDO site-bridge: ensure login email → {}", raw);
        } catch (Exception e) {
            log.warn("YOUDO site-bridge: ensure login email failed: {}", e.getMessage());
        }
    }

    /**
     * Передаёт токен в форму модалки так, чтобы React увидел {@code input} (native setter + events).
     */
    public void commitExternalSmartToken(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            Object tokenObj = page.evaluate(
                    """
                            () => {
                              const el = document.querySelector(
                                'input[name="smart-token"], input[name="smart_token"]');
                              return el && el.value && el.value.length > 20 ? el.value : '';
                            }
                            """);
            String token = tokenObj != null ? tokenObj.toString() : "";
            if (token.length() < 20) {
                log.warn("YOUDO site-bridge: нет токена в модалке для commit");
                return;
            }
            Object result = page.evaluate(COMMIT_TOKEN_JS, token);
            log.info("YOUDO site-bridge: commit token → {}", result);
        } catch (Exception e) {
            log.warn("YOUDO site-bridge: commit token failed: {}", e.getMessage());
        }
    }

    public String probeLoginDialog(Page page) {
        if (page == null || page.isClosed()) {
            return "{}";
        }
        try {
            Object raw = page.evaluate(PROBE_JS);
            return raw != null ? raw.toString() : "{}";
        } catch (Exception e) {
            return "{\"probeError\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * Ждём, пока сайт сам разрешит «Далее» (не снимаем disabled вручную).
     */
    public boolean waitForSiteEnabledNext(Page page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (page.isClosed()) {
                return false;
            }
            try {
                Object ok = page.evaluate(
                        """
                                () => {
                                  const dialog = document.querySelector('[role="dialog"]');
                                  const scope = dialog || document;
                                  const code = scope.querySelector('input[name="code"]');
                                  if (code && code.offsetParent !== null) return true;
                                  const token = document.querySelector(
                                    'input[name="smart-token"], input[name="smart_token"]');
                                  if (!token || (token.value || '').length < 20) return false;
                                  const nextBtn = [...document.querySelectorAll('button')]
                                    .find((b) => b.offsetParent !== null && (b.textContent || '').includes('Далее'));
                                  if (!nextBtn) return false;
                                  if (nextBtn.disabled || nextBtn.getAttribute('aria-busy') === 'true') return false;
                                  const cls = String(nextBtn.className || '');
                                  if (cls.includes('isLoading') || cls.includes('IsLoading')) return false;
                                  if (nextBtn.querySelector('[class*="loader"], [class*="Loader"]')) return false;
                                  const login = document.querySelector('input[name="login"]');
                                  const lv = login ? (login.value || '') : '';
                                  if (lv.length > 40 && !lv.includes('@')) return false;
                                  return true;
                                }
                                """);
                if (Boolean.TRUE.equals(ok)) {
                    log.info("YOUDO site-bridge: «Далее» разрешена сайтом (React)");
                    return true;
                }
            } catch (Exception ignored) {
                // retry
            }
            page.waitForTimeout(350);
        }
        logNextButtonMarkup(page, "wait-enabled-next-timeout");
        log.info("YOUDO site-bridge: «Далее» так и не разрешена сайтом, probe={}", probeLoginDialog(page));
        return false;
    }

    /** YouDo иногда сам переходит на OTP после успеха капчи. */
    public boolean waitForOtpStepInDialog(Page page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (page.isClosed()) {
                return false;
            }
            try {
                Object ok = page.evaluate(
                        """
                                () => {
                                  const dialog = document.querySelector('[role="dialog"]') || document;
                                  const code = dialog.querySelector('input[name="code"]');
                                  if (code && code.offsetParent !== null) return true;
                                  const text = (dialog.innerText || '').toLowerCase();
                                  return text.includes('код из письма') || text.includes('введите код');
                                }
                                """);
                if (Boolean.TRUE.equals(ok)) {
                    return true;
                }
            } catch (Exception ignored) {
                // retry
            }
            page.waitForTimeout(350);
        }
        return false;
    }
}
