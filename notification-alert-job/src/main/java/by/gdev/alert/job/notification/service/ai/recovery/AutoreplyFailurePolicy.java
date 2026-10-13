package by.gdev.alert.job.notification.service.ai.recovery;

import by.gdev.alert.job.notification.service.ai.metrics.errors.AutoreplyErrorTypes;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureCode;
import by.gdev.common.service.playwright.flru.FlRuPlaywrightGuards;

/**
 * Политика recovery по {@code errorCode} / тексту ошибки:
 * {@link AutoreplyFailureAction#ROTATE_PROXY}, {@link AutoreplyFailureAction#RETRY_SAME}, {@link AutoreplyFailureAction#ABORT}.
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
            return AutoreplyFailureAction.ROTATE_PROXY;
        }
        if (errorCode == null || errorCode.isBlank()) {
            return AutoreplyFailureAction.ABORT;
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
            };
        } catch (IllegalArgumentException ignored) {
            // не код капчи
        }

        return switch (errorCode) {
            case AutoreplyErrorTypes.CAPTCHA_FAILED,
                 AutoreplyErrorTypes.TIMEOUT,
                 AutoreplyErrorTypes.ERROR_OPEN_PAGE,
                 "YOUDO_EMAIL_SUBMIT_STUCK",
                 "YOUDO_LOGIN_STAGE_LOOP",
                 "YOUDO_LOGIN_UNKNOWN_STAGE" -> AutoreplyFailureAction.ROTATE_PROXY;
            case AutoreplyErrorTypes.LOGIN_FAILED,
                 AutoreplyErrorTypes.OTP_NOT_RECEIVED,
                 AutoreplyErrorTypes.FIELD_NOT_FOUND,
                 AutoreplyErrorTypes.BUTTON_NOT_FOUND,
                 AutoreplyErrorTypes.EMAIL_INVALID,
                 AutoreplyErrorTypes.TARIFF_LIMIT,
                 AutoreplyErrorTypes.ORDER_EXECUTOR_SELECTED,
                 AutoreplyErrorTypes.SITE_MAINTENANCE,
                 AutoreplyErrorTypes.EMAIL_CODE_LOGIN_ENABLED -> AutoreplyFailureAction.ABORT;
            default -> AutoreplyFailureAction.ABORT;
        };
    }
}
