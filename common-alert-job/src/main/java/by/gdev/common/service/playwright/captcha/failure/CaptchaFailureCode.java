package by.gdev.common.service.playwright.captcha.failure;

/**
 * Код последней неудачи прохождения капчи (FL.ru validate-captcha, SmartCaptcha на логине и т.д.).
 */
public enum CaptchaFailureCode {
    /** /validate-captcha — hCaptcha, 2Captcha не вернула токен */
    FLRU_VALIDATE_HCAPTCHA_TWO_CAPTCHA,
    /** /validate-captcha — hCaptcha visual puzzle после локального клика */
    FLRU_VALIDATE_HCAPTCHA_VISUAL,
    /** /validate-captcha — токен есть, но страница не снята */
    FLRU_VALIDATE_HCAPTCHA_STUCK,
    /** /validate-captcha — Yandex «Я человек» / SmartCaptcha */
    FLRU_VALIDATE_YANDEX,
    /** /validate-captcha — сценарий не распознан или общий провал */
    FLRU_VALIDATE_UNKNOWN,

    /** Форма логина — Yandex SmartCaptcha, чекбокс (нет bbox / клик не прошёл) */
    FLRU_LOGIN_YANDEX_CHECKBOX,
    /** Форма логина — Yandex advanced / visual, 2Captcha не помогла */
    FLRU_LOGIN_YANDEX_ADVANCED,
    /** Форма логина — SmartCaptcha общий провал */
    FLRU_LOGIN_YANDEX_UNKNOWN,

    /** YouDo / ServicePipe — Kaleidoscope, 2Captcha Pazl или извлечение task/canvas */
    YOUDO_KALEIDOSCOPE_FAIL,

    /** Cloudflare / прочие */
    CAPTCHA_GENERIC,
    CAPTCHA_EXCEPTION
}
