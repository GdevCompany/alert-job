package by.gdev.alert.job.notification.service.ai.parser.youdo;

import org.slf4j.Logger;

/**
 * Состояния входа YouDo (диаграмма для логов). Капча ({@link #CAPTCHA_GUARD}) может вклиниться перед любым шагом.
 */
public enum YouDoLoginFsmState {

    NAVIGATE_HOME("Открытие youdo.com"),
    PROXY_BLOCKED("403: прокси/IP заблокирован защитой"),
    DISMISS_COOKIES("Снятие cookie-баннера"),
    OPEN_LOGIN_MODAL("Модалка «Войти»"),
    CHOOSE_EMAIL("Вход через email"),
    FILL_EMAIL("Ввод email"),
    CAPTCHA_GUARD("Проверка капчи перед шагом"),
    CAPTCHA_CHECKBOX("Капча: «Я не робот»"),
    CAPTCHA_KALEIDOSCOPE("Капча: калейдоскоп / advanced"),
    CAPTCHA_TWO_CAPTCHA("Решение через 2Captcha"),
    SUBMIT_EMAIL("Отправка email («Далее»)"),
    POST_CAPTCHA_SUBMIT("Повтор отправки после smart-token"),
    WAIT_OTP("Ожидание поля OTP"),
    ENTER_OTP("Ввод OTP"),
    DONE("Вход выполнен"),
    ABORT("Ошибка");

    private final String title;

    YouDoLoginFsmState(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public static void log(Logger log, YouDoLoginFsmState state, String user, String extra) {
        if (extra == null || extra.isBlank()) {
            log.info("YOUDO FSM: {} — {} | user={}", state.name(), state.title, user);
        } else {
            log.info("YOUDO FSM: {} — {} | user={} | {}", state.name(), state.title, user, extra);
        }
    }
}
