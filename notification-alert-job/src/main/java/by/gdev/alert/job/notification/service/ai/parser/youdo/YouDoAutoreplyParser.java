package by.gdev.alert.job.notification.service.ai.parser.youdo;

import by.gdev.alert.job.notification.model.AutoreplyMode;
import by.gdev.alert.job.notification.model.dto.AiNotificationPayload;
import by.gdev.alert.job.notification.model.dto.DecryptedCredential;
import by.gdev.alert.job.notification.service.ai.metrics.errors.AutoreplyErrorTypes;
import by.gdev.alert.job.notification.service.ai.parser.AutoreplyPlaywrightParser;
import by.gdev.alert.job.notification.service.ai.otp.OtpService;
import by.gdev.alert.job.notification.service.ai.parser.impl.AutoreplyParser;
import by.gdev.alert.job.notification.service.ai.parser.impl.flru.FlRuAutoreplyParser;
import by.gdev.alert.job.notification.service.ai.proxy.AssignedProxyService;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.common.model.SiteName;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureInfo;
import by.gdev.common.service.playwright.human.HumanInput;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.slf4j.event.Level;


@Slf4j
@Component
public class YouDoAutoreplyParser extends AutoreplyParser implements AutoreplyPlaywrightParser {

    private static final String[] LOGIN_BUTTON_SELECTORS = {
            "header [data-test='LoginButton']",
            "[data-test='LoginButton']",
            "data-test=LoginButton"
    };

    private static final String[] LOGIN_EMAIL_BUTTON_SELECTORS = {
            "data-test=LoginWithEmailButton",
            "[data-test='LoginWithEmailButton']",
            "button[data-test='LoginWithEmailButton']",
            "text=Войти через электронную почту",
            "text=Войти через email"
    };

    private static final int LOGIN_MODAL_WAIT_MS = 12_000;
    private static final int LOGIN_CLICK_RETRY_MS = 28_000;
    /** Cookie-баннер YouDo часто монтируется с задержкой после NETWORKIDLE (особенно Camoufox/Linux). */
    private static final int COOKIE_BANNER_SETTLE_MS = 3_500;

    private static final String[] EMAIL_ERROR_SELECTORS = {
            "text=Неправильный адрес электронной почты",
            "text=Неправильный адрес почты",
            "[class*='Tooltip_error']:has-text('Неправильный адрес')"
    };

    private static final String EMAIL_ERROR_MESSAGE = "Неправильный адрес электронной почты";

    private static final int YOUDO_LOGIN_STAGE_MAX_ITERATIONS = 12;

    private static final String YOUDO_STAGE_LOOP_EXHAUSTED_MESSAGE =
            "YouDo: слишком много переходов между капчей, email и кодом из письма. Попробуйте ещё раз.";

    private static final String YOUDO_EMAIL_SUBMIT_STUCK_CODE = "YOUDO_EMAIL_SUBMIT_STUCK";
    private static final String YOUDO_EMAIL_SUBMIT_STUCK_MESSAGE =
            "YouDo: отправка email зависла после капчи (спиннер на «Далее»). Попробуем другой прокси.";

    private static final int YOUDO_POST_CAPTCHA_NEXT_MAX = 1;
    private static final int YOUDO_POST_SUBMIT_WAIT_MS = 55_000;

    /** Этапы входа YouDo: SmartCaptcha (чекбокс / калейдоскоп) и OTP могут чередоваться. */
    private enum YouDoLoginStage {
        SMART_CAPTCHA,
        OTP_CODE,
        EMAIL_FORM,
        EMAIL_ERROR,
        LOGGED_IN,
        UNKNOWN
    }

    private final OtpService otpService;

    private final YoudoTariffChecker youdoTariffChecker;

    private final YouDoLoginCaptcha youDoLoginCaptcha;

    private final YouDoSmartCaptchaSupport smartCaptchaSupport;

    private final YouDoBrowserGuards browserGuards;

    private final YouDoModalLayoutFix modalLayoutFix;

    @Value("${parser.autoreply.headless.youdo.com:true}")
    private void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Value("${parser.autoreply.proxy.youdo.com:false}")
    private void setProxy(boolean proxy) {
        this.proxy = proxy;
    }

    @Value("${parser.autoreply.send.request.youdo.com:true}")
    private void setOnSendRequest(boolean sendRequest) {
        this.sendRequest = sendRequest;
    }

    @Value("${credential.validation.otp.timeout.ms:120000}")
    private long otpValidationTimeoutMs;

    public YouDoAutoreplyParser(AssignedProxyService assignedProxyService,
                                OtpService otpService,
                                YoudoTariffChecker youdoTariffChecker,
                                YouDoLoginCaptcha youDoLoginCaptcha,
                                YouDoSmartCaptchaSupport smartCaptchaSupport,
                                YouDoBrowserGuards browserGuards,
                                YouDoModalLayoutFix modalLayoutFix) {
        super(assignedProxyService);
        this.otpService = otpService;
        this.youdoTariffChecker = youdoTariffChecker;
        this.youDoLoginCaptcha = youDoLoginCaptcha;
        this.smartCaptchaSupport = smartCaptchaSupport;
        this.browserGuards = browserGuards;
        this.modalLayoutFix = modalLayoutFix;
    }

    @Override
    public SiteName getSiteName() {
        return SiteName.YOUDO;
    }

    @Override
    protected String sessionCookieCheckUrl() {
        return "https://youdo.com";
    }

    @Override
    protected StepResult<Void> login(Page page, AiNotificationPayload payload, DecryptedCredential creds, AutoreplyMode mode) {
        log.info("АВТООТВЕТ: {} -> НАЧАЛО ЛОГИНА, пользователь: {}", getSiteName(), creds.login());
        browserGuards.installForLogin(page);
        YouDoLoginFsmState.log(log, YouDoLoginFsmState.NAVIGATE_HOME, creds.login(), null);

        // Открыть главную страницу
        try {
            safeNavigate(page, "https://youdo.com/");
            log.info("АВТООТВЕТ: {} -> главная страница загружена, пользователь: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN,
                    log,
                    String.format("АВТООТВЕТ: %s -> НЕ УДАЛОСЬ ОТКРЫТЬ ГЛАВНУЮ СТРАНИЦУ, пользователь: %s, ошибка: %s", getSiteName(), creds.login(), e.getMessage()), AutoreplyErrorTypes.ERROR_OPEN_PAGE);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось открыть главную страницу: " + e.getMessage(), captureScreenshot(page));
        }

        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> NETWORKIDLE не достигнут, продолжаем, пользователь: {}", getSiteName(), creds.login());
        }

        humanWarmupYouDo(page);

        YouDoLoginFsmState.log(log, YouDoLoginFsmState.DISMISS_COOKIES, creds.login(), null);
        prepareYouDoPageForLogin(page);

        YouDoLoginFsmState.log(log, YouDoLoginFsmState.OPEN_LOGIN_MODAL, creds.login(), null);
        if (!clickHeaderLoginUntilModal(page, creds.login())) {
            report(Level.WARN, log,
                    String.format("АВТООТВЕТ: %s -> НЕ ОТКРЫЛАСЬ МОДАЛКА ВХОДА, пользователь: %s",
                            getSiteName(), creds.login()),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не открылась модалка входа YouDo", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> модалка входа открыта, пользователь: {}", getSiteName(), creds.login());
        modalLayoutFix.apply(page);

        YouDoLoginFsmState.log(log, YouDoLoginFsmState.CHOOSE_EMAIL, creds.login(), null);
        if (!openEmailLoginStep(page, creds.login())) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ПЕРЕЙТИ К ВХОДУ ПО EMAIL, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось открыть вход по email", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> форма email доступна, пользователь: {}", getSiteName(), creds.login());

        // Ожидание поля ввода email
        if (!waitOrFail(page, "input[name='login']", "Поле email")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ EMAIL, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле email не найдено", captureScreenshot(page));
        }

        YouDoLoginFsmState.log(log, YouDoLoginFsmState.FILL_EMAIL, creds.login(), null);
        // Заполнение email
        try {
            humanTypeField(page, "input[name='login']", creds.login());
            log.info("АВТООТВЕТ: {} -> email заполнен: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ EMAIL, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить email: " + e.getMessage(), captureScreenshot(page));
        }

        youDoLoginCaptcha.beginNetworkCapture(page);
        try {
            return loginAfterEmailFilled(page, payload, creds);
        } finally {
            youDoLoginCaptcha.endNetworkCapture(page);
        }
    }

    private StepResult<Void> loginAfterEmailFilled(Page page, AiNotificationPayload payload, DecryptedCredential creds) {
        StepResult<Void> pre = runYouDoLoginStageLoop(page, creds, true, false);
        if (pre != null && pre.failed()) {
            return pre;
        }
        if (detectYouDoLoginStage(page) == YouDoLoginStage.LOGGED_IN) {
            log.info("АВТООТВЕТ: {} -> ЛОГИН УСПЕШЕН (без OTP), пользователь: {}", getSiteName(), creds.login());
            return StepResult.ok(StepType.SEND_AUTOREPLY, null);
        }

        otpService.beginOtpWait(SiteName.YOUDO.name(), creds.login());
        YouDoLoginFsmState.log(log, YouDoLoginFsmState.SUBMIT_EMAIL, creds.login(), "нажимаем «Далее»");
        log.info("АВТООТВЕТ: {} -> OTP wait начат, нажимаем «Далее», пользователь: {}", getSiteName(), creds.login());

        if (!clickOrFail(page, "button:has-text('Далее')", "Кнопка 'Далее'")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА 'Далее', пользователь: " + creds.login(),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка 'Далее' не найдена", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> кнопка 'Далее' нажата, пользователь: {}", getSiteName(), creds.login());

        waitAfterYouDoNextClick(page);
        modalLayoutFix.apply(page);
        browserGuards.clickContinueEditingIfPresent(page);
        StepResult<Void> captchaAfterNext = resolveCaptchaBlockingStep(page, creds);
        if (captchaAfterNext != null) {
            return captchaAfterNext;
        }

        StepResult<Void> gates = runYouDoLoginStageLoop(page, creds, false, true);
        if (gates != null && gates.failed()) {
            return gates;
        }

        if (!isOtpFieldVisible(page)) {
            if (isEmailErrorPresent(page)) {
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> НЕПРАВИЛЬНЫЙ АДРЕС ЭЛЕКТРОННОЙ ПОЧТЫ, пользователь: " + creds.login(),
                        AutoreplyErrorTypes.EMAIL_INVALID);
                return StepResult.fail(StepType.SEND_AUTOREPLY, EMAIL_ERROR_MESSAGE, captureScreenshot(page));
            }
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ ВВОДА КОДА, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле ввода кода не найдено", captureScreenshot(page));
        }

        log.info("АВТООТВЕТ: {} -> ожидание OTP для {}", getSiteName(), creds.login());
        String otp = otpService.waitForOtp(SiteName.YOUDO.name(), creds.login(), otpValidationTimeoutMs);
        if (otp == null) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> OTP НЕ ПОЛУЧЕН за отведённое время, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.OTP_NOT_RECEIVED);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "OTP не получен за отведённое время", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> OTP получен для пользователя: {}", getSiteName(), creds.login());
        setOtp(payload, otp, true);

        if (page.isClosed()) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> браузер закрыт до ввода OTP, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY,
                    "Браузер закрыт до ввода OTP (не закрывайте окно Playwright во время ожидания кода)",
                    (byte[]) null);
        }

        try {
            humanTypeField(page, "input[name='code']", otp);
            log.info("АВТООТВЕТ: {} -> OTP заполнен для пользователя: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ OTP, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить OTP: " + e.getMessage(), captureScreenshot(page));
        }

        gates = runYouDoLoginStageLoop(page, creds, false, false);
        if (gates != null && gates.failed()) {
            return gates;
        }

        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);
            log.info("АВТООТВЕТ: {} -> страница загружена после ввода OTP, пользователь: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            if (!isYouDoLoggedIn(page)) {
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ДОЖДАТЬСЯ ЗАГРУЗКИ ПОСЛЕ OTP, пользователь: "
                                + creds.login() + ", ошибка: " + e.getMessage(),
                        AutoreplyErrorTypes.TIMEOUT);
                return StepResult.fail(StepType.SEND_AUTOREPLY,
                        "Не удалось дождаться загрузки после OTP: " + e.getMessage(), captureScreenshot(page));
            }
            log.debug("АВТООТВЕТ: {} -> NETWORKIDLE не достигнут после OTP, продолжаем, пользователь: {}",
                    getSiteName(), creds.login());
        }

        log.info("АВТООТВЕТ: {} -> ЛОГИН УСПЕШЕН, пользователь: {}", getSiteName(), creds.login());
        otpService.invalidateOtp(SiteName.YOUDO.name(), creds.login());
        log.info("АВТООТВЕТ: {} -> OTP инвалидирован для {}", getSiteName(), creds.login());
        return StepResult.ok(StepType.SEND_AUTOREPLY, null);
    }

    /**
     * Цикл этапов входа (как {@link FlRuAutoreplyParser#runLoginStageLoop}):
     * SmartCaptcha и OTP могут чередоваться после «Далее».
     *
     * @param allowEmailForm {@code true} — остановка на форме email без ошибки (ещё не жали «Далее»)
     * @param otpWaitArmed   {@code true} если {@link OtpService#beginOtpWait} уже вызван перед «Далее»
     * @return {@code null} при успехе; иначе ошибка шага
     */
    private StepResult<Void> runYouDoLoginStageLoop(Page page, DecryptedCredential creds,
                                                    boolean allowEmailForm, boolean otpWaitArmed) {
        int captchaHandled = 0;
        int[] postCaptchaNextClicks = {0};
        for (int i = 1; i <= YOUDO_LOGIN_STAGE_MAX_ITERATIONS; i++) {
            if (page.isClosed()) {
                YouDoLoginFsmState.log(log, YouDoLoginFsmState.ABORT, creds.login(), "страница закрыта");
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Браузер закрыт во время входа YouDo",
                        captureScreenshot(page));
            }
            browserGuards.clickContinueEditingIfPresent(page);
            YouDoLoginStage stage = detectYouDoLoginStage(page);
            // Капча может всплыть на любом шаге — приоритет над формой/UNKNOWN.
            if (stage != YouDoLoginStage.SMART_CAPTCHA
                    && stage != YouDoLoginStage.LOGGED_IN
                    && (smartCaptchaSupport.isLoginCaptchaGate(page)
                    || smartCaptchaSupport.isSmartCaptchaShellPresent(page))) {
                YouDoLoginFsmState.log(log, YouDoLoginFsmState.CAPTCHA_GUARD, creds.login(),
                        "перехват: было " + stage + " → SMART_CAPTCHA, kind="
                                + smartCaptchaSupport.detectCaptchaKind(page));
                stage = YouDoLoginStage.SMART_CAPTCHA;
            }
            logFsmStage(stage, creds.login(), i);
            log.info("АВТООТВЕТ: {} -> этап входа {}/{}: {} url={}, пользователь: {}",
                    getSiteName(), i, YOUDO_LOGIN_STAGE_MAX_ITERATIONS, stage, page.url(), creds.login());

            switch (stage) {
                case SMART_CAPTCHA -> {
                    captchaHandled++;
                    if (captchaHandled > 5) {
                        return failYouDoCaptcha(page, creds.login(),
                                "YouDo SmartCaptcha: слишком много повторов капчи");
                    }
                    log.info("АВТООТВЕТ: {} -> вызываем passLoginSmartCaptcha ({}/5), otpWaitArmed={}, пользователь: {}",
                            getSiteName(), captchaHandled, otpWaitArmed, creds.login());
                    if (!smartCaptchaSupport.passLoginSmartCaptcha(page, creds.login())) {
                        return failYouDoCaptcha(page, creds.login(),
                                "YouDo SmartCaptcha не пройдена (чекбокс / калейдоскоп)");
                    }
                    log.info("АВТООТВЕТ: {} -> капча пройдена, resumeYandexSmartCaptchaForForm + dismiss overlay, пользователь: {}",
                            getSiteName(), creds.login());
                    captchaService.resumeYandexSmartCaptchaForForm(page);
                    captchaService.dismissSmartCaptchaOverlay(page);
                    page.waitForTimeout(400);
                    if (otpWaitArmed) {
                        log.info("АВТООТВЕТ: {} -> otpWaitArmed: tryResumeYouDoEmailSubmitAfterCaptcha, пользователь: {}",
                                getSiteName(), creds.login());
                        tryResumeYouDoEmailSubmitAfterCaptcha(page);
                        StepResult<Void> stuck = handlePostCaptchaEmailSubmit(page, creds, postCaptchaNextClicks);
                        if (stuck != null) {
                            return stuck;
                        }
                        if (smartCaptchaSupport.isLoginCaptchaGate(page)) {
                            log.info("АВТООТВЕТ: {} -> после капчи снова loginCaptchaGate — ещё один шаг капчи, пользователь: {}",
                                    getSiteName(), creds.login());
                            continue;
                        }
                        if (isOtpFieldVisible(page)) {
                            return null;
                        }
                    }
                }
                case EMAIL_ERROR -> {
                    report(Level.WARN, log,
                            "АВТООТВЕТ: " + getSiteName() + " -> НЕПРАВИЛЬНЫЙ АДРЕС ЭЛЕКТРОННОЙ ПОЧТЫ, пользователь: "
                                    + creds.login(),
                            AutoreplyErrorTypes.EMAIL_INVALID);
                    return StepResult.fail(StepType.SEND_AUTOREPLY, EMAIL_ERROR_MESSAGE, captureScreenshot(page));
                }
                case OTP_CODE -> {
                    if (otpWaitArmed) {
                        return null;
                    }
                    page.waitForTimeout(500);
                }
                case EMAIL_FORM -> {
                    if (allowEmailForm) {
                        return null;
                    }
                    if (smartCaptchaSupport.isLoginCaptchaGate(page)) {
                        page.waitForTimeout(500);
                        continue;
                    }
                    if (otpWaitArmed) {
                        if (isOtpFieldVisible(page)) {
                            return null;
                        }
                        StepResult<Void> stuck = handlePostCaptchaEmailSubmit(page, creds, postCaptchaNextClicks);
                        if (stuck != null) {
                            return stuck;
                        }
                        if (isOtpFieldVisible(page)) {
                            return null;
                        }
                        if (isYouDoEmailSubmitLoading(page)) {
                            log.info("АВТООТВЕТ: {} -> ждём ответ сервера после «Далее» (спиннер), пользователь: {}",
                                    getSiteName(), creds.login());
                            waitForYouDoLoginProgress(page, YOUDO_POST_SUBMIT_WAIT_MS);
                            if (isYouDoEmailSubmitLoading(page) && !isOtpFieldVisible(page)) {
                                return failYouDoEmailSubmitStuck(page, creds.login());
                            }
                            continue;
                        }
                        if (smartCaptchaSupport.isLoginCaptchaGate(page)) {
                            continue;
                        }
                        if (postCaptchaNextClicks[0] < YOUDO_POST_CAPTCHA_NEXT_MAX
                                && hasYouDoSmartTokenForSubmit(page)
                                && isYouDoNextButtonClickable(page)) {
                            postCaptchaNextClicks[0]++;
                            log.info("АВТООТВЕТ: {} -> после капчи одна отправка «Далее» ({}/{}), пользователь: {}",
                                    getSiteName(), postCaptchaNextClicks[0], YOUDO_POST_CAPTCHA_NEXT_MAX, creds.login());
                            if (clickYouDoNextButton(page)) {
                                waitAfterYouDoNextClick(page);
                                waitForYouDoLoginProgress(page, YOUDO_POST_SUBMIT_WAIT_MS);
                            }
                            continue;
                        }
                        if (postCaptchaNextClicks[0] >= YOUDO_POST_CAPTCHA_NEXT_MAX
                                && isEmailFieldVisible(page)
                                && !isOtpFieldVisible(page)) {
                            if (smartCaptchaSupport.isLoginCaptchaGate(page)) {
                                continue;
                            }
                            return failYouDoEmailSubmitStuck(page, creds.login());
                        }
                        waitForYouDoLoginProgress(page, 8_000);
                        continue;
                    }
                    report(Level.WARN, log,
                            "АВТООТВЕТ: " + getSiteName() + " -> ВХОД НЕ ВЫПОЛНЕН, остались на форме email, пользователь: "
                                    + creds.login(),
                            AutoreplyErrorTypes.LOGIN_FAILED);
                    return StepResult.fail(StepType.SEND_AUTOREPLY, "Остались на форме email после «Далее»",
                            captureScreenshot(page));
                }
                case LOGGED_IN -> {
                    return null;
                }
                case UNKNOWN -> {
                    page.waitForTimeout(700);
                    captchaService.revealYouDoLoginCaptchaIframe(page);
                    if (smartCaptchaSupport.isLoginCaptchaGate(page)
                            || smartCaptchaSupport.isSmartCaptchaShellPresent(page)) {
                        YouDoLoginFsmState.log(log, YouDoLoginFsmState.CAPTCHA_GUARD, creds.login(),
                                "UNKNOWN → shell/gate, ждём 2Captcha");
                        captchaService.waitForYandexSmartCaptchaWidget(page, 12_000);
                        continue;
                    }
                    if (otpWaitArmed && isEmailFieldVisible(page)) {
                        continue;
                    }
                    if (!otpWaitArmed && !isEmailFieldVisible(page) && !isOtpFieldVisible(page)
                            && shouldRestoreYouDoEmailFormAfterCaptcha(page)) {
                        restoreYouDoEmailFormAfterCaptcha(page, creds);
                    }
                    YouDoLoginStage again = detectYouDoLoginStage(page);
                    if (again != YouDoLoginStage.UNKNOWN) {
                        continue;
                    }
                    if (otpWaitArmed && isOtpFieldVisible(page)) {
                        return null;
                    }
                    if (otpWaitArmed && i >= 6 && !smartCaptchaSupport.isLoginCaptchaGate(page)) {
                        log.warn("АВТООТВЕТ: {} -> неизвестный этап после «Далее», url={}, пользователь: {}",
                                getSiteName(), page.url(), creds.login());
                        return StepResult.fail(StepType.SEND_AUTOREPLY, "YOUDO_LOGIN_UNKNOWN_STAGE",
                                "YouDo: неизвестный экран после «Далее»: " + page.url(), captureScreenshot(page));
                    }
                    if (!otpWaitArmed && i >= 3) {
                        return null;
                    }
                }
                default -> page.waitForTimeout(300);
            }
        }
        if (otpWaitArmed && isEmailFieldVisible(page) && !isOtpFieldVisible(page)) {
            return failYouDoEmailSubmitStuck(page, creds.login());
        }
        return StepResult.fail(StepType.SEND_AUTOREPLY, "YOUDO_LOGIN_STAGE_LOOP",
                YOUDO_STAGE_LOOP_EXHAUSTED_MESSAGE, captureScreenshot(page));
    }

    /**
     * После капчи: не кликать «Далее», пока крутится спиннер; дождаться OTP или одной повторной отправки.
     */
    private StepResult<Void> handlePostCaptchaEmailSubmit(Page page, DecryptedCredential creds,
                                                          int[] postCaptchaNextClicks) {
        log.info("АВТООТВЕТ: {} -> handlePostCaptchaEmailSubmit: otp={} loggedIn={} loading={} tokenSubmit={} "
                        + "nextClickable={} postCaptchaClicks={}/{}, пользователь: {}",
                getSiteName(),
                isOtpFieldVisible(page),
                isYouDoLoggedIn(page),
                isYouDoEmailSubmitLoading(page),
                hasYouDoSmartTokenForSubmit(page),
                isYouDoNextButtonClickable(page),
                postCaptchaNextClicks[0],
                YOUDO_POST_CAPTCHA_NEXT_MAX,
                creds.login());
        if (isOtpFieldVisible(page) || isYouDoLoggedIn(page)) {
            log.info("АВТООТВЕТ: {} -> postCaptcha: уже OTP или logged-in, выход", getSiteName());
            return null;
        }
        if (smartCaptchaSupport.isLoginCaptchaGate(page)) {
            log.info("АВТООТВЕТ: {} -> postCaptcha: loginCaptchaGate — ждём повторного прохода капчи, пользователь: {}",
                    getSiteName(), creds.login());
            return null;
        }
        if (isYouDoEmailSubmitLoading(page)) {
            log.info("АВТООТВЕТ: {} -> капча снята, ждём завершение запроса email, пользователь: {}",
                    getSiteName(), creds.login());
            waitForYouDoLoginProgress(page, YOUDO_POST_SUBMIT_WAIT_MS);
            if (isOtpFieldVisible(page) || isYouDoLoggedIn(page)) {
                return null;
            }
            if (isYouDoEmailSubmitLoading(page) && hasYouDoSmartTokenForSubmit(page)) {
                log.info("АВТООТВЕТ: {} -> спиннер после smart-token, возобновляем отправку email, пользователь: {}",
                        getSiteName(), creds.login());
                captchaService.resumeYandexSmartCaptchaForForm(page);
                captchaService.dismissSmartCaptchaOverlay(page);
                page.waitForTimeout(600);
                tryResumeYouDoEmailSubmitAfterCaptcha(page);
                waitForYouDoLoginProgress(page, 28_000);
            }
            if (isYouDoEmailSubmitLoading(page) && hasYouDoSmartTokenForSubmit(page)) {
                log.info("АВТООТВЕТ: {} -> спиннер не снялся, force «Далее» после капчи, пользователь: {}",
                        getSiteName(), creds.login());
                forceClickYouDoNextAfterCaptcha(page);
                waitForYouDoLoginProgress(page, 20_000);
            }
            if (isYouDoEmailSubmitLoading(page) && !isOtpFieldVisible(page)) {
                return failYouDoEmailSubmitStuck(page, creds.login());
            }
            return null;
        }
        if (postCaptchaNextClicks[0] >= YOUDO_POST_CAPTCHA_NEXT_MAX) {
            log.info("АВТООТВЕТ: {} -> postCaptcha: лимит повторных «Далее» исчерпан", getSiteName());
            return null;
        }
        if (!hasYouDoSmartTokenForSubmit(page) || !isYouDoNextButtonClickable(page)) {
            log.info("АВТООТВЕТ: {} -> postCaptcha: ждём прогресс (tokenSubmit={} nextClickable={})",
                    getSiteName(), hasYouDoSmartTokenForSubmit(page), isYouDoNextButtonClickable(page));
            waitForYouDoLoginProgress(page, 12_000);
            return null;
        }
        postCaptchaNextClicks[0]++;
        log.info("АВТООТВЕТ: {} -> postCaptcha: «Далее» с токеном ({}/{}), пользователь: {}",
                getSiteName(), postCaptchaNextClicks[0], YOUDO_POST_CAPTCHA_NEXT_MAX, creds.login());
        if (clickYouDoNextButton(page)) {
            waitAfterYouDoNextClick(page);
            triggerYouDoEmailFormSubmit(page);
            waitForYouDoLoginProgress(page, YOUDO_POST_SUBMIT_WAIT_MS);
        } else {
            forceClickYouDoNextAfterCaptcha(page);
            waitForYouDoLoginProgress(page, YOUDO_POST_SUBMIT_WAIT_MS);
        }
        if (!isOtpFieldVisible(page) && isEmailFieldVisible(page) && hasYouDoSmartTokenForSubmit(page)) {
            triggerYouDoEmailFormSubmit(page);
            waitForYouDoLoginProgress(page, 15_000);
        }
        return null;
    }

    /** Сброс зависшего спиннера и повтор «Далее» после внешнего smart-token. */
    private void tryResumeYouDoEmailSubmitAfterCaptcha(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        captchaService.resumeYandexSmartCaptchaForForm(page);
        captchaService.dismissSmartCaptchaOverlay(page);
        try {
            page.evaluate(
                    """
                            () => {
                              for (const b of document.querySelectorAll('button')) {
                                if (b.offsetParent === null) continue;
                                const text = (b.textContent || '').trim();
                                if (!text.includes('Далее')) continue;
                                b.disabled = false;
                                b.removeAttribute('aria-busy');
                                b.classList.remove('disabled');
                              }
                            }
                            """);
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> сброс спиннера «Далее»: {}", getSiteName(), e.getMessage());
        }
        if (!isYouDoEmailSubmitLoading(page) && isYouDoNextButtonClickable(page)) {
            clickYouDoNextButton(page);
            waitAfterYouDoNextClick(page);
            return;
        }
        if (isYouDoEmailSubmitLoading(page) && isYouDoNextButtonClickable(page)) {
            clickYouDoNextButton(page);
            waitAfterYouDoNextClick(page);
            return;
        }
        if (hasYouDoSmartTokenForSubmit(page)) {
            forceClickYouDoNextAfterCaptcha(page);
            triggerYouDoEmailFormSubmit(page);
        }
    }

    /** React-форма YouDo: после внешнего smart-token иногда нужен requestSubmit / Enter, не только клик. */
    private void triggerYouDoEmailFormSubmit(Page page) {
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
                log.info("АВТООТВЕТ: {} -> отправка формы email (requestSubmit/Enter)", getSiteName());
                waitAfterYouDoNextClick(page);
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> requestSubmit email: {}", getSiteName(), e.getMessage());
        }
    }

    /** Повтор «Далее» при зависшем спиннере после smart-token (обычный клик блокируется isYouDoEmailSubmitLoading). */
    private void forceClickYouDoNextAfterCaptcha(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            page.evaluate(
                    """
                            () => {
                              for (const b of document.querySelectorAll('button')) {
                                if (b.offsetParent === null) continue;
                                const text = (b.textContent || '').trim();
                                if (!text.includes('Далее')) continue;
                                b.disabled = false;
                                b.removeAttribute('aria-busy');
                                b.classList.remove('disabled');
                                const spin = b.querySelector('[class*="Loader"], [class*="loader"], [class*="Spinner"], [class*="spinner"], svg');
                                if (spin) spin.remove();
                              }
                            }
                            """);
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> сброс спиннера force «Далее»: {}", getSiteName(), e.getMessage());
        }
        try {
            Locator next = page.locator("button:has-text('Далее')").first();
            if (next.count() > 0 && next.isVisible()) {
                clickLocatorForceIfNeeded(page, next);
                log.info("АВТООТВЕТ: {} -> force-клик «Далее» после капчи", getSiteName());
                waitAfterYouDoNextClick(page);
                triggerYouDoEmailFormSubmit(page);
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> force «Далее» не удался: {}", getSiteName(), e.getMessage());
        }
    }

    private void waitAfterYouDoNextClick(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        if (smartCaptchaSupport.isLoginCaptchaGate(page)) {
            captchaService.waitForYandexSmartCaptchaWidget(page, 15_000);
            captchaService.revealYouDoLoginCaptchaIframe(page);
            page.waitForTimeout(400);
        } else if (hasYouDoSmartTokenForSubmit(page)) {
            page.waitForTimeout(600);
        } else {
            captchaService.waitForYandexSmartCaptchaWidget(page, 6_000);
            page.waitForTimeout(400);
        }
    }

    private boolean hasYouDoSmartTokenForSubmit(Page page) {
        return captchaService != null && captchaService.smartCaptchaTokenLength(page) > 20;
    }

    private boolean clickYouDoNextButton(Page page) {
        if (isYouDoEmailSubmitLoading(page)) {
            return false;
        }
        return clickOrFail(page, "button:has-text('Далее')", "Кнопка 'Далее' после капчи");
    }

    private boolean isYouDoNextButtonClickable(Page page) {
        if (page == null || page.isClosed() || isYouDoEmailSubmitLoading(page)) {
            return false;
        }
        try {
            Locator next = page.locator("button:has-text('Далее')").first();
            return next.count() > 0 && next.isVisible() && next.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isYouDoEmailSubmitLoading(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            Object loading = page.evaluate(
                    """
                            () => {
                              const login = document.querySelector('input[name="login"]');
                              if (!login || login.offsetParent === null) return false;
                              const buttons = [...document.querySelectorAll('button')]
                                .filter(b => b.offsetParent !== null);
                              for (const b of buttons) {
                                const text = (b.textContent || '').trim();
                                if (text.includes('Назад')) continue;
                                const r = b.getBoundingClientRect();
                                if (r.width < 50 || r.height < 24) continue;
                                const busy = b.disabled || b.getAttribute('aria-busy') === 'true'
                                  || b.querySelector('svg, [class*="Loader"], [class*="loader"], [class*="Spinner"], [class*="spinner"]');
                                if (busy && (text.includes('Далее') || !text)) return true;
                              }
                              return false;
                            }
                            """);
            return Boolean.TRUE.equals(loading);
        } catch (Exception e) {
            return false;
        }
    }

    /** Ждём OTP, ошибку email, капчу или окончание спиннера на «Далее». */
    private boolean waitForYouDoLoginProgress(Page page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isOtpFieldVisible(page) || isYouDoLoggedIn(page) || isEmailErrorPresent(page)) {
                return true;
            }
            if (smartCaptchaSupport.isLoginCaptchaGate(page)) {
                return true;
            }
            if (!isYouDoEmailSubmitLoading(page) && !isEmailFieldVisible(page)) {
                return true;
            }
            page.waitForTimeout(450);
        }
        return isOtpFieldVisible(page) || isYouDoLoggedIn(page);
    }

    /**
     * Не переоткрываем модалку, если идёт submit, есть smart-token или ещё виден чекбокс капчи.
     */
    private boolean shouldRestoreYouDoEmailFormAfterCaptcha(Page page) {
        if (smartCaptchaSupport.isLoginCaptchaGate(page)
                || smartCaptchaSupport.isSmartCaptchaShellPresent(page)) {
            return false;
        }
        if (hasYouDoSmartTokenForSubmit(page) || isYouDoEmailSubmitLoading(page)) {
            return false;
        }
        if (isLoginModalOpen(page) || isEmailFieldVisible(page)) {
            return false;
        }
        return true;
    }

    /** Если на экране капча (в т.ч. скрытый shell) — только 2Captcha, без reopen модалки. */
    private StepResult<Void> resolveCaptchaBlockingStep(Page page, DecryptedCredential creds) {
        if (!smartCaptchaSupport.isLoginCaptchaGate(page)
                && !smartCaptchaSupport.isSmartCaptchaShellPresent(page)) {
            return null;
        }
        YouDoSmartCaptchaSupport.CaptchaKind kind = smartCaptchaSupport.detectCaptchaKind(page);
        YouDoLoginFsmState.log(log, YouDoLoginFsmState.CAPTCHA_GUARD, creds.login(), "resolve kind=" + kind);
        modalLayoutFix.apply(page);
        captchaService.revealYouDoLoginCaptchaIframe(page);
        if (!smartCaptchaSupport.passLoginSmartCaptcha(page, creds.login())) {
            return failYouDoCaptcha(page, creds.login(), "YouDo SmartCaptcha не пройдена (2Captcha)");
        }
        captchaService.resumeYandexSmartCaptchaForForm(page);
        captchaService.dismissSmartCaptchaOverlay(page);
        return null;
    }

    private void logFsmStage(YouDoLoginStage stage, String login, int iteration) {
        YouDoLoginFsmState fsm = switch (stage) {
            case SMART_CAPTCHA -> YouDoLoginFsmState.CAPTCHA_GUARD;
            case EMAIL_FORM -> YouDoLoginFsmState.POST_CAPTCHA_SUBMIT;
            case OTP_CODE -> YouDoLoginFsmState.WAIT_OTP;
            case LOGGED_IN -> YouDoLoginFsmState.DONE;
            case EMAIL_ERROR, UNKNOWN -> YouDoLoginFsmState.CAPTCHA_GUARD;
        };
        YouDoLoginFsmState.log(log, fsm, login, "loop=" + iteration + " stage=" + stage);
    }

    /** После капчи модалка иногда закрывается — снова открываем email и заполняем. */
    private void restoreYouDoEmailFormAfterCaptcha(Page page, DecryptedCredential creds) {
        if (page == null || page.isClosed() || isEmailFieldVisible(page) || isOtpFieldVisible(page)) {
            return;
        }
        if (smartCaptchaSupport.isSmartCaptchaShellPresent(page)
                || smartCaptchaSupport.isLoginCaptchaGate(page)) {
            log.info("АВТООТВЕТ: {} -> восстановление формы пропущено (капча в DOM), пользователь: {}",
                    getSiteName(), creds.login());
            return;
        }
        log.info("АВТООТВЕТ: {} -> восстановление формы email после капчи, пользователь: {}",
                getSiteName(), creds.login());
        dismissCookieBanner(page);
        clickHeaderLoginUntilModal(page, creds.login());
        openEmailLoginStep(page, creds.login());
        if (!isEmailFieldVisible(page)) {
            return;
        }
        try {
            String current = page.locator("input[name='login']").first().inputValue();
            if (current == null || current.isBlank() || !current.equals(creds.login())) {
                humanTypeField(page, "input[name='login']", creds.login());
                log.info("АВТООТВЕТ: {} -> email повторно заполнен: {}", getSiteName(), creds.login());
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> не удалось повторно заполнить email: {}", getSiteName(), e.getMessage());
        }
    }

    private StepResult<Void> failYouDoEmailSubmitStuck(Page page, String login) {
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> зависла отправка email после капчи, пользователь: " + login,
                AutoreplyErrorTypes.TIMEOUT);
        return StepResult.fail(StepType.SEND_AUTOREPLY, YOUDO_EMAIL_SUBMIT_STUCK_CODE,
                YOUDO_EMAIL_SUBMIT_STUCK_MESSAGE, captureScreenshot(page));
    }

    /**
     * Приоритет: SmartCaptcha → ошибка email → OTP → форма email → logged-in.
     */
    private YouDoLoginStage detectYouDoLoginStage(Page page) {
        if (page == null || page.isClosed()) {
            return YouDoLoginStage.UNKNOWN;
        }
        if (smartCaptchaSupport.isLoginCaptchaGate(page)
                || smartCaptchaSupport.isSmartCaptchaShellPresent(page)) {
            return YouDoLoginStage.SMART_CAPTCHA;
        }
        if (isEmailErrorPresent(page)) {
            return YouDoLoginStage.EMAIL_ERROR;
        }
        if (isOtpFieldVisible(page)) {
            return YouDoLoginStage.OTP_CODE;
        }
        if (isEmailFieldVisible(page)) {
            return YouDoLoginStage.EMAIL_FORM;
        }
        if (isYouDoLoggedIn(page)) {
            return YouDoLoginStage.LOGGED_IN;
        }
        return YouDoLoginStage.UNKNOWN;
    }

    private boolean isOtpFieldVisible(Page page) {
        String[] selectors = {
                "input[name='code']",
                "input[autocomplete='one-time-code']",
                "input[inputmode='numeric'][name='code']"
        };
        for (String selector : selectors) {
            try {
                Locator code = page.locator(selector).first();
                if (code.count() > 0 && code.isVisible()) {
                    return true;
                }
            } catch (Exception ignored) {
                // next
            }
        }
        try {
            return page.locator("text=код из письма").first().isVisible()
                    || page.locator("text=Введите код").first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isEmailFieldVisible(Page page) {
        try {
            Locator email = page.locator("input[name='login']").first();
            return email.count() > 0 && email.isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isYouDoLoggedIn(Page page) {
        try {
            if (isOtpFieldVisible(page) || isEmailFieldVisible(page)
                    || smartCaptchaSupport.isLoginCaptchaGate(page)) {
                return false;
            }
            Locator loginBtn = page.locator("[data-test='LoginButton']");
            return loginBtn.count() == 0 || !loginBtn.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private StepResult<Void> failYouDoCaptcha(Page page, String login, String fallbackMessage) {
        CaptchaFailureInfo info = captchaService != null
                ? captchaService.getLastFailure().orElse(null)
                : null;
        String errorCode = info != null ? info.codeName() : AutoreplyErrorTypes.CAPTCHA_FAILED;
        String message = info != null && info.userMessage() != null && !info.userMessage().isBlank()
                ? info.userMessage()
                : fallbackMessage;
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> капча не пройдена [" + errorCode + "], пользователь: " + login,
                AutoreplyErrorTypes.CAPTCHA_FAILED);
        return StepResult.fail(StepType.SEND_AUTOREPLY, errorCode, message, captureScreenshot(page));
    }

    @Override
    protected StepResult<Void> finalizeBeforeSessionSave(Page page, DecryptedCredential creds,
                                                         AutoreplyMode autoreplyMode) {
        if (!smartCaptchaSupport.isLoginCaptchaGate(page)) {
            return null;
        }
        if (!smartCaptchaSupport.passLoginSmartCaptcha(page, creds.login())) {
            return failYouDoCaptcha(page, creds.login(), "YouDo SmartCaptcha не пройдена перед сохранением сессии");
        }
        return null;
    }

    /** Дождаться позднего cookie-баннера и снять его до клика «Войти». */
    private void prepareYouDoPageForLogin(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            getCurrentManager().humanMouse(page);
            getCurrentManager().humanDelay(page);
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> human перед cookie-баннером: {}", getSiteName(), e.getMessage());
        }
        long deadline = System.currentTimeMillis() + COOKIE_BANNER_SETTLE_MS;
        while (System.currentTimeMillis() < deadline) {
            ensureCookieBannerDismissed(page);
            if (!isYouDoCookieConsentVisible(page)) {
                page.waitForTimeout(250);
            } else {
                break;
            }
        }
        ensureCookieBannerDismissed(page);
    }

    private void ensureCookieBannerDismissed(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            if (!isYouDoCookieConsentVisible(page)) {
                return;
            }
            dismissCookieBanner(page);
            page.waitForTimeout(200);
        }
    }

    private void dismissCookieBanner(Page page) {
        if (page == null || page.isClosed() || !isYouDoCookieConsentVisible(page)) {
            return;
        }
        String[] scopedSelectors = {
                "[class*='Cookie'] button:has-text('Хорошо')",
                "[id*='cookie' i] button:has-text('Хорошо')",
                "[data-test*='cookie' i] button:has-text('Хорошо')"
        };
        for (String selector : scopedSelectors) {
            try {
                Locator btn = page.locator(selector).first();
                if (btn.count() > 0 && btn.isVisible()) {
                    clickLocator(page, btn);
                    log.info("АВТООТВЕТ: {} -> cookie-баннер закрыт", getSiteName());
                    page.waitForTimeout(300);
                    return;
                }
            } catch (Exception ignored) {
                // next
            }
        }
        try {
            Object clicked = page.evaluate(
                    """
                            () => {
                              const isLoginDialog = (el) => {
                                if (!el) return false;
                                const t = (el.textContent || '');
                                return /Вход|Войти через|электронн/i.test(t);
                              };
                              const buttons = [...document.querySelectorAll('button')];
                              for (const b of buttons) {
                                if (b.offsetParent === null) continue;
                                const text = (b.textContent || '').trim();
                                if (!text.includes('Хорошо')) continue;
                                const dialog = b.closest('[role="dialog"], [class*="Modal"]');
                                if (dialog && isLoginDialog(dialog)) continue;
                                const r = b.getBoundingClientRect();
                                if (r.top < window.innerHeight * 0.45) continue;
                                b.click();
                                return true;
                              }
                              return false;
                            }
                            """);
            if (Boolean.TRUE.equals(clicked)) {
                log.info("АВТООТВЕТ: {} -> cookie-баннер закрыт (нижняя панель)", getSiteName());
                page.waitForTimeout(300);
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> cookie-баннер не найден или уже закрыт", getSiteName());
        }
    }

    private boolean isYouDoCookieConsentVisible(Page page) {
        try {
            Object visible = page.evaluate(
                    """
                            () => {
                              const roots = document.querySelectorAll(
                                '[class*="Cookie"], [id*="cookie" i], [data-test*="cookie" i]');
                              for (const root of roots) {
                                if (root.offsetParent === null) continue;
                                const btn = root.querySelector('button');
                                if (btn && /Хорошо/i.test(btn.textContent || '')) return true;
                              }
                              for (const b of document.querySelectorAll('button')) {
                                if (b.offsetParent === null) continue;
                                if (!/Хорошо/.test((b.textContent || '').trim())) continue;
                                const dialog = b.closest('[role="dialog"]');
                                if (dialog && /Вход|Войти/i.test(dialog.textContent || '')) continue;
                                const r = b.getBoundingClientRect();
                                if (r.top > window.innerHeight * 0.45) return true;
                              }
                              return false;
                            }
                            """);
            return Boolean.TRUE.equals(visible);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLoginWithEmailButtonVisible(Page page) {
        return isAnySelectorVisible(page, LOGIN_EMAIL_BUTTON_SELECTORS);
    }

    private boolean isLoginModalOpen(Page page) {
        if (isEmailFieldVisible(page)) {
            return true;
        }
        if (isLoginWithEmailButtonVisible(page)) {
            return true;
        }
        try {
            Locator dialog = page.locator("div[role='dialog']").first();
            if (dialog.count() > 0 && dialog.isVisible()) {
                String text = dialog.innerText();
                return text != null && (text.contains("Вход") || text.contains("Войти"));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return false;
    }

    private boolean clickHeaderLoginUntilModal(Page page, String login) {
        if (page == null || page.isClosed()) {
            return false;
        }
        ensureCookieBannerDismissed(page);
        if (isLoginModalOpen(page)) {
            return true;
        }

        long deadline = System.currentTimeMillis() + LOGIN_CLICK_RETRY_MS;
        int round = 0;
        while (System.currentTimeMillis() < deadline) {
            if (page.isClosed()) {
                return false;
            }
            round++;
            ensureCookieBannerDismissed(page);
            if (isLoginModalOpen(page)) {
                return true;
            }

            boolean clicked = false;
            for (String selector : LOGIN_BUTTON_SELECTORS) {
                try {
                    Locator locator = page.locator(selector).first();
                    if (locator.count() == 0 || !locator.isVisible()) {
                        continue;
                    }
                    locator.scrollIntoViewIfNeeded();
                    ensureCookieBannerDismissed(page);
                    clickLocator(page, locator);
                    clicked = true;
                    log.info("АВТООТВЕТ: {} -> кнопка 'Войти' нажата ({}), раунд {}, пользователь: {}",
                            getSiteName(), selector, round, login);
                    page.waitForTimeout(500);
                    if (waitForLoginModalVisible(page, LOGIN_MODAL_WAIT_MS)) {
                        return true;
                    }
                    if (isYouDoCookieConsentVisible(page)) {
                        log.info("АВТООТВЕТ: {} -> cookie перекрыл модалку после «Войти», повтор, пользователь: {}",
                                getSiteName(), login);
                        break;
                    }
                } catch (Exception e) {
                    log.debug("АВТООТВЕТ: {} -> клик 'Войти' '{}': {}", getSiteName(), selector, e.getMessage());
                }
            }

            if (!clicked) {
                ensureCookieBannerDismissed(page);
                clickHeaderLoginViaJs(page, login);
                page.waitForTimeout(600);
                if (waitForLoginModalVisible(page, LOGIN_MODAL_WAIT_MS)) {
                    return true;
                }
            }
            page.waitForTimeout(400);
        }
        return isLoginModalOpen(page);
    }

    private void clickHeaderLoginViaJs(Page page, String login) {
        try {
            Object ok = page.evaluate(
                    """
                            () => {
                              const el = document.querySelector("header [data-test='LoginButton']")
                                || document.querySelector("[data-test='LoginButton']");
                              if (!el) return false;
                              el.scrollIntoView({ block: 'center', inline: 'center' });
                              el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                              if (typeof el.click === 'function') el.click();
                              return true;
                            }
                            """);
            if (Boolean.TRUE.equals(ok)) {
                log.info("АВТООТВЕТ: {} -> кнопка 'Войти' (JS), пользователь: {}", getSiteName(), login);
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> JS-клик 'Войти': {}", getSiteName(), e.getMessage());
        }
    }

    /** Ждём видимый контент модалки, не hidden nodes в DOM. */
    private boolean waitForLoginModalVisible(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ensureCookieBannerDismissed(page);
        while (System.currentTimeMillis() < deadline) {
            if (isLoginModalOpen(page)) {
                return true;
            }
            if (isYouDoCookieConsentVisible(page)) {
                ensureCookieBannerDismissed(page);
            }
            page.waitForTimeout(200);
        }
        ensureCookieBannerDismissed(page);
        return isLoginModalOpen(page);
    }

    /** Модалка открыта → клик «Войти через email», пока не видно поле login. */
    private boolean openEmailLoginStep(Page page, String login) {
        dismissCookieBanner(page);
        if (isEmailFieldVisible(page)) {
            return true;
        }

        long deadline = System.currentTimeMillis() + LOGIN_CLICK_RETRY_MS;
        int round = 0;
        while (System.currentTimeMillis() < deadline) {
            round++;
            if (round == 1 || round % 6 == 0) {
                dismissCookieBanner(page);
            }
            if (isEmailFieldVisible(page)) {
                return true;
            }

            if (!isLoginWithEmailButtonVisible(page) && !isLoginModalOpen(page)) {
                log.debug("АВТООТВЕТ: {} -> модалка входа пропала, снова «Войти», пользователь: {}",
                        getSiteName(), login);
                clickHeaderLoginUntilModal(page, login);
            }

            if (clickWithFallback(page, LOGIN_EMAIL_BUTTON_SELECTORS, 4_000, "Войти через email")
                    || clickLoginWithEmailViaJs(page, login)) {
                page.waitForTimeout(700);
                if (isEmailFieldVisible(page)) {
                    return true;
                }
            }

            if (clickFirstVisible(page, LOGIN_EMAIL_BUTTON_SELECTORS, "Войти через email (повтор)")) {
                page.waitForTimeout(700);
                if (isEmailFieldVisible(page)) {
                    return true;
                }
            }

            try {
                Locator byText = page.getByText("электронную почту", new Page.GetByTextOptions().setExact(false)).first();
                if (byText.count() > 0 && byText.isVisible()) {
                    clickLocator(page, byText);
                    log.info("АВТООТВЕТ: {} -> клик по тексту «электронную почту», пользователь: {}", getSiteName(), login);
                    page.waitForTimeout(700);
                    if (isEmailFieldVisible(page)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> клик «электронную почту»: {}", getSiteName(), e.getMessage());
            }

            page.waitForTimeout(400);
        }
        return isEmailFieldVisible(page);
    }

    private boolean clickLoginWithEmailViaJs(Page page, String login) {
        try {
            Object ok = page.evaluate(
                    """
                            () => {
                              const sel = "[data-test='LoginWithEmailButton']";
                              const el = document.querySelector(sel)
                                || [...document.querySelectorAll('button')].find(b =>
                                  /электронн|email/i.test((b.textContent || '')));
                              if (!el || el.offsetParent === null) return false;
                              el.scrollIntoView({ block: 'center', inline: 'center' });
                              el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                              if (typeof el.click === 'function') el.click();
                              return true;
                            }
                            """);
            if (Boolean.TRUE.equals(ok)) {
                log.info("АВТООТВЕТ: {} -> Войти через email (JS), пользователь: {}", getSiteName(), login);
                return true;
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> JS «Войти через email»: {}", getSiteName(), e.getMessage());
        }
        return false;
    }

    private boolean clickFirstVisible(Page page, String[] selectors, String step) {
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() == 0 || !locator.isVisible()) {
                    continue;
                }
                locator.scrollIntoViewIfNeeded();
                clickLocator(page, locator);
                log.info("АВТООТВЕТ: {} -> {} (селектор: {})", getSiteName(), step, selector);
                return true;
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> {} '{}': {}", getSiteName(), step, selector, e.getMessage());
            }
        }
        return false;
    }

    private boolean isAnySelectorVisible(Page page, String... selectors) {
        for (String selector : selectors) {
            try {
                Locator loc = page.locator(selector).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    return true;
                }
            } catch (Exception ignored) {
                // next
            }
        }
        return false;
    }

    private boolean clickWithFallback(Page page, String[] selectors, int timeoutMs, String step) {
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector).first();
                locator.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(timeoutMs));
                locator.scrollIntoViewIfNeeded();
                clickLocator(page, locator);
                log.info("АВТООТВЕТ: {} -> {} (селектор: {})", getSiteName(), step, selector);
                return true;
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> {} не удалось через '{}': {}", getSiteName(), step, selector, e.getMessage());
            }
        }
        return false;
    }

    /** Прогрев курсора и паузы перед действиями (как FL.ru / Weblancer). */
    private void humanWarmupYouDo(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            humanWarmup(page);
            getCurrentManager().humanScroll(page);
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> humanWarmupYouDo: {}", getSiteName(), e.getMessage());
        }
    }

    /** Печать в поле с паузами; при сбое — обычный fill. */
    private void humanTypeField(Page page, String selector, String text) {
        if (page == null || page.isClosed() || text == null) {
            return;
        }
        try {
            getCurrentManager().humanMouse(page);
            getCurrentManager().humanDelay(page);
            getCurrentManager().humanType(page, selector, text);
        } catch (Exception e) {
            log.warn("АВТООТВЕТ: {} -> humanType '{}' не удался, fill: {}", getSiteName(), selector, e.getMessage());
            page.fill(selector, text);
        }
    }

    /** Клик через HumanInput; при перекрытии — штатный click, затем force. */
    private void clickLocator(Page page, Locator locator) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            getCurrentManager().humanDelay(page);
            HumanInput.click(page, locator);
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> humanClick не удался: {}", getSiteName(), e.getMessage());
            try {
                locator.click(new Locator.ClickOptions().setTimeout(8_000));
            } catch (Exception e2) {
                locator.click(new Locator.ClickOptions().setForce(true).setTimeout(8_000));
            }
        }
    }

    /** После капчи / спиннера: сначала human-клик, при необходимости force. */
    private void clickLocatorForceIfNeeded(Page page, Locator locator) {
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            getCurrentManager().humanDelay(page);
            HumanInput.click(page, locator);
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> humanClick (force path): {}", getSiteName(), e.getMessage());
            locator.click(new Locator.ClickOptions().setForce(true).setTimeout(8_000));
        }
    }

    private boolean waitForEmailError(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isEmailErrorPresent(page)) {
                return true;
            }
            page.waitForTimeout(300);
        }
        return isEmailErrorPresent(page);
    }

    private boolean isEmailErrorPresent(Page page) {
        for (String selector : EMAIL_ERROR_SELECTORS) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.isVisible()) {
                    log.debug("АВТООТВЕТ: {} -> ошибка email обнаружена (селектор: {})", getSiteName(), selector);
                    return true;
                }
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> проверка ошибки email через '{}' не удалась: {}", getSiteName(), selector, e.getMessage());
            }
        }
        try {
            Locator partialMatch = page.getByText("Неправильный адрес", new Page.GetByTextOptions().setExact(false)).first();
            if (partialMatch.isVisible()) {
                log.debug("АВТООТВЕТ: {} -> ошибка email обнаружена (getByText частичное совпадение)", getSiteName());
                return true;
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> getByText проверка ошибки email не удалась: {}", getSiteName(), e.getMessage());
        }
        return false;
    }

    @Override
    protected StepResult<Void> processAutoReply(Page page, AiNotificationPayload payload, DecryptedCredential creds) {
        String link = payload.getOrder().getLink();
        String login = creds.login();
        log.info("АВТООТВЕТ: {} -> НАЧАЛО ОБРАБОТКИ ЗАКАЗА: {}, пользователь: {}", getSiteName(), link, login);
        try {
            StepResult<Void> tariffResult = checkTariff(page, login);
            if (tariffResult.failed()) {
                return tariffResult; // возвращаем ошибку, если профиль бесплатный или откликов недостаточно
            }
            log.info("АВТООТВЕТ: {} -> тариф активен, продолжаем обработку заказа", getSiteName());
            page.navigate(link);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            log.info("АВТООТВЕТ: {} -> страница заказа открыта, пользователь: {}", getSiteName(), login);
            humanWarmupYouDo(page);
            takeScreenshot(page, getSiteName(), payload.getUser().getUuid(), "order_page");
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ОТКРЫТЬ ЗАКАЗ, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.ERROR_OPEN_PAGE);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось открыть заказ: " + e.getMessage(), captureScreenshot(page));
        }

        if (!clickOrFail(page, "button:has-text('Откликнуться')", "Кнопка 'Откликнуться'")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА 'Откликнуться', пользователь: " + login,
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка 'Откликнуться' не найдена", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> кнопка 'Откликнуться' нажата, пользователь: {}", getSiteName(), login);

        if (!waitOrFail(page, "input[placeholder='В рублях']", "Поле цены")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ ЦЕНЫ, пользователь: " + login,
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле цены не найдено", captureScreenshot(page));
        }

        try {
            humanTypeField(page, "input[placeholder='В рублях']", String.valueOf(defaultPrice));
            log.info("АВТООТВЕТ: {} -> цена установлена: {}, пользователь: {}", getSiteName(), defaultPrice, login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ЦЕНУ, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить цену: " + e.getMessage(), captureScreenshot(page));
        }

        if (!waitOrFail(page, "textarea.Textarea_textarea__FjgmX", "Поле текста отклика")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ ТЕКСТА ОТКЛИКА, пользователь: " + login,
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле текста отклика не найдено", captureScreenshot(page));
        }

        try {
            String replyText = payload.getDecision().reply();
            humanTypeField(page, "textarea.Textarea_textarea__FjgmX", replyText);
            log.info("АВТООТВЕТ: {} -> текст ответа вставлен, длина: {}, пользователь: {}", getSiteName(),
                    replyText != null ? replyText.length() : 0, login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ТЕКСТ ОТКЛИКА, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить текст отклика: " + e.getMessage(), captureScreenshot(page));
        }

        if (!waitOrFail(page, "button.NewButton_button__2D_5n:has-text('Далее')", "Кнопка 'Далее'")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА 'Далее', пользователь: " + login,
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка 'Далее' не найдена", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> кнопка 'Далее' найдена, пользователь: {}", getSiteName(), login);

        Locator nextBtn = page.locator("button.NewButton_button__2D_5n:has-text('Далее')");
        try {
            page.waitForCondition(nextBtn::isEnabled, new Page.WaitForConditionOptions().setTimeout(5000));
            log.info("АВТООТВЕТ: {} -> кнопка 'Далее' активна, пользователь: {}", getSiteName(), login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> КНОПКА 'Далее' НЕАКТИВНА, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка 'Далее' неактивна", captureScreenshot(page));
        }

        if (sendRequest) {
            try {
                getCurrentManager().humanDelay(page);
                clickLocator(page, nextBtn);
                log.info("АВТООТВЕТ: {} -> ЗАЯВКА УСПЕШНО ОТПРАВЛЕНА, пользователь: {}", getSiteName(), login);
            } catch (Exception e) {
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ НАЖАТЬ КНОПКУ 'Далее', пользователь: "
                                + login + ", ошибка: " + e.getMessage(),
                        AutoreplyErrorTypes.BUTTON_NOT_FOUND);
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось нажать кнопку 'Далее': " + e.getMessage(), captureScreenshot(page));
            }
        } else {
            log.info("АВТООТВЕТ: {} -> ЗАЯВКА НЕ ОТПРАВЛЕНА (sendRequest=false), пользователь: {}", getSiteName(), login);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Заявка не отправлена (sendRequest=false)", captureScreenshot(page));
        }

        page.waitForTimeout(3000);
        return StepResult.ok(StepType.SEND_AUTOREPLY, null);
    }

    private StepResult<Void> checkTariff(Page page, String login){
        page.navigate(getProfileUrl(page));
        page.waitForLoadState(LoadState.NETWORKIDLE);
        log.info("АВТООТВЕТ: {} -> страница профиля открыта, пользователь: {}", getSiteName(), login);
        boolean isTariffTab = youdoTariffChecker.isTariffsTabPresent(page);
        if (!isTariffTab) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> ПРОФИЛЬ БЕСПЛАТНЫЙ, пользователь: " + login,
                    AutoreplyErrorTypes.TARIFF_LIMIT);
            return StepResult.fail(StepType.SEND_AUTOREPLY,
                    "Профиль бесплатный. Отклик не удастся отправить ", captureScreenshot(page));
        }
        int remainingResponses = youdoTariffChecker.getRemainingResponses(page);
        if (remainingResponses > 0) {
            return StepResult.ok(StepType.SEND_AUTOREPLY, null);
        }
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> ОТКЛИКОВ МАЛО, пользователь: " + login,
                AutoreplyErrorTypes.TARIFF_LIMIT);
        return StepResult.fail(StepType.SEND_AUTOREPLY,
                "Количество откликов мало для отправки автоответа ", captureScreenshot(page));
    }

    private String getProfileUrl(Page page){
        // Находим элемент
        Locator avatarLink = page.locator("a.avatar_block__KOT6G.avatar_s32Square__FqL_i.js-toggleUserNavigationBtn");
        // Получаем значение атрибута href
        String href = avatarLink.getAttribute("href");
        // Формируем полный URL
        String profileUrl = "https://youdo.com" + href;
        log.info("Профиль пользователя: {}", profileUrl);
        return profileUrl;
    }
}