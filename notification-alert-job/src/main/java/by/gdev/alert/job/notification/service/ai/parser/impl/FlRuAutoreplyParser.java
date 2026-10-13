package by.gdev.alert.job.notification.service.ai.parser.impl;

import by.gdev.alert.job.notification.model.AutoreplyMode;
import by.gdev.alert.job.notification.model.dto.AiNotificationPayload;
import by.gdev.alert.job.notification.model.dto.DecryptedCredential;
import by.gdev.alert.job.notification.service.ai.flru.FlRuValidateCaptchaSupport;
import by.gdev.alert.job.notification.service.ai.metrics.errors.AutoreplyErrorTypes;
import by.gdev.alert.job.notification.service.ai.otp.OtpService;
import by.gdev.alert.job.notification.service.ai.parser.AutoreplyPlaywrightParser;
import by.gdev.alert.job.notification.service.ai.proxy.AssignedProxyService;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.alert.job.notification.service.ai.recovery.AutoreplyFailureAction;
import by.gdev.common.model.SiteName;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureInfo;
import by.gdev.common.service.playwright.captcha.CaptchaService;
import by.gdev.common.service.playwright.flru.FlRuPlaywrightGuards;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
public class FlRuAutoreplyParser extends AutoreplyParser implements AutoreplyPlaywrightParser {

    private static final String[] LOGIN_ERROR_SELECTORS = {
            "div.invalid-feedback.mt-8.d-block:has-text('Неверный логин/пароль')",
            "text=Неверный логин/пароль"
    };

    /** Признаки страницы FL.ru "Введите код из письма для входа" (вход по коду на e-mail). */
    private static final String[] EMAIL_CODE_SELECTORS = {
            "form.js-send-confirmation-code",
            "h2:has-text('Введите код из письма для входа')",
            "a[href*='/account/repeat-send-code']"
    };

    private static final String CODE_FORM = "form.js-send-confirmation-code";

    private static final String RESEND_CODE_LINK = "a[href*='/account/repeat-send-code/']";

    /** Перебираются по порядку: от самого точного описания поля к самому общему. */
    private static final String[] CODE_INPUT_SELECTORS = {
            CODE_FORM + " input[name*='code']",
            CODE_FORM + " input[type='text']",
            CODE_FORM + " input:not([type='hidden']):not([type='submit'])"
    };

    private static final String[] CODE_SUBMIT_SELECTORS = {
            CODE_FORM + " button[type='submit']",
            CODE_FORM + " input[type='submit']",
            CODE_FORM + " button"
    };

    private static final String CODE_FORM_NOT_PARSED_MESSAGE =
            "FL.ru запросил код из письма, но поле для его ввода не удалось найти на странице. "
                    + "Разметка формы записана в лог — сообщите об этом в поддержку.";

    private static final String CODE_NOT_RECEIVED_MESSAGE =
            "FL.ru запросил код подтверждения, но письмо с кодом не пришло на почтовый ящик сервиса "
                    + "за отведённое время. Проверьте, что письма от no_reply@free-lance.ru доходят и не попадают в спам.";

    private static final String CODE_REJECTED_MESSAGE =
            "FL.ru не принял код из письма — форма подтверждения осталась на экране. "
                    + "Код мог устареть, попробуйте запустить вход ещё раз.";

    private static final String STAGE_LOOP_EXHAUSTED_MESSAGE =
            "FL.ru: слишком много переходов между этапами входа (логин / капча / код из письма). "
                    + "Попробуйте ещё раз.";

    /** После IP-капчи FL.ru часто редиректит на главную — нужен явный переход на вход. */
    private static final String FLRU_LOGIN_URL = "https://www.fl.ru/account/login/?return=%2F";

    private static final String REPLY_TEXT_FIELD = "#el-descr";

    /** Попап «купите отклик» — на аккаунте нет доступных откликов. */
    private static final String PAYWALL_POPUP = "#project_answer_popup";

    private static final String[] OPEN_REPLY_BUTTON_SELECTORS = {
            "#reply_offer",
            "a[name='new_offer']",
            "a[data-popup='project_answer_popup']",
            "a.ui-button._primary:has-text('Откликнуться')",
            "a:has-text('Откликнуться')"
    };

    private static final String NO_RESPONSES_USER_MESSAGE =
            "На FL.ru закончились доступные отклики (или не хватает средств на балансе). "
                    + "Пополните баланс или купите отклики на сайте, чтобы сервис мог отправлять автоответы.";

    private static final String EXECUTOR_SELECTED_MARKER = "Заказчик выбрал исполнителя";

    private static final String[] EXECUTOR_SELECTED_SELECTORS = {
            "text=Заказчик выбрал исполнителя",
            "text=Заказчик выбрал исполнителя:",
            ":text-is('Заказчик выбрал исполнителя:')"
    };

    private static final String EXECUTOR_SELECTED_USER_MESSAGE =
            "На FL.ru заказчик уже выбрал исполнителя по этому проекту — новые отклики не принимаются. "
                    + "Автоответ не отправлен.";

    private static final String SITE_MAINTENANCE_USER_MESSAGE =
            "На FL.ru проводятся технические работы — сайт временно недоступен. "
                    + "Попробуйте отправить автоответ позже.";

    /** Сколько раз подряд можно обрабатывать validate/email в одном входе (они могут чередоваться). */
    private static final int LOGIN_STAGE_MAX_ITERATIONS = 12;

    /** Этапы входа FL.ru: validate-captcha и код из письма могут появляться повторно. */
    private enum FlRuLoginStage {
        ANTI_DDOS,
        SITE_MAINTENANCE,
        VALIDATE_CAPTCHA,
        EMAIL_CODE,
        LOGIN_ERROR,
        LOGIN_FORM,
        LOGGED_IN,
        UNKNOWN
    }

    private final CaptchaService captchaService;
    private final OtpService otpService;
    private final FlRuValidateCaptchaSupport validateCaptchaSupport;

    @Value("${credential.validation.otp.timeout.ms:120000}")
    private long otpTimeoutMs;

    /** Сколько раз ждать письмо (первая отправка + повторные по ссылке на сайте). */
    @Value("${parser.autoreply.fl.ru.otp.resend.max-rounds:3}")
    private int otpResendMaxRounds;

    /** Сколько ждать письмо в каждом раунде, прежде чем нажать «Отправить повторно». */
    @Value("${parser.autoreply.fl.ru.otp.wait-per-round.ms:90000}")
    private long otpWaitPerRoundMs;

    /** Максимум ждать, пока на странице снова станет доступна ссылка повторной отправки. */
    @Value("${parser.autoreply.fl.ru.otp.resend.cooldown.max-wait.ms:120000}")
    private long otpResendCooldownMaxWaitMs;

    @Value("${parser.autoreply.fl.ru.ddos.proxy.max-attempts:3}")
    private int ddosProxyMaxAttempts;

    @Value("${parser.autoreply.headless.fl.ru:true}")
    private void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Value("${parser.autoreply.proxy.fl.ru:false}")
    private void setProxy(boolean proxy) {
        this.proxy = proxy;
    }

    @Value("${parser.autoreply.send.request.fl.ru:true}")
    private void setOnSendRequest(boolean sendRequest) {
        this.sendRequest = sendRequest;
    }

    public FlRuAutoreplyParser(AssignedProxyService assignedProxyService, CaptchaService captchaService,
                               OtpService otpService, FlRuValidateCaptchaSupport validateCaptchaSupport) {
        super(assignedProxyService);
        this.captchaService = captchaService;
        this.otpService = otpService;
        this.validateCaptchaSupport = validateCaptchaSupport;
    }

    @Override
    protected String sessionCookieCheckUrl() {
        return "https://www.fl.ru";
    }

    @Override
    void safeNavigate(Page page, String url) {
        super.safeNavigate(page, url);
        StepResult<Void> maintenance = failIfSiteMaintenance(page, "navigate");
        if (maintenance != null) {
            throw new FlRuStepFailureException(maintenance);
        }
        StepResult<Void> gate = ensurePastValidateCaptcha(page, "navigate");
        if (gate != null) {
            throw new FlRuStepFailureException(gate);
        }
        resumeNavigationAfterValidateCaptcha(page, url, "navigate");
    }

    /**
     * После {@code /validate-captcha} сайт часто оставляет на {@code https://www.fl.ru/} вместо исходного URL.
     */
    private void resumeNavigationAfterValidateCaptcha(Page page, String targetUrl, String login) {
        if (targetUrl == null || targetUrl.isBlank() || isValidateCaptchaPage(page)) {
            return;
        }
        if (!needsResumeToTarget(page.url(), targetUrl)) {
            return;
        }
        log.info("АВТООТВЕТ: {} -> после validate-captcha с {} на {}, пользователь: {}",
                getSiteName(), page.url(), targetUrl, login);
        super.safeNavigate(page, targetUrl);
        StepResult<Void> gate = ensurePastValidateCaptcha(page, login);
        if (gate != null) {
            throw new FlRuStepFailureException(gate);
        }
    }

    private StepResult<Void> failFlRuCaptcha(Page page, String login, String fallbackMessage) {
        CaptchaFailureInfo info = captchaService.getLastFailure().orElse(null);
        String errorCode = info != null ? info.codeName() : AutoreplyErrorTypes.CAPTCHA_FAILED;
        String message = info != null && info.userMessage() != null && !info.userMessage().isBlank()
                ? info.userMessage()
                : fallbackMessage;
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> капча не пройдена [" + errorCode + "], пользователь: " + login,
                AutoreplyErrorTypes.CAPTCHA_FAILED);
        return StepResult.fail(StepType.SEND_AUTOREPLY, errorCode, message, captureScreenshot(page));
    }

    private static boolean needsResumeToTarget(String currentUrl, String targetUrl) {
        if (currentUrl == null) {
            return true;
        }
        if (currentUrl.contains(FlRuValidateCaptchaSupport.VALIDATE_CAPTCHA_PATH)) {
            return false;
        }
        if (targetUrl.contains("/account/login") && !currentUrl.contains("/account/login")) {
            return true;
        }
        try {
            java.net.URI target = java.net.URI.create(targetUrl);
            java.net.URI current = java.net.URI.create(currentUrl);
            String targetPath = normalizePath(target.getPath());
            String currentPath = normalizePath(current.getPath());
            if (targetPath.isEmpty()) {
                return false;
            }
            return !currentPath.equals(targetPath) && !currentPath.startsWith(targetPath);
        } catch (Exception e) {
            return !currentUrl.startsWith(targetUrl);
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private void ensureOnLoginPage(Page page, String login) {
        if (page.locator("input[name='username']").count() > 0) {
            return;
        }
        StepResult<Void> validateGate = ensurePastValidateCaptcha(page, login);
        if (validateGate != null) {
            throw new FlRuStepFailureException(validateGate);
        }
        if (page.locator("input[name='username']").count() > 0) {
            return;
        }
        log.info("АВТООТВЕТ: {} -> форма входа не на экране (url={}), переход на login, пользователь: {}",
                getSiteName(), page.url(), login);
        super.safeNavigate(page, FLRU_LOGIN_URL);
        resumeNavigationAfterValidateCaptcha(page, FLRU_LOGIN_URL, login);
        StepResult<Void> gate = ensurePastValidateCaptcha(page, login);
        if (gate != null) {
            throw new FlRuStepFailureException(gate);
        }
        if (page.locator("input[name='username']").count() > 0) {
            return;
        }
        clickFlRuLoginEntryLink(page, login);
        page.waitForTimeout(800);
        if (page.locator("input[name='username']").count() == 0) {
            super.safeNavigate(page, FLRU_LOGIN_URL);
            resumeNavigationAfterValidateCaptcha(page, FLRU_LOGIN_URL, login);
            StepResult<Void> gate2 = ensurePastValidateCaptcha(page, login);
            if (gate2 != null) {
                throw new FlRuStepFailureException(gate2);
            }
        }
    }

    private void clickFlRuLoginEntryLink(Page page, String login) {
        String[] selectors = {
                "a[href*='/account/login']",
                "a:has-text('Вход')",
                "a:has-text('Войти')",
                ".header-login a",
                "header a[href*='login']"
        };
        for (String selector : selectors) {
            Locator loc = page.locator(selector);
            if (loc.count() == 0) {
                continue;
            }
            try {
                Locator first = loc.first();
                if (first.isVisible()) {
                    first.click(new Locator.ClickOptions().setTimeout(5000));
                    log.info("АВТООТВЕТ: {} -> нажата ссылка входа ({}), пользователь: {}",
                            getSiteName(), selector, login);
                    page.waitForTimeout(1200);
                    return;
                }
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> клик входа '{}' не удался: {}", getSiteName(), selector, e.getMessage());
            }
        }
    }

    @Override
    protected int autoreplyProxySwitchMaxAttempts() {
        return Math.max(1, ddosProxyMaxAttempts);
    }

    @Override
    protected boolean skipLoginAfterVerifyFailure(StepResult<Void> verifyResult, AutoreplyMode autoreplyMode) {
        if (autoreplyMode == AutoreplyMode.LOGIN_ONLY) {
            return false;
        }
        return resolveFailureAction(verifyResult) == AutoreplyFailureAction.ROTATE_PROXY;
    }

    @Override
    protected StepResult<Void> verifyExistingSession(Page page, DecryptedCredential creds, AutoreplyMode mode) {
        StepResult<Void> verified = super.verifyExistingSession(page, creds, mode);
        if (verified == null || verified.failed()) {
            return verified;
        }
        StepResult<Void> gates = clearFlRuPostLoginGates(page, creds);
        return gates != null ? gates : verified;
    }

    @Override
    protected StepResult<Void> login(Page page, AiNotificationPayload payload, DecryptedCredential creds, AutoreplyMode mode) {
        log.info("АВТООТВЕТ: {} -> НАЧАЛО ЛОГИНА, пользователь: {}", getSiteName(), creds.login());

        try {
            safeNavigate(page, FLRU_LOGIN_URL);
            ensureOnLoginPage(page, creds.login());
            log.info("АВТООТВЕТ: {} -> страница логина, url={}, пользователь: {}",
                    getSiteName(), page.url(), creds.login());

            StepResult<Void> maintenance = failIfSiteMaintenance(page, creds.login());
            if (maintenance != null) {
                return maintenance;
            }

            StepResult<Void> pre = runLoginStageLoop(page, creds, true, false);
            if (pre != null && pre.failed()) {
                return pre;
            }
            if (detectLoginStage(page) == FlRuLoginStage.LOGGED_IN) {
                log.info("АВТООТВЕТ: {} -> ЛОГИН УСПЕШЕН (без формы), пользователь: {}", getSiteName(), creds.login());
                setOtp(payload, null, false);
                return StepResult.ok(StepType.SEND_AUTOREPLY, null);
            }

            humanWarmup(page);

            StepResult<Void> filled = fillAndSubmitLoginForm(page, creds);
            if (filled != null && filled.failed()) {
                return filled;
            }

            waitForLoginCompletionOrEmailCode(page, 15_000);
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(12_000));
                log.info("АВТООТВЕТ: {} -> страница загружена после входа, пользователь: {}", getSiteName(), creds.login());
            } catch (Exception e) {
                FlRuLoginStage stage = detectLoginStage(page);
                if (stage == FlRuLoginStage.LOGIN_FORM || stage == FlRuLoginStage.LOGIN_ERROR) {
                    report(Level.WARN, log,
                            "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ДОЖДАТЬСЯ ЗАГРУЗКИ ПОСЛЕ ВХОДА, пользователь: "
                                    + creds.login() + ", ошибка: " + e.getMessage(),
                            AutoreplyErrorTypes.TIMEOUT);
                    return StepResult.fail(StepType.SEND_AUTOREPLY,
                            "Не удалось дождаться загрузки после входа: " + e.getMessage(), captureScreenshot(page));
                }
                log.debug("АВТООТВЕТ: {} -> NETWORKIDLE не достигнут после входа (этап={}), продолжаем, пользователь: {}",
                        getSiteName(), stage, creds.login());
            }

            StepResult<Void> afterSubmit = runLoginStageLoop(page, creds, false, true);
            if (afterSubmit != null && afterSubmit.failed()) {
                return afterSubmit;
            }

            maintenance = failIfSiteMaintenance(page, creds.login());
            if (maintenance != null) {
                return maintenance;
            }

            FlRuLoginStage stage = detectLoginStage(page);
            if (stage == FlRuLoginStage.SITE_MAINTENANCE) {
                return failIfSiteMaintenance(page, creds.login());
            }
            if (stage == FlRuLoginStage.LOGGED_IN) {
                log.info("АВТООТВЕТ: {} -> ЛОГИН УСПЕШЕН, пользователь: {}", getSiteName(), creds.login());
                setOtp(payload, null, false);
                return StepResult.ok(StepType.SEND_AUTOREPLY, null);
            }
            // EMAIL_CODE обработан в loop; checkAfterLogin подхватит, если форма ещё на экране
            // (например, после restore session). Здесь ok — дальше stage-loop в checkAfterLogin.
            if (stage == FlRuLoginStage.EMAIL_CODE) {
                log.info("АВТООТВЕТ: {} -> FL.ru запросил код из письма (url={}), пользователь: {}",
                        getSiteName(), page.url(), creds.login());
                return StepResult.ok(StepType.SEND_AUTOREPLY, null);
            }
            if (stage == FlRuLoginStage.LOGIN_FORM || stage == FlRuLoginStage.LOGIN_ERROR) {
                if (isLoginErrorPresent(page)) {
                    report(Level.WARN, log,
                            "АВТООТВЕТ: " + getSiteName() + " -> НЕВЕРНЫЙ ЛОГИН/ПАРОЛЬ, пользователь: " + creds.login(),
                            AutoreplyErrorTypes.LOGIN_FAILED);
                    return StepResult.fail(StepType.SEND_AUTOREPLY, "Неверный логин/пароль", captureScreenshot(page));
                }
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> ВХОД НЕ ВЫПОЛНЕН, остались на странице логина, пользователь: " + creds.login(),
                        AutoreplyErrorTypes.LOGIN_FAILED);
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Остались на странице логина", captureScreenshot(page));
            }

            log.info("АВТООТВЕТ: {} -> ЛОГИН УСПЕШЕН (этап={}), пользователь: {}", getSiteName(), stage, creds.login());
            setOtp(payload, null, false);
            return StepResult.ok(StepType.SEND_AUTOREPLY, null);

        } catch (FlRuStepFailureException stepFailure) {
            return stepFailure.getStepResult();
        } catch (Exception e) {
            log.error("АВТООТВЕТ: {} -> ОШИБКА ПРИ ЛОГИНЕ, пользователь: {}, ошибка: {}", getSiteName(), creds.login(), e.getMessage(), e);
            report(Level.ERROR, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> ОШИБКА ПРИ ЛОГИНЕ, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.LOGIN_FAILED);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Ошибка при логине: " + e.getMessage(), captureScreenshot(page));
        }
    }

    /**
     * Заполнение формы логина + SmartCaptcha + submit. Без пост-гейтов.
     *
     * @return ошибка или {@code null} если форма отправлена
     */
    private StepResult<Void> fillAndSubmitLoginForm(Page page, DecryptedCredential creds) {
        StepResult<Void> maintenance = failIfSiteMaintenance(page, creds.login());
        if (maintenance != null) {
            return maintenance;
        }
        if (!waitOrFail(page, "input[name='username']", "Поле логина")) {
            ensureOnLoginPage(page, creds.login());
            if (!waitOrFail(page, "input[name='username']", "Поле логина")) {
                if (FlRuPlaywrightGuards.isAntiDdosOrBotWall(page, captchaService)) {
                    return failAntiDdosWall(page, creds);
                }
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ ЛОГИНА, пользователь: " + creds.login(),
                        AutoreplyErrorTypes.FIELD_NOT_FOUND);
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле логина не найдено", captureScreenshot(page));
            }
        }

        try {
            getCurrentManager().humanMouse(page);
            getCurrentManager().humanDelay(page);
            getCurrentManager().humanType(page, "input[name='username']", creds.login());
            log.info("АВТООТВЕТ: {} -> логин заполнен: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ЛОГИН, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить логин: " + e.getMessage(), captureScreenshot(page));
        }

        try {
            getCurrentManager().humanMouse(page);
            getCurrentManager().humanDelay(page);
            getCurrentManager().humanType(page, "input[name='password']", creds.password());
            log.info("АВТООТВЕТ: {} -> пароль заполнен для пользователя: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ПАРОЛЬ, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить пароль: " + e.getMessage(), captureScreenshot(page));
        }

        getCurrentManager().humanScroll(page);
        getCurrentManager().humanDelay(page);

        log.info("АВТООТВЕТ: {} -> попытка прохождения SmartCaptcha для пользователя: {}, url={}",
                getSiteName(), creds.login(), page.url());
        captchaService.waitForYandexSmartCaptchaWidget(page, 15_000);
        captchaService.logSmartCaptchaState(page, "flru-login-before-captcha");
        page.waitForTimeout(800);
        if (!captchaService.solveYandexSmartCaptcha(page)) {
            captchaService.logSmartCaptchaState(page, "flru-login-captcha-fail");
            return failFlRuCaptcha(page, creds.login(),
                    "Yandex SmartCaptcha на форме входа не пройдена");
        }
        log.info("АВТООТВЕТ: {} -> SmartCaptcha пройдена, пользователь: {}", getSiteName(), creds.login());

        // Старые непрочитанные письма с кодом из прошлых попыток иначе подставляются вместо нового.
        otpService.beginOtpWait(SiteName.FLRU.name(), creds.login());

        if (!clickFlRuLoginButton(page, creds.login())) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА 'Войти', пользователь: " + creds.login(),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка 'Войти' не найдена", captureScreenshot(page));
        }

        if (waitForLoginError(page, 3000)) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕВЕРНЫЙ ЛОГИН/ПАРОЛЬ, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.LOGIN_FAILED);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Неверный логин/пароль", captureScreenshot(page));
        }
        return null;
    }

    /**
     * Определяет текущий этап экрана FL.ru.
     * Приоритет: anti-ddos → техработы → validate-captcha → код из письма → ошибка логина → форма логина → logged-in.
     */
    private FlRuLoginStage detectLoginStage(Page page) {
        if (page == null) {
            return FlRuLoginStage.UNKNOWN;
        }
        if (FlRuPlaywrightGuards.isAntiDdosOrBotWall(page, captchaService)) {
            return FlRuLoginStage.ANTI_DDOS;
        }
        if (FlRuPlaywrightGuards.isSiteMaintenancePage(page)) {
            return FlRuLoginStage.SITE_MAINTENANCE;
        }
        if (validateCaptchaSupport.isValidateCaptchaPage(page)) {
            return FlRuLoginStage.VALIDATE_CAPTCHA;
        }
        if (isEmailCodePage(page)) {
            return FlRuLoginStage.EMAIL_CODE;
        }
        if (isLoginErrorPresent(page)) {
            return FlRuLoginStage.LOGIN_ERROR;
        }
        String url = page.url() == null ? "" : page.url().toLowerCase(Locale.ROOT);
        // confirmation-email без формы — переходный экран, ждём форму или редирект
        if (url.contains("confirmation-email") || url.contains("repeat-send-code")) {
            return FlRuLoginStage.UNKNOWN;
        }
        boolean onLoginUrl = url.contains("/account/login");
        boolean hasUsername = false;
        try {
            hasUsername = page.locator("input[name='username']").count() > 0;
        } catch (Exception ignored) {
            // страница могла уйти
        }
        if (onLoginUrl && hasUsername) {
            return FlRuLoginStage.LOGIN_FORM;
        }
        if (onLoginUrl) {
            return FlRuLoginStage.UNKNOWN;
        }
        if (url.contains("/validate-captcha")) {
            return FlRuLoginStage.VALIDATE_CAPTCHA;
        }
        return FlRuLoginStage.LOGGED_IN;
    }

    /**
     * Цикл смешанных этапов: validate-captcha и код из письма могут чередоваться после логина.
     *
     * @param allowLoginForm если {@code true} — остановка на форме логина без ошибки (ещё не отправляли);
     *                       если {@code false} — форма логина = провал входа
     * @param otpWaitArmed   {@code true} если {@link OtpService#beginOtpWait} уже вызван перед submit логина
     * @return {@code null} при успехе / готовности продолжить; иначе ошибка шага
     */
    private StepResult<Void> runLoginStageLoop(Page page, DecryptedCredential creds,
                                               boolean allowLoginForm, boolean otpWaitArmed) {
        int emailHandled = 0;
        int validateHandled = 0;
        boolean otpArmed = otpWaitArmed;

        for (int i = 1; i <= LOGIN_STAGE_MAX_ITERATIONS; i++) {
            FlRuLoginStage stage = detectLoginStage(page);
            log.info("АВТООТВЕТ: {} -> этап входа {}/{}: {} url={}, пользователь: {}",
                    getSiteName(), i, LOGIN_STAGE_MAX_ITERATIONS, stage, page.url(), creds.login());

            switch (stage) {
                case ANTI_DDOS -> {
                    return failAntiDdosWall(page, creds);
                }
                case SITE_MAINTENANCE -> {
                    return failIfSiteMaintenance(page, creds.login());
                }
                case VALIDATE_CAPTCHA -> {
                    validateHandled++;
                    if (validateHandled > 5) {
                        return failFlRuCaptcha(page, creds.login(),
                                "FL.ru /validate-captcha: слишком много повторов IP-капчи");
                    }
                    if (!validateCaptchaSupport.passValidateCaptcha(page, creds.login())) {
                        return failFlRuCaptcha(page, creds.login(),
                                "FL.ru /validate-captcha: IP-капча не пройдена или страница не покинута");
                    }
                    // После IP-гейта снова может быть код из письма — нужен новый beginOtpWait.
                    otpArmed = false;
                    page.waitForTimeout(400);
                }
                case EMAIL_CODE -> {
                    emailHandled++;
                    if (emailHandled > 5) {
                        return StepResult.fail(StepType.SEND_AUTOREPLY, "FLRU_EMAIL_CODE_PENDING",
                                STAGE_LOOP_EXHAUSTED_MESSAGE, captureScreenshot(page));
                    }
                    boolean renewOtpWait = !otpArmed;
                    StepResult<Void> otp = handleEmailCodeStage(page, creds, renewOtpWait);
                    otpArmed = false;
                    if (otp != null && otp.failed()) {
                        return otp;
                    }
                    page.waitForTimeout(400);
                }
                case LOGIN_ERROR -> {
                    report(Level.WARN, log,
                            "АВТООТВЕТ: " + getSiteName() + " -> НЕВЕРНЫЙ ЛОГИН/ПАРОЛЬ, пользователь: " + creds.login(),
                            AutoreplyErrorTypes.LOGIN_FAILED);
                    return StepResult.fail(StepType.SEND_AUTOREPLY, "Неверный логин/пароль", captureScreenshot(page));
                }
                case LOGIN_FORM -> {
                    if (allowLoginForm) {
                        return null;
                    }
                    report(Level.WARN, log,
                            "АВТООТВЕТ: " + getSiteName() + " -> ВХОД НЕ ВЫПОЛНЕН, остались на странице логина, пользователь: "
                                    + creds.login(),
                            AutoreplyErrorTypes.LOGIN_FAILED);
                    return StepResult.fail(StepType.SEND_AUTOREPLY, "Остались на странице логина", captureScreenshot(page));
                }
                case LOGGED_IN -> {
                    return null;
                }
                case UNKNOWN -> {
                    page.waitForTimeout(700);
                    FlRuLoginStage again = detectLoginStage(page);
                    if (again != FlRuLoginStage.UNKNOWN) {
                        continue;
                    }
                    String url = page.url() == null ? "" : page.url().toLowerCase(Locale.ROOT);
                    // confirmation-email без формы может грузиться дольше обычного редиректа
                    int minBeforeFail = (url.contains("confirmation-email") || url.contains("repeat-send-code"))
                            ? 8 : 3;
                    if (i >= minBeforeFail) {
                        log.warn("АВТООТВЕТ: {} -> неизвестный этап входа, url={}, пользователь: {}",
                                getSiteName(), page.url(), creds.login());
                        return StepResult.fail(StepType.SEND_AUTOREPLY, "FLRU_LOGIN_UNKNOWN_STAGE",
                                "FL.ru: неизвестный экран после входа: " + page.url(), captureScreenshot(page));
                    }
                }
            }
        }

        return StepResult.fail(StepType.SEND_AUTOREPLY, "FLRU_LOGIN_STAGE_LOOP",
                STAGE_LOOP_EXHAUSTED_MESSAGE, captureScreenshot(page));
    }

    private boolean isValidateCaptchaPage(Page page) {
        return validateCaptchaSupport.isValidateCaptchaPage(page);
    }

    /**
     * Только IP-гейт /validate-captcha (навигация, открытие заказа). Без email-кода.
     *
     * @return {@code null} если страницы нет или успешно прошли; иначе ошибка шага
     */
    private StepResult<Void> ensurePastValidateCaptcha(Page page, String login) {
        StepResult<Void> maintenance = failIfSiteMaintenance(page, login);
        if (maintenance != null) {
            return maintenance;
        }
        if (!validateCaptchaSupport.isValidateCaptchaPage(page)) {
            return null;
        }
        if (validateCaptchaSupport.passValidateCaptcha(page, login)) {
            return null;
        }
        return failFlRuCaptcha(page, login,
                "FL.ru /validate-captcha: IP-капча не пройдена или страница не покинута");
    }

    /**
     * Post-login гейты: validate-captcha и код из письма могут чередоваться.
     */
    private StepResult<Void> clearFlRuPostLoginGates(Page page, DecryptedCredential creds) {
        return runLoginStageLoop(page, creds, false, false);
    }

    private boolean clickFlRuLoginButton(Page page, String login) {
        captchaService.dismissSmartCaptchaOverlay(page);
        if (captchaService.isSmartCaptchaOverlayVisible(page)) {
            log.warn("АВТООТВЕТ: {} -> SmartCaptcha advanced-оверлей перекрывает форму, отправляем без клика, пользователь: {}",
                    getSiteName(), login);
        }
        if (submitFlRuLoginForm(page, login)) {
            return true;
        }

        String[] selectors = {
                "#submit-button",
                "button[type='submit']",
                "form button:has-text('Войти')",
                "input[type='submit']"
        };
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (String selector : selectors) {
                Locator loc = page.locator(selector);
                if (loc.count() == 0) {
                    continue;
                }
                Locator first = loc.first();
                try {
                    if (!first.isVisible()) {
                        continue;
                    }
                    if (first.isDisabled()) {
                        continue;
                    }
                    getCurrentManager().humanDelay(page);
                    first.click(new Locator.ClickOptions().setTimeout(5000));
                    log.info("АВТООТВЕТ: {} -> кнопка 'Войти' нажата ({}), пользователь: {}", getSiteName(), selector, login);
                    return true;
                } catch (Exception e) {
                    log.debug("АВТООТВЕТ: {} -> клик '{}' не удался: {}", getSiteName(), selector, e.getMessage());
                }
            }
            page.waitForTimeout(400);
        }
        captchaService.dismissSmartCaptchaOverlay(page);
        for (String selector : selectors) {
            Locator loc = page.locator(selector);
            if (loc.count() == 0) {
                continue;
            }
            try {
                loc.first().click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                log.info("АВТООТВЕТ: {} -> кнопка 'Войти' нажата force ({}), пользователь: {}", getSiteName(), selector, login);
                return true;
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> force-клик '{}' не удался: {}", getSiteName(), selector, e.getMessage());
            }
        }
        if (submitFlRuLoginForm(page, login)) {
            return true;
        }
        log.warn("CLICK FAILED at step 'Кнопка Войти': ни один селектор не сработал, пользователь: {}", login);
        return false;
    }

    private boolean submitFlRuLoginForm(Page page, String login) {
        try {
            Object submitted = page.evaluate(
                    """
                            () => {
                              const form = document.querySelector('form');
                              if (!form) return false;
                              const btn = document.querySelector('#submit-button, button[type="submit"]');
                              if (typeof form.requestSubmit === 'function') {
                                if (btn) form.requestSubmit(btn);
                                else form.requestSubmit();
                                return true;
                              }
                              form.submit();
                              return true;
                            }
                            """);
            if (Boolean.TRUE.equals(submitted)) {
                log.info("АВТООТВЕТ: {} -> форма логина отправлена (requestSubmit/submit), пользователь: {}", getSiteName(), login);
                return true;
            }
        } catch (Exception e) {
            log.warn("АВТООТВЕТ: {} -> submit формы не удался: {}", getSiteName(), e.getMessage());
        }
        return false;
    }

    private boolean waitForLoginError(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isLoginErrorPresent(page)) {
                return true;
            }
            page.waitForTimeout(300);
        }
        return isLoginErrorPresent(page);
    }

    /**
     * Ждёт OTP из почты; если за раунд письмо не пришло — нажимает «Отправить повторно» на FL.ru
     * (с паузой по таймеру на странице) и повторяет ожидание.
     */
    private String waitForOtpWithResend(Page page, DecryptedCredential creds) {
        int rounds = Math.max(1, otpResendMaxRounds);
        long perRoundMs = otpWaitPerRoundMs > 0 ? otpWaitPerRoundMs : Math.max(30_000, otpTimeoutMs / rounds);

        for (int round = 1; round <= rounds; round++) {
            log.info("АВТООТВЕТ: {} -> ожидание кода из письма для {}, раунд {}/{}, таймаут {} ms",
                    getSiteName(), creds.login(), round, rounds, perRoundMs);
            String otp = otpService.waitForOtp(SiteName.FLRU.name(), creds.login(), perRoundMs);
            if (otp != null) {
                return otp;
            }
            if (round >= rounds) {
                break;
            }
            log.info("АВТООТВЕТ: {} -> код не пришёл за раунд {}, запрашиваем повторную отправку, пользователь: {}",
                    getSiteName(), round, creds.login());
            if (!requestFlRuCodeResend(page, creds)) {
                log.warn("АВТООТВЕТ: {} -> не удалось запросить повторную отправку кода, пользователь: {}",
                        getSiteName(), creds.login());
                break;
            }
        }
        return null;
    }

    private boolean requestFlRuCodeResend(Page page, DecryptedCredential creds) {
        try {
            waitUntilResendLinkAvailable(page, otpResendCooldownMaxWaitMs);
            Locator link = page.locator(RESEND_CODE_LINK).first();
            if (link.count() == 0 || !link.isVisible()) {
                log.warn("АВТООТВЕТ: {} -> ссылка «Отправить повторно» недоступна, пользователь: {}",
                        getSiteName(), creds.login());
                return false;
            }
            otpService.beginOtpWait(SiteName.FLRU.name(), creds.login());
            link.click();
            try {
                page.waitForLoadState(LoadState.LOAD,
                        new Page.WaitForLoadStateOptions().setTimeout(15_000));
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> LOAD после повторной отправки: {}", getSiteName(), e.getMessage());
            }
            log.info("АВТООТВЕТ: {} -> нажата «Отправить повторно», пользователь: {}", getSiteName(), creds.login());
            return isEmailCodePage(page);
        } catch (Exception e) {
            log.warn("АВТООТВЕТ: {} -> ошибка повторной отправки кода: {}, пользователь: {}",
                    getSiteName(), e.getMessage(), creds.login());
            return false;
        }
    }

    /**
     * FL.ru скрывает ссылку на время обратного отсчёта ({@code #counter} / {@code #seconds}),
     * затем показывает {@code #repeat-send}.
     */
    private void waitUntilResendLinkAvailable(Page page, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            Locator link = page.locator("#repeat-send " + RESEND_CODE_LINK).first();
            if (link.count() > 0 && link.isVisible()) {
                return;
            }
            link = page.locator(RESEND_CODE_LINK).first();
            if (link.count() > 0 && link.isVisible()) {
                return;
            }
            long waitMs = pollResendCooldownMs(page);
            if (waitMs > 0) {
                long remaining = deadline - System.currentTimeMillis();
                page.waitForTimeout(Math.min(waitMs, Math.max(remaining, 0)));
            } else {
                page.waitForTimeout(500);
            }
        }
    }

    private long pollResendCooldownMs(Page page) {
        try {
            Locator counter = page.locator("#counter");
            if (counter.count() == 0 || !counter.isVisible()) {
                return 0;
            }
            String secText = page.locator("#seconds").innerText().trim();
            int seconds = Integer.parseInt(secText.replaceAll("[^\\-0-9]", ""));
            if (seconds <= 0) {
                return 500;
            }
            log.debug("АВТООТВЕТ: {} -> повторная отправка кода через {} с", getSiteName(), seconds);
            return seconds * 1000L + 800L;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ждём исчезновения формы кода после отправки (URL при этом может не меняться). */
    private void waitForEmailCodeAccepted(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isEmailCodePage(page)) {
                return;
            }
            page.waitForTimeout(300);
        }
        try {
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(5000));
        } catch (Exception ignored) {
            // таймер «отправить повторно» может мешать NETWORKIDLE
        }
    }

    /** Ждём редирект с логина, форму кода из письма, validate-captcha или ошибку пароля. */
    private void waitForLoginCompletionOrEmailCode(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            FlRuLoginStage stage = detectLoginStage(page);
            if (stage == FlRuLoginStage.LOGIN_ERROR
                    || stage == FlRuLoginStage.EMAIL_CODE
                    || stage == FlRuLoginStage.VALIDATE_CAPTCHA
                    || stage == FlRuLoginStage.ANTI_DDOS
                    || stage == FlRuLoginStage.SITE_MAINTENANCE
                    || stage == FlRuLoginStage.LOGGED_IN) {
                return;
            }
            if (stage != FlRuLoginStage.LOGIN_FORM && stage != FlRuLoginStage.UNKNOWN) {
                return;
            }
            page.waitForTimeout(300);
        }
    }

    /**
     * FL.ru показывает форму кода из письма и после логина, и при заходе с восстановленной сессией,
     * причём прямо на главной — URL при этом может не меняться, поэтому проверяем по разметке.
     */
    @Override
    protected StepResult<Void> finalizeBeforeSessionSave(Page page, DecryptedCredential creds,
                                                         AutoreplyMode autoreplyMode) {
        if (page == null) {
            return null;
        }
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(12_000));
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> DOMCONTENTLOADED перед finalize: {}", getSiteName(), e.getMessage());
        }
        return runLoginStageLoop(page, creds, false, false);
    }

    @Override
    protected StepResult<Void> incompleteLoginAfterSaveBlocked(Page page, AutoreplyMode autoreplyMode) {
        if (autoreplyMode != AutoreplyMode.LOGIN_ONLY || page == null) {
            return null;
        }
        FlRuLoginStage stage = detectLoginStage(page);
        return switch (stage) {
            case VALIDATE_CAPTCHA -> StepResult.fail(StepType.SEND_AUTOREPLY, "FLRU_POST_LOGIN_VALIDATE_CAPTCHA",
                    "FL.ru: страница validate-captcha (подозрительная активность) после входа",
                    captureScreenshot(page));
            case EMAIL_CODE -> StepResult.fail(StepType.SEND_AUTOREPLY, "FLRU_EMAIL_CODE_PENDING",
                    CODE_REJECTED_MESSAGE, captureScreenshot(page));
            default -> super.incompleteLoginAfterSaveBlocked(page, autoreplyMode);
        };
    }

    @Override
    protected boolean shouldPersistSession(Page page, StepResult<Void> loginResult) {
        if (!super.shouldPersistSession(page, loginResult)) {
            return false;
        }
        FlRuLoginStage stage = detectLoginStage(page);
        if (stage == FlRuLoginStage.EMAIL_CODE) {
            log.warn("SESSION: FLRU — форма кода из письма на экране, save запрещён, url={}", page.url());
            return false;
        }
        if (stage == FlRuLoginStage.VALIDATE_CAPTCHA) {
            log.warn("SESSION: FLRU — validate-captcha, save запрещён, url={}", page.url());
            return false;
        }
        if (stage == FlRuLoginStage.LOGIN_FORM || stage == FlRuLoginStage.LOGIN_ERROR) {
            log.warn("SESSION: FLRU — форма логина на экране, save запрещён, url={}", page.url());
            return false;
        }
        if (stage == FlRuLoginStage.ANTI_DDOS) {
            log.warn("SESSION: FLRU — anti-ddos, save запрещён, url={}", page.url());
            return false;
        }
        return true;
    }

    @Override
    protected boolean shouldInvalidateStoredSessionAfterFailedRelogin(StepResult<Void> loginResult) {
        if (loginResult == null || !loginResult.failed()) {
            return false;
        }
        String msg = loginResult.getErrorMessage();
        if (msg == null) {
            return false;
        }
        if (msg.contains("TargetClosedError") || msg.contains("browser has been closed")) {
            return false;
        }
        if (msg.contains(FlRuPlaywrightGuards.DDOS_RETRY_FAIL_MARKER)) {
            return false;
        }
        return msg.contains("Неверный логин/пароль") || msg.contains("Редирект на страницу логина");
    }

    @Override
    protected boolean shouldDeleteStoredSessionAfterLoginFailure(StepResult<Void> loginResult) {
        if (loginResult == null || !loginResult.failed()) {
            return false;
        }
        String code = loginResult.getErrorCode();
        if (code == null || code.isBlank()) {
            return false;
        }
        // Ошибка капчи / прокси — не затираем сессию от успешной проверки минутой ранее.
        if (code.contains("CAPTCHA") || code.contains("YANDEX") || code.contains("HCAPTCHA")
                || code.contains("VALIDATE") || code.contains("DDOS") || code.contains("PROXY")
                || code.contains("EMAIL_CODE") || code.contains("STAGE")) {
            return false;
        }
        return code.contains("LOGIN") || code.contains("CREDENTIAL") || code.contains("PASSWORD");
    }

    @Override
    protected StepResult<Void> checkAfterLogin(Page page, DecryptedCredential creds) {
        return runLoginStageLoop(page, creds, false, false);
    }

    /**
     * Один проход этапа «код из письма»: ждать OTP → ввести → отправить.
     * После успешного ввода форма должна исчезнуть; иначе — ошибка (код отклонён).
     * При повторном появлении формы (после validate-captcha) вызывается снова из stage-loop.
     *
     * @param renewOtpWait вызвать {@link OtpService#beginOtpWait} (повторный email-гейт); не вызывать,
     *                     если wait уже стартовал перед submit логина
     */
    private StepResult<Void> handleEmailCodeStage(Page page, DecryptedCredential creds, boolean renewOtpWait) {
        log.info("АВТООТВЕТ: {} -> этап EMAIL_CODE, renewOtpWait={}, url={}, пользователь: {}",
                getSiteName(), renewOtpWait, page.url(), creds.login());

        if (findFirst(page, CODE_INPUT_SELECTORS) == null) {
            log.warn("АВТООТВЕТ: {} -> поле кода не найдено, разметка формы: {}", getSiteName(), dumpCodeForm(page));
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ КОДА ИЗ ПИСЬМА, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, CODE_FORM_NOT_PARSED_MESSAGE, captureScreenshot(page));
        }

        if (renewOtpWait) {
            otpService.beginOtpWait(SiteName.FLRU.name(), creds.login());
        }

        String otp = waitForOtpWithResend(page, creds);
        if (otp == null) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> КОД ИЗ ПИСЬМА НЕ ПОЛУЧЕН за отведённое время, пользователь: "
                            + creds.login(),
                    AutoreplyErrorTypes.OTP_NOT_RECEIVED);
            return StepResult.fail(StepType.SEND_AUTOREPLY, CODE_NOT_RECEIVED_MESSAGE, captureScreenshot(page));
        }
        String code = otp.toUpperCase(Locale.ROOT);
        log.info("АВТООТВЕТ: {} -> код из письма получен ({} символов), пользователь: {}",
                getSiteName(), code.length(), creds.login());

        Locator input = findFirst(page, CODE_INPUT_SELECTORS);
        if (input == null) {
            log.warn("АВТООТВЕТ: {} -> поле кода пропало после ожидания письма, разметка: {}",
                    getSiteName(), dumpCodeForm(page));
            return StepResult.fail(StepType.SEND_AUTOREPLY, CODE_FORM_NOT_PARSED_MESSAGE, captureScreenshot(page));
        }

        try {
            input.click();
            input.fill(code);
            String filled = input.inputValue();
            log.info("АВТООТВЕТ: {} -> в поле кода введено: {} (ожидали {}), пользователь: {}",
                    getSiteName(), filled, code, creds.login());

            Locator submit = findFirst(page, CODE_SUBMIT_SELECTORS);
            if (submit != null) {
                submit.click(new Locator.ClickOptions().setTimeout(10_000));
            } else {
                page.keyboard().press("Enter");
            }
            waitForEmailCodeAccepted(page, 25_000);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ОТПРАВИТЬ КОД ИЗ ПИСЬМА, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY,
                    "Не удалось отправить код из письма: " + e.getMessage(), captureScreenshot(page));
        }

        otpService.invalidateOtp(SiteName.FLRU.name(), creds.login());

        // Форма кода ещё на экране — либо код отклонён, либо сразу ушли на validate-captcha / другой гейт.
        FlRuLoginStage after = detectLoginStage(page);
        if (after == FlRuLoginStage.EMAIL_CODE) {
            log.warn("АВТООТВЕТ: {} -> форма кода всё ещё на экране после отправки, url={}, разметка: {}, пользователь: {}",
                    getSiteName(), page.url(), dumpCodeForm(page), creds.login());
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> КОД ИЗ ПИСЬМА ОТКЛОНЁН, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.EMAIL_CODE_LOGIN_ENABLED);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "FLRU_EMAIL_CODE_PENDING",
                    CODE_REJECTED_MESSAGE, captureScreenshot(page));
        }

        log.info("АВТООТВЕТ: {} -> код из письма принят (следующий этап={}), пользователь: {}",
                getSiteName(), after, creds.login());
        try {
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(15_000));
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> LOAD после OTP: {}", getSiteName(), e.getMessage());
        }
        return null;
    }

    /** Первый селектор из списка, которому на странице что-то соответствует. */
    private Locator findFirst(Page page, String[] selectors) {
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0) {
                    return locator;
                }
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> селектор '{}' не сработал: {}", getSiteName(), selector, e.getMessage());
            }
        }
        return null;
    }

    private String dumpCodeForm(Page page) {
        try {
            Locator form = page.locator(CODE_FORM).first();
            return form.count() > 0 ? form.innerHTML() : "форма " + CODE_FORM + " на странице отсутствует";
        } catch (Exception e) {
            return "не удалось прочитать разметку: " + e.getMessage();
        }
    }

    private boolean isEmailCodePage(Page page) {
        for (String selector : EMAIL_CODE_SELECTORS) {
            try {
                if (page.locator(selector).count() > 0) {
                    log.debug("АВТООТВЕТ: {} -> страница ввода кода из письма обнаружена (селектор: {})", getSiteName(), selector);
                    return true;
                }
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> проверка страницы кода через '{}' не удалась: {}", getSiteName(), selector, e.getMessage());
            }
        }
        return false;
    }

    private StepResult<Void> failIfAntiDdosWall(Page page, DecryptedCredential creds) {
        if (FlRuPlaywrightGuards.isAntiDdosOrBotWall(page, captchaService)) {
            return failAntiDdosWall(page, creds);
        }
        return null;
    }

    private StepResult<Void> failAntiDdosWall(Page page, DecryptedCredential creds) {
        boolean suspiciousIp = FlRuPlaywrightGuards.isSuspiciousIpActivityBlock(page);
        if (suspiciousIp) {
            log.warn("АВТООТВЕТ: {} -> блок IP (подозрительная активность), url={}, пользователь: {}",
                    getSiteName(), page.url(), creds.login());
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> блок IP (подозрительная активность), пользователь: "
                            + creds.login(),
                    AutoreplyErrorTypes.ERROR_OPEN_PAGE);
        } else {
            log.warn("АВТООТВЕТ: {} -> страница защиты от DDoS/ботов, url={}, пользователь: {}",
                    getSiteName(), page.url(), creds.login());
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> DDoS/ANTI-BOT, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.ERROR_OPEN_PAGE);
        }
        return StepResult.fail(StepType.SEND_AUTOREPLY,
                FlRuPlaywrightGuards.ddosRetryFailMessage(getSiteName()), captureScreenshot(page));
    }

    /**
     * Открывает форму отклика (кнопка «Откликнуться») и ждёт поле текста.
     * Если на сайте техработы — ошибка {@link AutoreplyErrorTypes#SITE_MAINTENANCE}.
     * Если заказчик уже выбрал исполнителя — ошибка {@link AutoreplyErrorTypes#ORDER_EXECUTOR_SELECTED}.
     * Если FL.ru показывает попап покупки отклика — сразу ошибка {@link AutoreplyErrorTypes#TARIFF_LIMIT}.
     *
     * @return {@code null}, если форма готова; иначе ошибка шага
     */
    private StepResult<Void> prepareFlRuReplyForm(Page page, String login) {
        StepResult<Void> maintenance = failIfSiteMaintenance(page, login);
        if (maintenance != null) {
            return maintenance;
        }
        StepResult<Void> closed = failIfExecutorAlreadySelected(page, login);
        if (closed != null) {
            return closed;
        }
        if (isReplyFormPresent(page)) {
            return null;
        }
        StepResult<Void> paywall = failIfBuyResponsePaywall(page, login);
        if (paywall != null) {
            return paywall;
        }

        boolean clicked = clickOpenReplyButtonIfPresent(page, login);
        if (clicked) {
            page.waitForTimeout(900);
            paywall = failIfBuyResponsePaywall(page, login);
            if (paywall != null) {
                return paywall;
            }
            if (isReplyFormPresent(page)) {
                return null;
            }
        }

        long totalMs = timeouts.getElementWaitMs();
        long pollMs = timeouts.getElementPollMs();
        long deadline = System.currentTimeMillis() + totalMs;
        int attempts = 0;
        while (System.currentTimeMillis() < deadline) {
            attempts++;
            maintenance = failIfSiteMaintenance(page, login);
            if (maintenance != null) {
                return maintenance;
            }
            closed = failIfExecutorAlreadySelected(page, login);
            if (closed != null) {
                return closed;
            }
            paywall = failIfBuyResponsePaywall(page, login);
            if (paywall != null) {
                return paywall;
            }
            if (isReplyFormPresent(page)) {
                long spent = totalMs - (deadline - System.currentTimeMillis());
                log.info("OK at step '{}': '{}' найдено за {} ms (attempts={})",
                        "Поле текста отклика", REPLY_TEXT_FIELD, spent, attempts);
                return null;
            }
            if (!clicked && attempts == 3) {
                clicked = clickOpenReplyButtonIfPresent(page, login);
                if (clicked) {
                    page.waitForTimeout(900);
                }
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            page.waitForTimeout(Math.min(pollMs, remaining));
        }

        maintenance = failIfSiteMaintenance(page, login);
        if (maintenance != null) {
            return maintenance;
        }
        closed = failIfExecutorAlreadySelected(page, login);
        if (closed != null) {
            return closed;
        }
        paywall = failIfBuyResponsePaywall(page, login);
        if (paywall != null) {
            return paywall;
        }

        log.warn("TIMEOUT at step '{}': '{}' не найдено за {} ms (attempts={})",
                "Поле текста отклика", REPLY_TEXT_FIELD, totalMs, attempts);
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ ТЕКСТА ОТКЛИКА, пользователь: " + login,
                AutoreplyErrorTypes.FIELD_NOT_FOUND);
        return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле текста отклика не найдено", captureScreenshot(page));
    }

    private StepResult<Void> failIfSiteMaintenance(Page page, String login) {
        if (!FlRuPlaywrightGuards.isSiteMaintenancePage(page)) {
            return null;
        }
        log.warn("АВТООТВЕТ: {} -> технические работы на сайте, url={}, пользователь: {}",
                getSiteName(), page.url(), login);
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> ТЕХНИЧЕСКИЕ РАБОТЫ НА САЙТЕ, пользователь: " + login,
                AutoreplyErrorTypes.SITE_MAINTENANCE);
        return StepResult.fail(StepType.SEND_AUTOREPLY, AutoreplyErrorTypes.SITE_MAINTENANCE,
                SITE_MAINTENANCE_USER_MESSAGE, captureScreenshot(page));
    }

    private StepResult<Void> failIfExecutorAlreadySelected(Page page, String login) {
        if (!isFlRuExecutorSelectedBadgeVisible(page)) {
            return null;
        }
        log.warn("АВТООТВЕТ: {} -> заказчик выбрал исполнителя (отклики закрыты), url={}, пользователь: {}",
                getSiteName(), page.url(), login);
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> ЗАКАЗЧИК ВЫБРАЛ ИСПОЛНИТЕЛЯ, пользователь: " + login,
                AutoreplyErrorTypes.ORDER_EXECUTOR_SELECTED);
        return StepResult.fail(StepType.SEND_AUTOREPLY, AutoreplyErrorTypes.ORDER_EXECUTOR_SELECTED,
                EXECUTOR_SELECTED_USER_MESSAGE, captureScreenshot(page));
    }

    private boolean isFlRuExecutorSelectedBadgeVisible(Page page) {
        if (page == null) {
            return false;
        }
        try {
            for (String selector : EXECUTOR_SELECTED_SELECTORS) {
                Locator badge = page.locator(selector).first();
                if (badge.count() > 0 && badge.isVisible()) {
                    return true;
                }
            }
            String bodyText = page.locator("body").innerText();
            if (bodyText != null && bodyText.contains(EXECUTOR_SELECTED_MARKER)) {
                Locator marker = page.locator("text=" + EXECUTOR_SELECTED_MARKER).first();
                return marker.count() > 0;
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> проверка плашки «исполнитель выбран»: {}", getSiteName(), e.getMessage());
        }
        return false;
    }

    private StepResult<Void> failIfBuyResponsePaywall(Page page, String login) {
        if (!isFlRuBuyResponsePaywallVisible(page)) {
            return null;
        }
        return failNoResponsesLeft(page, login);
    }

    private StepResult<Void> failNoResponsesLeft(Page page, String login) {
        log.warn("АВТООТВЕТ: {} -> попап покупки отклика (нет откликов/средств), url={}, пользователь: {}",
                getSiteName(), page.url(), login);
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> НЕТ ОТКЛИКОВ / ТРЕБУЕТСЯ ОПЛАТА, пользователь: " + login,
                AutoreplyErrorTypes.TARIFF_LIMIT);
        return StepResult.fail(StepType.SEND_AUTOREPLY, AutoreplyErrorTypes.TARIFF_LIMIT,
                NO_RESPONSES_USER_MESSAGE, captureScreenshot(page));
    }

    private boolean isFlRuBuyResponsePaywallVisible(Page page) {
        if (page == null) {
            return false;
        }
        try {
            Locator popup = page.locator(PAYWALL_POPUP).first();
            if (popup.count() > 0 && popup.isVisible()) {
                return true;
            }
            Locator buyOffers = page.locator("[id^='quickPaymentPopupBuyOffers']").first();
            if (buyOffers.count() > 0 && buyOffers.isVisible()) {
                return true;
            }
            Locator hint = page.locator("text=купить 1 отклик").first();
            if (hint.count() > 0 && hint.isVisible()) {
                return true;
            }
            Locator hintAlt = page.locator("text=купить отклик").first();
            if (hintAlt.count() > 0 && hintAlt.isVisible()) {
                return true;
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> проверка попапа откликов: {}", getSiteName(), e.getMessage());
        }
        return false;
    }

    private boolean isReplyFormPresent(Page page) {
        if (page == null) {
            return false;
        }
        try {
            return page.locator(REPLY_TEXT_FIELD).count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clickOpenReplyButtonIfPresent(Page page, String login) {
        for (String selector : OPEN_REPLY_BUTTON_SELECTORS) {
            Locator loc = page.locator(selector);
            if (loc.count() == 0) {
                continue;
            }
            Locator first = loc.first();
            try {
                if (!first.isVisible()) {
                    continue;
                }
                getCurrentManager().humanDelay(page);
                first.click(new Locator.ClickOptions().setTimeout(10_000));
                log.info("АВТООТВЕТ: {} -> нажата кнопка отклика ({}), пользователь: {}",
                        getSiteName(), selector, login);
                return true;
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> клик отклика '{}' не удался: {}", getSiteName(), selector, e.getMessage());
            }
        }
        return false;
    }

    private boolean isLoginErrorPresent(Page page) {
        for (String selector : LOGIN_ERROR_SELECTORS) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.isVisible()) {
                    log.debug("АВТООТВЕТ: {} -> ошибка логина обнаружена (селектор: {})", getSiteName(), selector);
                    return true;
                }
            } catch (Exception e) {
                log.debug("АВТООТВЕТ: {} -> проверка ошибки логина через '{}' не удалась: {}", getSiteName(), selector, e.getMessage());
            }
        }
        return false;
    }

    @Override
    protected StepResult<Void> processAutoReply(Page page, AiNotificationPayload payload, DecryptedCredential creds) {
        String link = payload.getOrder().getLink();
        String login = creds.login();
        log.info("АВТООТВЕТ: {} -> НАЧАЛО ОБРАБОТКИ ЗАКАЗА: {}, пользователь: {}", getSiteName(), link, login);

        try {
            safeNavigate(page, link);
            StepResult<Void> validateGate = ensurePastValidateCaptcha(page, login);
            if (validateGate != null) {
                return validateGate;
            }
            page.waitForLoadState(LoadState.NETWORKIDLE);
            log.info("АВТООТВЕТ: {} -> страница заказа открыта, пользователь: {}", getSiteName(), login);
            takeScreenshot(page, getSiteName(), payload.getUser().getUuid(), "order_page");
        } catch (FlRuStepFailureException stepFailure) {
            return stepFailure.getStepResult();
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ОТКРЫТЬ ЗАКАЗ, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.ERROR_OPEN_PAGE);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось открыть заказ: " + e.getMessage(), captureScreenshot(page));
        }

        StepResult<Void> maintenance = failIfSiteMaintenance(page, login);
        if (maintenance != null) {
            return maintenance;
        }

        StepResult<Void> orderClosed = failIfExecutorAlreadySelected(page, login);
        if (orderClosed != null) {
            return orderClosed;
        }

        StepResult<Void> replyFormReady = prepareFlRuReplyForm(page, login);
        if (replyFormReady != null) {
            return replyFormReady;
        }

        try {
            page.fill("#el-descr", payload.getDecision().reply());
            log.info("АВТООТВЕТ: {} -> текст ответа вставлен, длина: {}, пользователь: {}", getSiteName(),
                    payload.getDecision().reply() != null ? payload.getDecision().reply().length() : 0, login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ТЕКСТ ОТКЛИКА, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить текст отклика: " + e.getMessage(), captureScreenshot(page));
        }

        try {
            Locator payRadio = page.locator("label[for='el-pay-0']");
            if (payRadio.count() > 0) {
                payRadio.click();
                log.info("АВТООТВЕТ: {} -> выбран способ оплаты 'На банковскую карту физ. лица', пользователь: {}", getSiteName(), login);
            } else {
                log.warn("АВТООТВЕТ: {} -> радиокнопка оплаты не найдена, пользователь: {}", getSiteName(), login);
            }
        } catch (Exception e) {
            log.warn("АВТООТВЕТ: {} -> ошибка при выборе способа оплаты, пользователь: {}, ошибка: {}", getSiteName(), login, e.getMessage());
            // Не критично, продолжаем
        }

        if (!waitOrFail(page, "#el-time_from", "Поле срока выполнения")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ СРОКА ВЫПОЛНЕНИЯ, пользователь: " + login,
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле срока выполнения не найдено", captureScreenshot(page));
        }

        try {
            String duration = String.valueOf(defaultDays);
            page.fill("#el-time_from", duration);
            log.info("АВТООТВЕТ: {} -> срок выполнения установлен: {} дней, пользователь: {}", getSiteName(), defaultDays, login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ СРОК ВЫПОЛНЕНИЯ, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить срок выполнения: " + e.getMessage(), captureScreenshot(page));
        }

        if (!waitOrFail(page, "#el-cost_from",  "Поле цены")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ ЦЕНЫ, пользователь: " + login,
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле цены не найдено", captureScreenshot(page));
        }

        try {
            String price = String.valueOf(defaultPrice);
            page.fill("#el-cost_from", price);
            log.info("АВТООТВЕТ: {} -> цена установлена: {}, пользователь: {}", getSiteName(), defaultPrice, login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ЦЕНУ, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить цену: " + e.getMessage(), captureScreenshot(page));
        }

        if (!waitOrFail(page, "#el-submit",  "Кнопка отправки отклика")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА ОТПРАВКИ ОТКЛИКА, пользователь: " + login,
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка отправки отклика не найдена", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> кнопка отправки найдена, пользователь: {}", getSiteName(), login);

        Locator sendBtn = page.locator("#el-submit");
        try {
            page.waitForCondition(sendBtn::isEnabled,
                    new Page.WaitForConditionOptions().setTimeout(5000));
            log.info("АВТООТВЕТ: {} -> кнопка отправки активна, пользователь: {}", getSiteName(), login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> КНОПКА ОТПРАВКИ НЕАКТИВНА, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка отправки неактивна", captureScreenshot(page));
        }

        if (sendRequest) {
            try {
                sendBtn.click();
                log.info("АВТООТВЕТ: {} -> ЗАЯВКА УСПЕШНО ОТПРАВЛЕНА, пользователь: {}", getSiteName(), login);
            } catch (Exception e) {
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ НАЖАТЬ КНОПКУ ОТПРАВКИ, пользователь: "
                                + login + ", ошибка: " + e.getMessage(),
                        AutoreplyErrorTypes.BUTTON_NOT_FOUND);
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось нажать кнопку отправки: " + e.getMessage(), captureScreenshot(page));
            }
        } else {
            log.info("АВТООТВЕТ: {} -> ЗАЯВКА НЕ ОТПРАВЛЕНА (sendRequest=false), пользователь: {}", getSiteName(), login);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Заявка не отправлена (sendRequest=false)", captureScreenshot(page));
        }

        page.waitForTimeout(2000);
        log.info("АВТООТВЕТ: {} -> ОТКЛИК УСПЕШНО ЗАВЕРШЁН, пользователь: {}", getSiteName(), login);
        return StepResult.ok(StepType.SEND_AUTOREPLY, null);
    }

    @Override
    public SiteName getSiteName() {
        return SiteName.FLRU;
    }
}