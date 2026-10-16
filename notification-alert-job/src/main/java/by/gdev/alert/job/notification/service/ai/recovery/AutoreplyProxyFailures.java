package by.gdev.alert.job.notification.service.ai.recovery;

import by.gdev.alert.job.notification.service.ai.metrics.errors.AutoreplyErrorTypes;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.common.service.playwright.flru.FlRuPlaywrightGuards;

import java.util.Locale;
import java.util.Set;

/**
 * Отделение сбоев прокси/сети от ошибок учётки и UI (для логов, писем и счётчика ротации).
 */
public final class AutoreplyProxyFailures {

    private static final Set<String> PROXY_ERROR_CODES = Set.of(
            AutoreplyErrorTypes.PROXY_FAILED,
            AutoreplyErrorTypes.TIMEOUT,
            AutoreplyErrorTypes.ERROR_OPEN_PAGE,
            AutoreplyErrorTypes.CAPTCHA_FAILED
    );

    /** Не прокси: учётка, бизнес-страницы FL.ru, техработы на стороне биржи. */
    private static final Set<String> NON_PROXY_ERROR_CODES = Set.of(
            AutoreplyErrorTypes.SITE_MAINTENANCE,
            AutoreplyErrorTypes.LOGIN_FAILED,
            AutoreplyErrorTypes.OTP_NOT_RECEIVED,
            AutoreplyErrorTypes.ORDER_EXECUTOR_SELECTED,
            AutoreplyErrorTypes.EMAIL_INVALID,
            AutoreplyErrorTypes.TARIFF_LIMIT,
            AutoreplyErrorTypes.EMAIL_CODE_LOGIN_ENABLED,
            AutoreplyErrorTypes.FIELD_NOT_FOUND,
            AutoreplyErrorTypes.BUTTON_NOT_FOUND
    );

    private AutoreplyProxyFailures() {
    }

    public static boolean isProxyRelated(String errorCode, String message) {
        if (isDefiniteNonProxy(errorCode, message)) {
            return false;
        }
        if (errorCode != null && PROXY_ERROR_CODES.contains(errorCode)) {
            return true;
        }
        if (message != null && message.contains(FlRuPlaywrightGuards.DDOS_RETRY_FAIL_MARKER)) {
            return true;
        }
        return isProxyRelatedMessage(message);
    }

    public static boolean isProxyRelated(StepResult<?> result) {
        if (result == null || !result.failed()) {
            return false;
        }
        return isProxyRelated(result.getErrorCode(), result.getErrorMessage());
    }

    /**
     * Если сбой похож на прокси, но код другой/пустой — нормализуем в {@link AutoreplyErrorTypes#PROXY_FAILED}.
     */
    public static <T> StepResult<T> tagProxyFailure(StepResult<T> result) {
        if (!isProxyRelated(result)) {
            return result;
        }
        if (AutoreplyErrorTypes.PROXY_FAILED.equals(result.getErrorCode())) {
            return result;
        }
        StepType step = result.step() != null ? result.step() : StepType.SEND_AUTOREPLY;
        return StepResult.fail(step, AutoreplyErrorTypes.PROXY_FAILED,
                result.getErrorMessage(), result.getScreenshot());
    }

    public static String errorCodeForThrowable(Throwable throwable) {
        if (isProxyRelatedThrowable(throwable)) {
            return AutoreplyErrorTypes.PROXY_FAILED;
        }
        return null;
    }

    private static boolean isProxyRelatedThrowable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (isProxyRelatedMessage(throwable.getMessage())) {
            return true;
        }
        return isProxyRelatedThrowable(throwable.getCause());
    }

    private static boolean isDefiniteNonProxy(String errorCode, String message) {
        if (errorCode != null && NON_PROXY_ERROR_CODES.contains(errorCode)) {
            return true;
        }
        if (message == null || message.isBlank()) {
            return false;
        }
        if (message.contains(FlRuPlaywrightGuards.SITE_MAINTENANCE_MARKER)) {
            return true;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("технические работы")
                || lower.contains("техобслуживан");
    }

    private static boolean isProxyRelatedMessage(String message) {
        if (isDefiniteNonProxy(null, message)) {
            return false;
        }
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("targetclosed") || m.contains("browser has been closed")) {
            return true;
        }
        if (m.contains("err_tunnel") || m.contains("tunnel_connection_failed") || m.contains("net::err_")) {
            return true;
        }
        if (m.contains("proxy auth failed") || m.contains("(407)")) {
            return true;
        }
        if (m.contains("connection refused") || m.contains("connection reset")) {
            return true;
        }
        if (m.contains("timeout") && (m.contains("exceeded") || m.contains("navigat"))) {
            return true;
        }
        if (m.contains("подозрительн") && m.contains("активност")) {
            return true;
        }
        if (m.contains("блок ip") || m.contains("ip-блок") || m.contains("ip блок")) {
            return true;
        }
        if (m.contains("не удалось открыть") || m.contains("error open page")) {
            return true;
        }
        return false;
    }
}
