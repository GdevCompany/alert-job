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
              const CAPTCHA_HANDLER_NAMES = [
                'onSuccess', 'onCaptchaSuccess', 'onVerify', 'onToken', 'callback',
                'onSmartCaptchaSuccess', 'handleCaptchaSuccess', 'setCaptchaToken', 'setSmartToken',
                'setToken', 'onChallengeHidden', 'onChallengeSuccess', 'onCaptchaToken'
              ];
              const LOADING_HANDLER_NAMES = [
                'setLoading', 'setIsLoading', 'setSubmitting', 'setIsSubmitting', 'setPending'
              ];
              const walkReactFiber = (dom, visitor) => {
                if (!dom) return;
                const fiberKey = Object.keys(dom).find((k) => k.startsWith('__reactFiber$'));
                if (!fiberKey) return;
                let fiber = dom[fiberKey];
                for (let i = 0; i < 120 && fiber; i++) {
                  visitor(fiber);
                  fiber = fiber.return;
                }
              };
              const PROP_NAME_HINT = /captcha|smart|token|verify|challenge/i;
              const invokeCaptchaReactHandlers = (dom, t) => {
                if (!dom) return false;
                let hit = 0;
                const tryProps = (props) => {
                  if (!props || typeof props !== 'object') return;
                  for (const [name, fn] of Object.entries(props)) {
                    if (typeof fn !== 'function') continue;
                    if (CAPTCHA_HANDLER_NAMES.includes(name) || PROP_NAME_HINT.test(name)) {
                      try { fn(t); hit++; } catch (e) { /* ignore */ }
                    }
                  }
                };
                const propKey = Object.keys(dom).find((k) => k.startsWith('__reactProps$'));
                if (propKey && dom[propKey]) tryProps(dom[propKey]);
                walkReactFiber(dom, (fiber) => {
                  tryProps(fiber.memoizedProps);
                  tryProps(fiber.pendingProps);
                  const typeName = fiber.type && (fiber.type.name || fiber.type.displayName
                    || (fiber.elementType && (fiber.elementType.name || fiber.elementType.displayName)));
                  if (typeName && /SignIn|Captcha|Smart/i.test(String(typeName))) {
                    tryProps(fiber.memoizedProps);
                  }
                });
                return hit > 0;
              };
              const invokeLoadingReset = (dom) => {
                if (!dom) return false;
                let hit = false;
                walkReactFiber(dom, (fiber) => {
                  const props = fiber.memoizedProps || fiber.pendingProps;
                  if (!props) return;
                  for (const name of LOADING_HANDLER_NAMES) {
                    if (typeof props[name] === 'function') {
                      try { props[name](false); hit = true; } catch (e) { /* ignore */ }
                    }
                  }
                });
                return hit;
              };
              const invokeFiberTokenHandlers = (t) => {
                const login = document.querySelector('input[name="login"]');
                if (!login) return 0;
                let hits = 0;
                walkReactFiber(login, (fiber) => {
                  const props = fiber.memoizedProps || fiber.pendingProps;
                  if (!props) return;
                  for (const [name, fn] of Object.entries(props)) {
                    if (typeof fn !== 'function') continue;
                    if (!/captcha|smart|token|verify|challenge/i.test(name)) continue;
                    try { fn(t); hits++; } catch (e) { /* ignore */ }
                  }
                  const typeName = fiber.type && (fiber.type.name || fiber.type.displayName
                    || (fiber.elementType && (fiber.elementType.name || fiber.elementType.displayName)));
                  if (typeName && /SignIn|Email|Captcha|Login/i.test(String(typeName))) {
                    for (const name of CAPTCHA_HANDLER_NAMES) {
                      if (typeof props[name] === 'function') {
                        try { props[name](t); hits++; } catch (e) { /* ignore */ }
                      }
                    }
                  }
                });
                return hits;
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
              const loginEl = document.querySelector('input[name="login"]');
              if (loginEl) {
                if (invokeCaptchaReactHandlers(loginEl, token)) reactHooks++;
                reactHooks += invokeFiberTokenHandlers(token);
                invokeLoadingReset(loginEl);
              }
              reactHooks += invokeFiberTokenHandlers(token);
              const fiberTypeName = (fiber) => {
                if (!fiber || !fiber.type) return '';
                const t = fiber.type;
                return String(t.displayName || t.name
                  || (fiber.elementType && (fiber.elementType.displayName || fiber.elementType.name)) || '');
              };
              const invokeSignInCallbacks = (t) => {
                const login = document.querySelector('input[name="login"]');
                if (!login) return 0;
                let hits = 0;
                walkReactFiber(login, (fiber) => {
                  const tn = fiberTypeName(fiber);
                  if (!/SignIn|SmartCaptcha|Captcha|Email/i.test(tn)) return;
                  const props = fiber.memoizedProps || fiber.pendingProps || {};
                  for (const [name, fn] of Object.entries(props)) {
                    if (typeof fn !== 'function') continue;
                    if (/captcha|token|success|verify|challenge|smart|submit/i.test(name)) {
                      try { fn(t); hits++; } catch (e) {
                        try { fn(); hits++; } catch (e2) { /* ignore */ }
                      }
                    }
                  }
                });
                return hits;
              };
              reactHooks += invokeSignInCallbacks(token);
              if (window.smartCaptcha && typeof window.smartCaptcha.subscribe === 'function') {
                try {
                  const ids = window.smartCaptcha._widgetIdMap || window.smartCaptcha._widgetIds
                    || window.smartCaptcha._widgets;
                  if (ids && typeof ids === 'object') {
                    for (const id of Object.values(ids)) {
                      try {
                        window.smartCaptcha.subscribe(id, () => {});
                      } catch (e) { /* ignore */ }
                    }
                  }
                } catch (e) { /* ignore */ }
              }
              const nextEl = [...document.querySelectorAll('button')]
                .find((b) => b.offsetParent !== null && (b.textContent || '').includes('Далее'));
              if (nextEl) {
                if (invokeCaptchaReactHandlers(nextEl, token)) reactHooks++;
                invokeLoadingReset(nextEl);
              }
              let loadingFiberResets = 0;
              for (const b of document.querySelectorAll('button')) {
                if (b.offsetParent === null) continue;
                if (!(b.textContent || '').includes('Далее')) continue;
                if (invokeLoadingReset(b)) loadingFiberResets++;
              }
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
              if (window.smartCaptcha) {
                try {
                  if (typeof window.smartCaptcha.setResponse === 'function') {
                    window.smartCaptcha.setResponse(token);
                  }
                  const ids = window.smartCaptcha._widgetIdMap || window.smartCaptcha._widgetIds
                    || window.smartCaptcha._widgets;
                  if (ids && typeof ids === 'object' && typeof window.smartCaptcha.setResponse === 'function') {
                    for (const id of Object.values(ids)) {
                      try { window.smartCaptcha.setResponse(id, token); } catch (e) { /* ignore */ }
                    }
                  }
                } catch (e) { /* ignore */ }
              }
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
                loadingFiberResets,
                nextFound: !!nextBtn,
                nextDisabled: nextBtn ? nextBtn.disabled : null,
                nextAriaBusy: nextBtn ? nextBtn.getAttribute('aria-busy') : null
              });
            }
            """;

    private static final String CLICK_NEXT_REACT_JS = """
            () => {
              const btn = [...document.querySelectorAll('button')]
                .find((b) => b.offsetParent !== null && (b.textContent || '').includes('Далее'));
              if (!btn) return JSON.stringify({ ok: false, reason: 'no-next' });
              const ev = {
                preventDefault() {},
                stopPropagation() {},
                target: btn,
                currentTarget: btn,
                type: 'click',
                bubbles: true,
                cancelable: true,
                nativeEvent: new MouseEvent('click', { bubbles: true, cancelable: true })
              };
              const propKey = Object.keys(btn).find((k) => k.startsWith('__reactProps$'));
              let reactClick = false;
              if (propKey && btn[propKey]) {
                const props = btn[propKey];
                for (const name of ['onClick', 'onPointerUp', 'onMouseUp']) {
                  if (typeof props[name] === 'function') {
                    try { props[name](ev); reactClick = true; } catch (e) { /* ignore */ }
                  }
                }
              }
              const fiberKey = Object.keys(btn).find((k) => k.startsWith('__reactFiber$'));
              if (fiberKey) {
                let fiber = btn[fiberKey];
                for (let i = 0; i < 40 && fiber; i++) {
                  const props = fiber.memoizedProps || fiber.pendingProps;
                  if (props && typeof props.onClick === 'function') {
                    try { props.onClick(ev); reactClick = true; } catch (e) { /* ignore */ }
                  }
                  fiber = fiber.return;
                }
              }
              return JSON.stringify({ ok: reactClick, reactClick });
            }
            """;

    private static final String CLICK_BACK_REACT_JS = """
            () => {
              const btn = [...document.querySelectorAll('button')]
                .find((b) => b.offsetParent !== null && (b.textContent || '').includes('Назад'));
              if (!btn) return JSON.stringify({ ok: false, reason: 'no-back' });
              const ev = {
                preventDefault() {},
                stopPropagation() {},
                target: btn,
                currentTarget: btn,
                type: 'click',
                bubbles: true,
                cancelable: true,
                nativeEvent: new MouseEvent('click', { bubbles: true, cancelable: true })
              };
              const propKey = Object.keys(btn).find((k) => k.startsWith('__reactProps$'));
              let reactClick = false;
              if (propKey && btn[propKey]) {
                const props = btn[propKey];
                for (const name of ['onClick', 'onPointerUp', 'onMouseUp']) {
                  if (typeof props[name] === 'function') {
                    try { props[name](ev); reactClick = true; } catch (e) { /* ignore */ }
                  }
                }
              }
              const fiberKey = Object.keys(btn).find((k) => k.startsWith('__reactFiber$'));
              if (fiberKey) {
                let fiber = btn[fiberKey];
                for (let i = 0; i < 40 && fiber; i++) {
                  const props = fiber.memoizedProps || fiber.pendingProps;
                  if (props && typeof props.onClick === 'function') {
                    try { props.onClick(ev); reactClick = true; } catch (e) { /* ignore */ }
                  }
                  fiber = fiber.return;
                }
              }
              if (!reactClick) {
                try { btn.click(); reactClick = true; } catch (e) { /* ignore */ }
              }
              return JSON.stringify({ ok: reactClick, reactClick });
            }
            """;

    private static final String RESET_STUCK_LOADING_JS = """
            () => {
              const LOADING_HANDLER_NAMES = [
                'setLoading', 'setIsLoading', 'setSubmitting', 'setIsSubmitting', 'setPending'
              ];
              const fiberTypeName = (fiber) => {
                if (!fiber || !fiber.type) return '';
                const t = fiber.type;
                return String(t.displayName || t.name || (fiber.elementType && (fiber.elementType.displayName || fiber.elementType.name)) || '');
              };
              const walkReactFiber = (dom, visitor) => {
                if (!dom) return;
                const fiberKey = Object.keys(dom).find((k) => k.startsWith('__reactFiber$'));
                if (!fiberKey) return;
                let fiber = dom[fiberKey];
                for (let i = 0; i < 160 && fiber; i++) {
                  visitor(fiber);
                  fiber = fiber.return;
                }
              };
              const resetHookLoadingStates = (fiber) => {
                let hits = 0;
                const name = fiberTypeName(fiber);
                if (!/SignIn|Email|Login|Captcha/i.test(name)) return 0;
                let hook = fiber.memoizedState;
                while (hook) {
                  if (hook.queue && typeof hook.queue.dispatch === 'function' && hook.memoizedState === true) {
                    try { hook.queue.dispatch(false); hits++; } catch (e) { /* ignore */ }
                  }
                  hook = hook.next;
                }
                return hits;
              };
              const invokeLoadingReset = (dom) => {
                if (!dom) return { propHits: 0, hookHits: 0 };
                let propHits = 0;
                let hookHits = 0;
                walkReactFiber(dom, (fiber) => {
                  hookHits += resetHookLoadingStates(fiber);
                  const props = fiber.memoizedProps || fiber.pendingProps;
                  if (!props) return;
                  for (const name of LOADING_HANDLER_NAMES) {
                    if (typeof props[name] === 'function') {
                      try { props[name](false); propHits++; } catch (e) { /* ignore */ }
                    }
                  }
                });
                return { propHits, hookHits };
              };
              let propHits = 0;
              let hookHits = 0;
              const login = document.querySelector('input[name="login"]');
              if (login) {
                const r = invokeLoadingReset(login);
                propHits += r.propHits;
                hookHits += r.hookHits;
              }
              for (const b of document.querySelectorAll('button')) {
                if (b.offsetParent === null) continue;
                const text = (b.textContent || '').trim();
                if (!text.includes('Далее')) continue;
                const r = invokeLoadingReset(b);
                propHits += r.propHits;
                hookHits += r.hookHits;
              }
              return JSON.stringify({ propHits, hookHits });
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

    /**
     * Сбрасывает зависший спиннер «Далее» после неудачного submit до капчи (внешний smart-token уже в DOM).
     */
    /**
     * Клик «Далее» через React onClick (DOM-клик иногда не доходит до обработчика после внешнего smart-token).
     */
    public boolean clickNextViaReact(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            Object raw = page.evaluate(CLICK_NEXT_REACT_JS);
            log.info("YOUDO site-bridge: React click «Далее» → {}", raw);
            if (raw == null) {
                return false;
            }
            String s = raw.toString();
            return s.contains("\"reactClick\":true") || s.contains("\"ok\":true");
        } catch (Exception e) {
            log.warn("YOUDO site-bridge: React click «Далее» failed: {}", e.getMessage());
            return false;
        }
    }

    public void resetStuckEmailSubmitUi(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            Object raw = page.evaluate(RESET_STUCK_LOADING_JS);
            log.info("YOUDO site-bridge: reset stuck loading → {}", raw);
        } catch (Exception e) {
            log.debug("YOUDO site-bridge: reset stuck loading failed: {}", e.getMessage());
        }
    }

    /**
     * После внешнего smart-token: сообщить React о капче, снять зависший loading, нажать «Далее» через onClick.
     */
    public boolean completeEmailSubmitAfterExternalToken(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        commitExternalSmartToken(page);
        resetStuckEmailSubmitUi(page);
        page.waitForTimeout(400);
        if (waitForOtpStepInDialog(page, 800)) {
            return true;
        }
        boolean reactClick = clickNextViaReact(page);
        page.waitForTimeout(500);
        if (waitForOtpStepInDialog(page, 2_500)) {
            return true;
        }
        log.info("YOUDO site-bridge: complete email submit reactClick={} probe={}",
                reactClick, probeLoginDialog(page));
        return reactClick;
    }

    /**
     * Сброс зависшего loading после клика «Далее» до капчи: «Назад» → снова email + токен + «Далее».
     */
    public boolean recoverStuckEmailSubmitViaBack(Page page, String expectedEmail) {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            log.info("YOUDO site-bridge: recover stuck submit — «Назад» + повтор «Далее»");
            commitExternalSmartToken(page);
            Object backRaw = page.evaluate(CLICK_BACK_REACT_JS);
            log.info("YOUDO site-bridge: React click «Назад» → {}", backRaw);
            page.waitForTimeout(1_200);
            ensureLoginEmail(page, expectedEmail);
            resetStuckEmailSubmitUi(page);
            commitExternalSmartToken(page);
            page.waitForTimeout(500);
            if (waitForOtpStepInDialog(page, 2_000)) {
                return true;
            }
            boolean next = clickNextViaReact(page);
            page.waitForTimeout(700);
            if (waitForOtpStepInDialog(page, 10_000)) {
                return true;
            }
            log.info("YOUDO site-bridge: recover via back next={} probe={}", next, probeLoginDialog(page));
            return next;
        } catch (Exception e) {
            log.warn("YOUDO site-bridge: recover via back failed: {}", e.getMessage());
            return false;
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
                                  const loader = nextBtn.querySelector(
                                    '[class*="loader"], [class*="Loader"], [class*="Spinner"], [class*="spinner"]');
                                  if (cls.includes('isLoading') || cls.includes('IsLoading')) return false;
                                  if (loader) return false;
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
