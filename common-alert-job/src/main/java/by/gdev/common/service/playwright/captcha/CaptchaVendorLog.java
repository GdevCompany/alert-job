package by.gdev.common.service.playwright.captcha;

import lombok.extern.slf4j.Slf4j;

/**
 * Однозначные строки в логе: какой внешний/локальный сервис что отдал и что мы с этим делаем.
 * Ищите в логе префикс {@code CAPTCHA_ОТВЕТ}.
 */
@Slf4j
final class CaptchaVendorLog {

    private CaptchaVendorLog() {
    }

    /** Человекочитаемое имя провайдера по URL API (в логе «кто ответил»). */
    static String providerLabel(String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            return "2Captcha";
        }
        String u = apiBaseUrl.toLowerCase();
        if (u.contains("rucaptcha")) {
            return "ruCaptcha";
        }
        if (u.contains("2captcha")) {
            return "2Captcha";
        }
        return apiBaseUrl;
    }

    private static String serviceColumn(String apiBaseUrl) {
        return providerLabel(apiBaseUrl) + " (" + apiBaseUrl + ")";
    }

    static void route(String scene, String nextStep) {
        log.info("CAPTCHA_ОТВЕТ | МАРШРУТ | {} | дальше: {}", scene, nextStep);
    }

    static void vendorRequest(String serviceUrl, String apiMethod, String taskType, long taskId, String details) {
        log.info("CAPTCHA_ОТВЕТ | ЗАПРОС | сервис={} | {} | тип_задачи={} | taskId={} | {}",
                serviceColumn(serviceUrl), apiMethod, taskType, taskId, details == null ? "" : details);
    }

    static void vendorResponse(String serviceUrl, String taskType, long taskId, String rawAnswer,
                               String weUse, String appliedBy) {
        log.info("CAPTCHA_ОТВЕТ | ОТВЕТ | сервис={} | getTaskResult | тип_задачи={} | taskId={} | что_отдал={} | используем={} | применяет={}",
                serviceColumn(serviceUrl), taskType, taskId, truncate(rawAnswer, 280), weUse, appliedBy);
    }

    /**
     * Тело ответа HTTP API без обрезки (ищите в логе {@code CAPTCHA_СЫРОЙ_ОТВЕТ}).
     */
    static void vendorRawHttp(String serviceUrl, String apiMethod, String taskType, long taskId, String httpBody) {
        log.info("CAPTCHA_СЫРОЙ_ОТВЕТ | сервис={} | {} | тип_задачи={} | taskId={} | json={}",
                serviceColumn(serviceUrl), apiMethod, taskType == null ? "" : taskType, taskId,
                httpBody == null ? "" : httpBody);
    }

    /** JSON тела запроса к API капчи (ищите {@code CAPTCHA_JSON | ОТПРАВКА}). */
    static void vendorJsonSend(String serviceUrl, String httpPath, String apiMethod, String taskType, long taskId,
                               String jsonBody) {
        log.info("CAPTCHA_JSON | ОТПРАВКА | сервис={} | path={} | {} | тип_задачи={} | taskId={} | json={}",
                serviceColumn(serviceUrl), httpPath, apiMethod, taskType == null ? "" : taskType, taskId,
                jsonBody == null ? "" : jsonBody);
    }

    /** JSON тела ответа API капчи (ищите {@code CAPTCHA_JSON | ПОЛУЧЕНИЕ}). */
    static void vendorJsonReceive(String serviceUrl, String httpPath, String apiMethod, String taskType, long taskId,
                                  String jsonBody) {
        log.info("CAPTCHA_JSON | ПОЛУЧЕНИЕ | сервис={} | path={} | {} | тип_задачи={} | taskId={} | json={}",
                serviceColumn(serviceUrl), httpPath, apiMethod, taskType == null ? "" : taskType, taskId,
                jsonBody == null ? "" : jsonBody);
    }

    static void localResult(String component, String action, String outcome) {
        log.info("CAPTCHA_ОТВЕТ | ЛОКАЛЬНО | компонент={} | действие={} | результат={}",
                component, action, outcome);
    }

    static void applyVendorAnswer(String serviceUrl, String taskType, long taskId, String summary, String appliedBy) {
        log.info("CAPTCHA_ОТВЕТ | ПРИМЕНЕНИЕ | сервис={} | тип_задачи={} | taskId={} | {} | применяет={}",
                serviceColumn(serviceUrl), taskType, taskId, summary, appliedBy);
    }

    static String interpretPazlRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return "пустой solution";
        }
        String s = raw.trim();
        if (s.matches("-?\\d{1,3}")) {
            int n = Integer.parseInt(s);
            if (n <= 0) {
                return "число кликов " + n + " — НЕТ (нужно > 0)";
            }
            return "число кликов по стрелке слайдера = " + n + " — ДА";
        }
        if (s.toLowerCase().contains("coordinates") || s.contains("x=")) {
            return "coordinates (клики по PNG) — НЕТ для калейдоскопа-слайдера";
        }
        return "неизвестный формат — см. что_отдал";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
