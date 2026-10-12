package by.gdev.alert.job.notification.service.ai.metrics.errors;

public final class AutoreplyErrorTypes {
    public static final String BUTTON_NOT_FOUND = "BUTTON_NOT_FOUND";
    public static final String FIELD_NOT_FOUND = "FIELD_NOT_FOUND";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String ERROR_OPEN_PAGE = "ERROR_OPEN_PAGE";
    public static final String EMAIL_INVALID = "EMAIL_INVALID";
    public static final String OTP_NOT_RECEIVED = "OTP_NOT_RECEIVED";
    public static final String TARIFF_LIMIT = "TARIFF_LIMIT";
    public static final String CAPTCHA_FAILED = "CAPTCHA_FAILED";
    public static final String EMAIL_CODE_LOGIN_ENABLED = "EMAIL_CODE_LOGIN_ENABLED";
    /** Заказ на FL.ru закрыт — заказчик уже выбрал исполнителя, отклики не принимаются. */
    public static final String ORDER_EXECUTOR_SELECTED = "ORDER_EXECUTOR_SELECTED";
    /** FL.ru недоступен — на сайте технические работы. */
    public static final String SITE_MAINTENANCE = "SITE_MAINTENANCE";
}