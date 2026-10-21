package by.gdev.alert.job.notification.service.ai.recovery;

import by.gdev.alert.job.notification.service.ai.metrics.errors.AutoreplyErrorTypes;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureCode;
import by.gdev.alert.job.notification.service.ai.parser.impl.flru.FlRuPlaywrightGuards;
import by.gdev.alert.job.notification.service.ai.parser.youdo.YouDoPlaywrightGuards;

/**
 * Политика recovery: {@link AutoreplyFailureAction#ABORT} или {@link AutoreplyFailureAction#RETRY_SAME}.
 * Смена прокси не здесь — в {@link by.gdev.alert.job.notification.service.ai.parser.impl.AutoreplyParser}
 * после {@code parser.autoreply.proxy.attempts-per-proxy} неудач на одном IP.
 */
public final class AutoreplyFailurePolicy {

    private AutoreplyFailurePolicy() {
    }

    public static AutoreplyFailureAction resolve(StepResult<?> result) {
        if (result == null || !result.failed()) {
            return AutoreplyFailureAction.ABORT;
        }
        return resolve(result.getErrorCode(), result.getErrorMessage());
    }

    public static AutoreplyFailureAction resolve(String errorCode, String message) {
        if (message != null && message.contains(FlRuPlaywrightGuards.DDOS_RETRY_FAIL_MARKER)) {
            return AutoreplyFailureAction.RETRY_SAME;
        }
        if (message != null && message.contains(YouDoPlaywrightGuards.PROXY_BLOCKED_MARKER)) {
            return AutoreplyFailureAction.ROTATE_PROXY;
        }
        if (errorCode == null || errorCode.isBlank()) {
            return AutoreplyProxyFailures.isProxyRelated(null, message)
                    ? AutoreplyFailureAction.RETRY_SAME
                    : AutoreplyFailureAction.ABORT;
        }

        try {
            CaptchaFailureCode captchaCode = CaptchaFailureCode.valueOf(errorCode);
            return switch (captchaCode) {
                case FLRU_VALIDATE_HCAPTCHA_TWO_CAPTCHA,
                     FLRU_VALIDATE_HCAPTCHA_VISUAL,
                     FLRU_VALIDATE_HCAPTCHA_STUCK,
                     FLRU_VALIDATE_YANDEX,
                     FLRU_VALIDATE_UNKNOWN,
                     FLRU_LOGIN_YANDEX_ADVANCED,
                     FLRU_LOGIN_YANDEX_UNKNOWN,
                     YOUDO_KALEIDOSCOPE_FAIL -> AutoreplyFailureAction.ROTATE_PROXY;
                case FLRU_LOGIN_YANDEX_CHECKBOX,
                     CAPTCHA_GENERIC,
                     CAPTCHA_EXCEPTION -> AutoreplyFailureAction.RETRY_SAME;
                default -> AutoreplyFailureAction.ABORT;
            };
        } catch (IllegalArgumentException ignored) {
            // не код капчи
        }

        return switch (errorCode) {
            case AutoreplyErrorTypes.PROXY_FAILED,
                 AutoreplyErrorTypes.CAPTCHA_FAILED,
                 AutoreplyErrorTypes.TIMEOUT,
                 AutoreplyErrorTypes.ERROR_OPEN_PAGE,
                 "YOUDO_EMAIL_SUBMIT_STUCK",
                 "YOUDO_LOGIN_STAGE_LOOP",
                 "YOUDO_LOGIN_UNKNOWN_STAGE",
                 YouDoPlaywrightGuards.YOUDO_PROXY_BLOCKED_CODE -> AutoreplyFailureAction.ROTATE_PROXY;
            case AutoreplyErrorTypes.LOGIN_FAILED,
                 AutoreplyErrorTypes.OTP_NOT_RECEIVED,
                 AutoreplyErrorTypes.FIELD_NOT_FOUND,
                 AutoreplyErrorTypes.BUTTON_NOT_FOUND,
                 AutoreplyErrorTypes.EMAIL_INVALID,
                 AutoreplyErrorTypes.TARIFF_LIMIT,
                 AutoreplyErrorTypes.ORDER_EXECUTOR_SELECTED,
                 AutoreplyErrorTypes.SITE_MAINTENANCE,
                 AutoreplyErrorTypes.EMAIL_CODE_LOGIN_ENABLED -> AutoreplyFailureAction.ABORT;
            default -> AutoreplyFailureAction.RETRY_SAME;
        };
    }
}
