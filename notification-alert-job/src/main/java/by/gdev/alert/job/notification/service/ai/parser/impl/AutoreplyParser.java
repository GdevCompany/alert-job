package by.gdev.alert.job.notification.service.ai.parser.impl;

import by.gdev.alert.job.notification.model.AutoreplyMode;
import by.gdev.alert.job.notification.model.dto.AiNotificationPayload;
import by.gdev.alert.job.notification.model.dto.DecryptedCredential;
import by.gdev.alert.job.notification.service.ai.metrics.timings.AutoreplyDurationMetrics;
import by.gdev.alert.job.notification.service.ai.metrics.timings.AutoreplyMetricOperations;
import by.gdev.alert.job.notification.service.ai.metrics.timings.AutoreplyTimeouts;
import by.gdev.alert.job.notification.service.ai.parser.debug.AutoreplyReporter;
import by.gdev.alert.job.notification.service.ai.parser.debug.ScreenshotService;
import by.gdev.alert.job.notification.service.ai.proxy.AssignedProxyService;
import by.gdev.alert.job.notification.service.ai.recovery.AutoreplyFailureAction;
import by.gdev.alert.job.notification.service.ai.recovery.AutoreplyFailurePolicy;
import by.gdev.alert.job.notification.service.ai.recovery.AutoreplyProxyFailures;
import by.gdev.alert.job.notification.service.ai.sessions.AutoreplySessionVerifierFactory;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.common.model.SiteName;
import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.service.playwright.captcha.CaptchaService;
import by.gdev.common.service.playwright.sessions.storage.SessionStorage;
import by.gdev.common.service.playwright.manager.BrowserLaunchOptions;
import by.gdev.common.service.playwright.manager.PlaywrightBrowserManager;
import by.gdev.common.service.playwright.manager.PlaywrightManagerResolver;
import by.gdev.common.service.playwright.manager.impl.PlaywrightCamoufoxManager;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public abstract class AutoreplyParser {

    private static final class ProxyRetryState {
        final Set<String> triedHostPorts = new HashSet<>();
        ProxyCredentials overrideProxy;
        ProxyCredentials lastUsed;

        void reset() {
            triedHostPorts.clear();
            overrideProxy = null;
            lastUsed = null;
        }
    }

    private static final ThreadLocal<ProxyRetryState> PROXY_RETRY_STATE =
            ThreadLocal.withInitial(ProxyRetryState::new);

    protected boolean headless;
    protected boolean sendRequest;
    protected boolean proxy;

    @Value("${parser.autoreply.default.price:1000}")
    protected int defaultPrice;

    @Value("${parser.autoreply.default.days:1}")
    protected int defaultDays;

    @Value("${parser.autoreply.post-login.pause-ms:0}")
    protected long postLoginPauseMs;

    /** Неудач на одном прокси подряд, после чего берём следующий. */
    @Value("${parser.autoreply.proxy.attempts-per-proxy:3}")
    private int attemptsPerProxy;

    /** Сколько раз сменить прокси после исчерпания attempts-per-proxy (0 = только закреплённый). */
    @Value("${parser.autoreply.proxy.max-rotations:2}")
    private int maxProxyRotations;

    protected AssignedProxyService assignedProxyService;

    @Autowired
    protected ScreenshotService screenshotService;

    @Autowired
    protected AutoreplyReporter reporter;

    @Autowired
    protected AutoreplyTimeouts timeouts;

    @Autowired
    protected AutoreplyDurationMetrics durationMetrics;

    @Autowired
    protected SessionStorage sessionStorage;

    @Autowired(required = false)
    protected CaptchaService captchaService;

    @Autowired
    protected PlaywrightManagerResolver managerResolver;

    @Autowired
    protected AutoreplySessionVerifierFactory sessionVerifierFactory;

    protected AutoreplyParser(AssignedProxyService assignedProxyService) {
        this.assignedProxyService = assignedProxyService;
    }

    /**
     * Политика recovery по коду ошибки. Наследники могут расширять
     * (по умолчанию — {@link AutoreplyFailurePolicy}).
     */
    protected AutoreplyFailureAction resolveFailureAction(StepResult<Void> result) {
        return AutoreplyFailurePolicy.resolve(result);
    }

    /** @deprecated используйте {@link #resolveFailureAction(StepResult)} */
    @Deprecated
    protected boolean shouldRetryAutoreplyWithNewProxy(StepResult<Void> result) {
        return resolveFailureAction(result) == AutoreplyFailureAction.ROTATE_PROXY;
    }

    protected void prepareNextAutoreplyProxyAttempt(AiNotificationPayload payload, int nextAttempt) {
        ProxyRetryState state = PROXY_RETRY_STATE.get();
        ProxyCredentials next;
        if (proxy) {
            next = assignedProxyService.rotateProxyForUserModule(
                    payload.getUser().getUuid(),
                    payload.getModule().getId(),
                    getSiteName(),
                    state.lastUsed,
                    state.triedHostPorts);
        } else {
            next = assignedProxyService.pickWorkingProxyExcluding(getSiteName(), state.triedHostPorts);
        }
        state.overrideProxy = next;
        if (next == null) {
            log.warn("АВТООТВЕТ: {} -> нет другого прокси для попытки {}", getSiteName(), nextAttempt);
        }
    }

    /** Не логиниться в том же браузере (например DDoS — нужен другой прокси). */
    protected boolean skipLoginAfterVerifyFailure(StepResult<Void> verifyResult, AutoreplyMode autoreplyMode) {
        return false;
    }

    public final StepResult<Void> sendAutoreply(DecryptedCredential creds, AiNotificationPayload payload,
                                                AutoreplyMode autoreplyMode) {
        PROXY_RETRY_STATE.get().reset();
        int perProxyLimit = Math.max(1, attemptsPerProxy);
        int rotationLimit = Math.max(0, maxProxyRotations);
        int failuresOnCurrentProxy = 0;
        int nonProxyRetries = 0;
        int proxyRotationsDone = 0;
        int run = 0;
        StepResult<Void> last = null;

        while (true) {
            run++;
            last = AutoreplyProxyFailures.tagProxyFailure(runAutoreplyOnce(creds, payload, autoreplyMode));
            if (!last.failed()) {
                return last;
            }

            AutoreplyFailureAction action = resolveFailureAction(last);
            boolean proxyFailure = AutoreplyProxyFailures.isProxyRelated(last);
            ProxyCredentials usedProxy = PROXY_RETRY_STATE.get().lastUsed;
            String proxyLabel = usedProxy != null
                    ? usedProxy.getHost() + ":" + usedProxy.getPort()
                    : "нет";
            log.warn("АВТООТВЕТ: {} -> failure run={} mode={} action={} proxyFailure={} proxy={} code={} message={}",
                    getSiteName(), run, autoreplyMode, action, proxyFailure, proxyLabel,
                    last.getErrorCode(), last.getErrorMessage());
            if (action == AutoreplyFailureAction.ABORT) {
                return last;
            }

            if (!proxyFailure) {
                nonProxyRetries++;
                if (nonProxyRetries >= perProxyLimit) {
                    log.warn("АВТООТВЕТ: {} -> лимит повторов без смены прокси ({}) для не-прокси ошибки, code={}",
                            getSiteName(), perProxyLimit, last.getErrorCode());
                    return last;
                }
                log.warn("АВТООТВЕТ: {} -> повтор (не прокси) {}/{}, code={}",
                        getSiteName(), nonProxyRetries, perProxyLimit, last.getErrorCode());
                continue;
            }

            failuresOnCurrentProxy++;
            if (failuresOnCurrentProxy < perProxyLimit) {
                log.warn("АВТООТВЕТ: {} -> ПРОКСИ: повтор на {} {}/{} (без смены IP), code={}",
                        getSiteName(), proxyLabel, failuresOnCurrentProxy, perProxyLimit, last.getErrorCode());
                continue;
            }

            if (proxyRotationsDone >= rotationLimit) {
                log.warn("АВТООТВЕТ: {} -> лимит смен прокси ({}) после {} неудач на IP, code={}",
                        getSiteName(), rotationLimit, perProxyLimit, last.getErrorCode());
                return last;
            }

            prepareNextAutoreplyProxyAttempt(payload, proxyRotationsDone + 2);
            ProxyRetryState state = PROXY_RETRY_STATE.get();
            if (proxy && state.overrideProxy == null) {
                log.warn("АВТООТВЕТ: {} -> нет другого прокси после {} неудач на текущем",
                        getSiteName(), perProxyLimit);
                return last;
            }

            failuresOnCurrentProxy = 0;
            proxyRotationsDone++;
            log.warn("АВТООТВЕТ: {} -> ПРОКСИ: смена после {} сбоев на {} (ротация {}/{}), code={}",
                    getSiteName(), perProxyLimit, proxyLabel, proxyRotationsDone, rotationLimit,
                    last.getErrorCode());
        }
    }

    private StepResult<Void> runAutoreplyOnce(DecryptedCredential creds, AiNotificationPayload payload,
                                              AutoreplyMode autoreplyMode) {
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;

        PlaywrightBrowserManager manager = managerResolver.resolve(getSiteName());
        BrowserLaunchOptions options = buildLaunchOptions(getSiteName(), payload);
        String siteName = getSiteName().name();
        String userUuid = payload.getUser().getUuid();
        String login = creds.login();
        boolean camoufox = manager instanceof PlaywrightCamoufoxManager;

        try {
            playwright = manager.createPlaywright();
            browser = manager.createBrowser(playwright, options, getSiteName());

            boolean hasSession = sessionStorage.hasSession(userUuid, siteName, login)
                    || sessionStorage.storageStatePath(userUuid, siteName, login).isPresent();
            log.info("SESSION: location={} hasSession={} manager={}",
                    sessionStorage.describeSession(userUuid, siteName, login), hasSession,
                    camoufox ? "Camoufox" : "Chromium");

            Path chromiumPath = null;
            String chromiumJson = null;
            if (hasSession && !camoufox) {
                chromiumPath = sessionStorage.storageStatePath(userUuid, siteName, login).orElse(null);
                if (chromiumPath == null) {
                    chromiumJson = sessionStorage.storageStateJson(userUuid, siteName, login).orElse(null);
                }
            }
            context = manager.createBrowserContext(browser, options, getSiteName(), chromiumPath, chromiumJson);

            if (hasSession && camoufox) {
                sessionStorage.restoreCookies(context, userUuid, siteName, login);
            }

            page = context.newPage();

            if (hasSession && camoufox) {
                sessionStorage.restoreOrigins(page, userUuid, siteName, login);
            }

            sessionStorage.logContextCookies(context, sessionCookieCheckUrl(), "after-restore");

            long loginStart = System.nanoTime();
            LoginResolveOutcome loginOutcome = resolveLogin(page, payload, creds, autoreplyMode,
                    userUuid, siteName, login, hasSession);
            StepResult<Void> loginResult = loginOutcome.result();

            if (!loginResult.failed()) {
                // Строго до сохранения: пока дополнительный шаг не пройден, вход не завершён,
                // и сохранённая сессия на следующем запуске упрётся в тот же шаг (FL.ru — код из письма).
                StepResult<Void> afterLogin = checkAfterLogin(page, creds);
                if (afterLogin != null) {
                    loginResult = afterLogin;
                }
            }

            if (!loginResult.failed()) {
                pauseForSessionVerify(page, userUuid);
            }

            if (!loginResult.failed()) {
                StepResult<Void> finalized = finalizeBeforeSessionSave(page, creds, autoreplyMode);
                if (finalized != null) {
                    loginResult = finalized;
                }
            }

            if (!loginResult.failed()) {
                if (shouldPersistSession(page, loginResult)) {
                    try {
                        sessionStorage.save(context, userUuid, siteName, login);
                        log.info("SESSION: save после успешного login/verify {}/{}", siteName, login);
                    } catch (Exception ex) {
                        log.warn("Не удалось сохранить сессию: {}", ex.getMessage());
                    }
                } else {
                    log.warn("SESSION: save отменён — вход не подтверждён по UI (url={}) {}/{} — "
                                    + "если login/verify OK в логе выше, проверьте shouldPersistSession",
                            page != null ? page.url() : "n/a", siteName, login);
                    StepResult<Void> incomplete = incompleteLoginAfterSaveBlocked(page, autoreplyMode);
                    if (incomplete != null) {
                        loginResult = incomplete;
                    }
                }
            } else {
                log.warn("SESSION: save пропущен — login/verify не успешен {}/{}", siteName, login);
                if (shouldDeleteStoredSessionAfterLoginFailure(loginResult)) {
                    try {
                        sessionStorage.delete(userUuid, siteName, login);
                    } catch (Exception ex) {
                        log.warn("SESSION: не удалось удалить сессию после ошибки входа: {}", ex.getMessage());
                    }
                }
            }

            if (loginOutcome.loginFormUsed()) {
                long loginMs = (System.nanoTime() - loginStart) / 1_000_000;
                String loginStatus = loginResult.failed()
                        ? AutoreplyDurationMetrics.STATUS_FAILURE
                        : AutoreplyDurationMetrics.STATUS_SUCCESS;
                durationMetrics.recordDuration(siteName, AutoreplyMetricOperations.LOGIN, loginStatus, loginMs);
                log.info("METRIC: {} operation={} status={} duration={} ms",
                        siteName, AutoreplyMetricOperations.LOGIN, loginStatus, loginMs);
            } else {
                log.debug("METRIC: {} operation={} skipped — сессия уже действительна, вход по форме не выполнялся",
                        siteName, AutoreplyMetricOperations.LOGIN);
            }

            if (autoreplyMode.equals(AutoreplyMode.LOGIN_ONLY)) {
                return loginResult;
            }
            if (loginResult.failed()) {
                log.warn("Логин не выполнен для {}", login);
                return loginResult;
            }

            takeScreenshot(page, getSiteName(), userUuid, "after_login");
            page.waitForTimeout(timeouts.getBeforeProcessPauseMs());

            long processStart = System.nanoTime();
            StepResult<Void> processResult = processAutoReply(page, payload, creds);
            long processMs = (System.nanoTime() - processStart) / 1_000_000;
            String processStatus = processResult.failed()
                    ? AutoreplyDurationMetrics.STATUS_FAILURE
                    : AutoreplyDurationMetrics.STATUS_SUCCESS;
            durationMetrics.recordDuration(siteName, AutoreplyMetricOperations.PROCESS_AUTOREPLY, processStatus, processMs);
            log.info("METRIC: {} operation={} status={} duration={} ms",
                    siteName, AutoreplyMetricOperations.PROCESS_AUTOREPLY, processStatus, processMs);


            if (processResult.failed()) {
                log.warn("Автоответ НЕ отправлен пользователем {}", login);
                return processResult;
            }

            log.info("Автоответ успешно отправлен пользователем {}", login);
            return StepResult.ok(StepType.SEND_AUTOREPLY, null);

        } catch (Exception e) {
            log.error("Ошибка при отправке автоответа", e);
            byte[] screenshot = page != null ? captureScreenshot(page) : null;
            String message = "Необработанная ошибка: " + e.getMessage();
            String code = AutoreplyProxyFailures.errorCodeForThrowable(e);
            if (code != null) {
                return StepResult.fail(StepType.SEND_AUTOREPLY, code, message, screenshot);
            }
            return StepResult.fail(StepType.SEND_AUTOREPLY, message, screenshot);

        } finally {
            if (captchaService != null) {
                captchaService.clearCurrentProxy();
            }
            manager.closeResources(page, context, browser, playwright, getSiteName());
        }
    }

    protected PlaywrightBrowserManager getCurrentManager() {
        return managerResolver.resolve(getSiteName());
    }

    protected void humanWarmup(Page page) {
        getCurrentManager().humanMouse(page);
        getCurrentManager().humanDelay(page);
    }

    /** URL для проверки cookies после restore (домен биржи). */
    protected String sessionCookieCheckUrl() {
        return null;
    }

    /**
     * @param loginFormUsed {@code true}, если вызывался {@link #login} (форма логина/пароля);
     *                      {@code false} при успешной проверке уже восстановленной сессии
     */
    protected record LoginResolveOutcome(StepResult<Void> result, boolean loginFormUsed) {
    }

    protected LoginResolveOutcome resolveLogin(Page page, AiNotificationPayload payload,
                                               DecryptedCredential creds, AutoreplyMode autoreplyMode,
                                               String userUuid, String siteName, String login,
                                               boolean hasSession) {
        if (hasSession) {
            StepResult<Void> verified = verifyExistingSession(page, creds, autoreplyMode);
            if (verified != null && !verified.failed()) {
                log.info("SESSION: verifyExistingSession OK для {}/{}", siteName, login);
                return new LoginResolveOutcome(verified, false);
            }
            if (verified != null && skipLoginAfterVerifyFailure(verified, autoreplyMode)) {
                return new LoginResolveOutcome(verified, false);
            }
            log.warn("SESSION: verify не прошёл для {}/{} ({}), пробуем полный login без delete",
                    siteName, login, verified != null ? verified.getErrorMessage() : "null result");
        }
        StepResult<Void> loginResult = login(page, payload, creds, autoreplyMode);
        if (loginResult.failed() && hasSession && shouldInvalidateStoredSessionAfterFailedRelogin(loginResult)) {
            log.warn("SESSION: полный login не удался — удаляем устаревшую сессию {}/{} ({})",
                    siteName, login, loginResult.getErrorMessage());
            sessionStorage.delete(userUuid, siteName, login);
        }
        return new LoginResolveOutcome(loginResult, true);
    }

    /**
     * После неудачного полного login при уже сохранённой сессии — удалять файл только если сессия точно мёртвая
     * (не при обрыве браузера, DDoS, капче).
     */
    protected boolean shouldInvalidateStoredSessionAfterFailedRelogin(StepResult<Void> loginResult) {
        return false;
    }

    protected StepResult<Void> verifyExistingSession(Page page, DecryptedCredential creds, AutoreplyMode mode) {
        return sessionVerifierFactory.find(getSiteName())
                .map(v -> v.verifySessionOnPage(page, creds))
                .orElseGet(() -> StepResult.fail(StepType.SEND_AUTOREPLY,
                        "Проверка сессии не реализована для сайта"));
    }

    /**
     * Проверка страницы после успешного login/verify — выполняется до сохранения сессии.
     * Нужна, когда биржа пускает по сессии, но требует дополнительное действие (например FL.ru просит код из письма).
     *
     * @return ошибку для пользователя либо {@code null}, если всё в порядке
     */
    protected StepResult<Void> checkAfterLogin(Page page, DecryptedCredential creds) {
        return null;
    }

    /**
     * После паузы UI: FL.ru и др. могут показать validate-captcha / anti-bot уже после OTP или SmartCaptcha.
     */
    protected StepResult<Void> finalizeBeforeSessionSave(Page page, DecryptedCredential creds,
                                                         AutoreplyMode autoreplyMode) {
        return null;
    }

    /**
     * {@link AutoreplyMode#LOGIN_ONLY}: нельзя считать проверку учётных данных успешной без сохранённой сессии.
     */
    protected StepResult<Void> incompleteLoginAfterSaveBlocked(Page page, AutoreplyMode autoreplyMode) {
        if (autoreplyMode != AutoreplyMode.LOGIN_ONLY || page == null) {
            return null;
        }
        return StepResult.fail(StepType.SEND_AUTOREPLY, "LOGIN_INCOMPLETE",
                "Вход не завершён (сессия не сохранена), url=" + page.url(), captureScreenshot(page));
    }

    /**
     * Дополнительная проверка перед записью storageState (после {@link #checkAfterLogin}).
     * Сайты с OTP/капчей могут переопределить, чтобы не сохранить «полувход».
     */
    protected boolean shouldPersistSession(Page page, StepResult<Void> loginResult) {
        return page != null && loginResult != null && !loginResult.failed();
    }

    /** Удалить файл сессии при неуспешном login/verify (переопределяется на сайтах). */
    protected boolean shouldDeleteStoredSessionAfterLoginFailure(StepResult<Void> loginResult) {
        return false;
    }

    protected void pauseForSessionVerify(Page page, String userUuid) {
        long pause = postLoginPauseMs;
        if (pause <= 0 && !headless) {
            pause = timeouts.getSessionVerifyHeadfulPauseMs();
        }
        if (pause <= 0 || page == null) {
            return;
        }
        log.info("SESSION: пауза {} ms для проверки UI (headless={})", pause, headless);
        takeScreenshot(page, getSiteName(), userUuid, "session_verify");
        page.waitForTimeout(pause);
    }

    protected void safeNavigate(Page page, String url) {
        for (int i = 1; i <= 5; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                return;
            } catch (PlaywrightException e) {
                log.warn("Навигация не удалась (попытка {}): {}", i, e.getMessage());
                page.waitForTimeout(timeouts.getNavigateRetryPauseMs());
            }
        }
        throw new RuntimeException("Не удалось открыть страницу после 5 попыток: " + url);
    }

    /*boolean waitOrFail(Page page, String selector, int timeoutMs, String step) {
        try {
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            log.warn("TIMEOUT at step '{}': selector '{}' not found within {} ms", step, selector, timeoutMs);
            return false;
        }
    }*/

    protected boolean waitOrFail(Page page, String selector, String step) {
        long totalMs = timeouts.getElementWaitMs();
        long pollMs  = timeouts.getElementPollMs();
        long deadline = System.currentTimeMillis() + totalMs;
        int attempts = 0;
        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                Locator loc = page.locator(selector).first();
                if (loc.count() > 0) {
                    long spent = totalMs - (deadline - System.currentTimeMillis());
                    log.info("OK at step '{}': '{}' найдено за {} ms (attempts={})",
                            step, selector, spent, attempts);
                    return true;
                }
            } catch (Exception e) {
                log.debug("Poll at step '{}' '{}': {}", step, selector, e.getMessage());
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            page.waitForTimeout(Math.min(pollMs, remaining));
        }
        log.warn("TIMEOUT at step '{}': '{}' не найдено за {} ms (attempts={})",
                step, selector, totalMs, attempts);
        return false;
    }

    /** Ждёт появления элемента в DOM и его видимости (для модалок и скрытых шаблонов). */
    protected boolean waitForVisibleOrFail(Page page, String selector, String step) {
        long totalMs = timeouts.getElementWaitMs();
        try {
            page.locator(selector).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(totalMs));
            log.info("OK at step '{}': '{}' видимо", step, selector);
            return true;
        } catch (Exception e) {
            log.warn("TIMEOUT at step '{}': '{}' не видимо за {} ms: {}",
                    step, selector, totalMs, e.getMessage());
            return false;
        }
    }

    protected boolean clickOrFail(Page page, String selector, String step) {
        if (!waitOrFail(page, selector, step)) return false;
        try {
            getCurrentManager().humanClick(page, selector);
            return true;
        } catch (Exception e) {
            log.warn("CLICK FAILED at step '{}': selector '{}': {}", step, selector, e.getMessage());
            return false;
        }
    }

    protected BrowserLaunchOptions buildLaunchOptions(SiteName site, AiNotificationPayload payload) {
        ProxyRetryState state = PROXY_RETRY_STATE.get();
        String userEmail = payload.getUser() != null ? payload.getUser().getEmail() : null;
        if (state.overrideProxy != null) {
            state.lastUsed = state.overrideProxy;
            state.triedHostPorts.add(AssignedProxyService.hostPortKey(state.lastUsed));
            state.overrideProxy = null;
            log.info("АВТООТВЕТ: {} -> запуск через прокси {}:{} (повтор / смена прокси)",
                    site, state.lastUsed.getHost(), state.lastUsed.getPort());
            if (captchaService != null) {
                captchaService.setCurrentProxy(state.lastUsed);
            }
            return new BrowserLaunchOptions(state.lastUsed, headless, true, userEmail);
        }
        if (!proxy) {
            // даже при proxy=false recovery может подставить прокси на повторной попытке
            BrowserLaunchOptions options = new BrowserLaunchOptions(null, headless, false, userEmail);
            state.lastUsed = null;
            if (captchaService != null) {
                captchaService.clearCurrentProxy();
            }
            return options;
        }
        String userUuid = payload.getUser().getUuid();
        Long moduleId = payload.getModule() != null ? payload.getModule().getId() : null;
        ProxyCredentials proxyCred = assignedProxyService.getProxyForUserAndModule(userUuid, moduleId);
        if (proxyCred == null) {
            proxyCred = assignedProxyService.getProxyForUserAndSite(userUuid, site);
        }
        if (proxyCred == null) {
            proxyCred = assignedProxyService.pickWorkingProxyExcluding(site, state.triedHostPorts);
            log.info("АВТООТВЕТ: {} -> нет закреплённого прокси, взят рабочий из пула: {}", site,
                    proxyCred != null ? proxyCred.getHost() + ":" + proxyCred.getPort() : "нет проверенных");
        }
        state.lastUsed = proxyCred;
        if (state.lastUsed != null) {
            state.triedHostPorts.add(AssignedProxyService.hostPortKey(state.lastUsed));
        }
        if (captchaService != null) {
            captchaService.setCurrentProxy(state.lastUsed);
        }
        return new BrowserLaunchOptions(proxyCred, headless, true, userEmail);
    }

    protected void setOtp(AiNotificationPayload payload, String otp, boolean used) {
        payload.setOtpUsed(used);
        payload.setOtpValue(otp);
    }

    protected void report(Level level, Logger logger, String message, String errorType) {
        reporter.report(level, logger, getSiteName(), message, errorType);
    }

    protected byte[] captureScreenshot(Page page) {
        return screenshotService.capture(page);
    }

    protected void takeScreenshot(Page page, SiteName site, String userUuid, String step) {
        screenshotService.take(page, site, userUuid, step);
    }

    protected abstract StepResult<Void> login(Page page, AiNotificationPayload payload,
                                              DecryptedCredential creds, AutoreplyMode mode);

    protected abstract StepResult<Void> processAutoReply(Page page, AiNotificationPayload payload,
                                                         DecryptedCredential creds);

    protected abstract SiteName getSiteName();
}
