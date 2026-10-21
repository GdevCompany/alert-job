package by.gdev.common.service.playwright.captcha;

import by.gdev.common.model.proxy.ProxyCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-клиент 2Captcha API v2:
 * Yandex SmartCaptcha (token, proxyless), AudioTask, опционально PazlCaptchaTask, hCaptcha.
 */
@Component
@Slf4j
public class TwoCaptchaClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${captcha.two-captcha.enabled:false}")
    private boolean enabled;

    @Value("${captcha.two-captcha.api-key:}")
    private String apiKey;

    /**
     * API v2: по умолчанию {@code https://api.2captcha.com}.
     * ruCaptcha — тот же протокол, другой хост: {@code captcha.two-captcha.api-base-url=https://api.rucaptcha.com}.
     */
    @Value("${captcha.two-captcha.api-base-url:https://api.2captcha.com}")
    private String apiBaseUrl;

    @Value("${captcha.two-captcha.poll-interval-ms:3000}")
    private long pollIntervalMs;

    @Value("${captcha.two-captcha.timeout-ms:180000}")
    private long timeoutMs;

    /** Сколько раз создавать новую задачу при timeout/processing (только hCaptcha / Yandex). */
    @Value("${captcha.two-captcha.task-max-attempts:2}")
    private int taskMaxAttempts;

    /** reportIncorrect / reportCorrect (возврат средств у 2Captcha). */
    @Value("${captcha.two-captcha.reports.enabled:true}")
    private boolean reportsEnabled;

    /**
     * Передавать в 2Captcha тот же HTTP-прокси, что у Playwright ({@code YandexSmartCaptchaTask}),
     * чтобы IP решения совпадал с сессией браузера (важно для YouDo).
     */
    @Value("${captcha.yandex.two-captcha-use-browser-proxy:true}")
    private boolean yandexUseBrowserProxy;

    @PostConstruct
    void logConfiguration() {
        if (!enabled) {
            log.info("2Captcha: выключено (captcha.two-captcha.enabled=false)");
            return;
        }
        String key = effectiveApiKey();
        if (key.isBlank()) {
            log.warn("2Captcha: включено, но captcha.two-captcha.api-key / TWO_CAPTCHA_API_KEY пустой");
            return;
        }
        log.info("2Captcha: включено, провайдер={}, apiBase={}, clientKey={}",
                CaptchaVendorLog.providerLabel(normalizedApiBase()), normalizedApiBase(), maskClientKey(key));
        if (apiKey != null && !apiKey.equals(key)) {
            log.warn("2Captcha: в api-key были пробелы по краям — в запросах используется trim()");
        }
        log.debug("2Captcha: clientKey (полностью)={}", key);
    }

    public boolean isConfigured() {
        return enabled && !effectiveApiKey().isBlank();
    }

    private String effectiveApiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    private String normalizedApiBase() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            return "https://api.2captcha.com";
        }
        String base = apiBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String apiUrl(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        return normalizedApiBase() + p;
    }

    private static String maskClientKey(String key) {
        if (key == null || key.isBlank()) {
            return "<empty>";
        }
        if (key.length() <= 8) {
            return key.charAt(0) + "*** (len=" + key.length() + ")";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4) + " (len=" + key.length() + ")";
    }

    /**
     * @return токен smart-captcha или empty при ошибке / отключённом сервисе
     */
    public Optional<String> solveYandexSmartCaptcha(String pageUrl, String siteKey, String userAgent, String cookies) {
        if (!isConfigured()) {
            log.warn("2Captcha: запрос пропущен — enabled={}, clientKey={}",
                    enabled, maskClientKey(effectiveApiKey()));
            return Optional.empty();
        }
        if (siteKey == null || siteKey.isBlank()) {
            log.warn("2Captcha: sitekey не найден для {}", pageUrl);
            return Optional.empty();
        }
        return solveYandexSmartCaptcha(pageUrl, siteKey, userAgent, cookies, null);
    }

    /**
     * Решает Yandex SmartCaptcha через 2Captcha.
     * При {@code captcha.yandex.two-captcha-use-browser-proxy=true} и непустом {@code proxy}
     * создаётся {@code YandexSmartCaptchaTask} с тем же HTTP-прокси, что у браузера.
     */
    public Optional<String> solveYandexSmartCaptcha(String pageUrl, String siteKey, String userAgent, String cookies,
                                                    ProxyCredentials proxy) {
        if (!isConfigured()) {
            log.warn("2Captcha: запрос пропущен — enabled={}, clientKey={}",
                    enabled, maskClientKey(effectiveApiKey()));
            return Optional.empty();
        }
        if (siteKey == null || siteKey.isBlank()) {
            log.warn("2Captcha: sitekey не найден для {}", pageUrl);
            return Optional.empty();
        }
        ProxyCredentials effectiveProxy = resolveYandexProxy(proxy);
        String kind = effectiveProxy != null
                ? "Yandex SmartCaptcha (browser proxy)"
                : "Yandex SmartCaptcha (proxyless)";
        if (effectiveProxy != null) {
            log.info("2Captcha: Yandex SmartCaptcha через прокси браузера {}:{} (type=YandexSmartCaptchaTask)",
                    effectiveProxy.getHost(), effectiveProxy.getPort());
        }
        return solveWithRetries(pageUrl, kind, () -> {
            long taskId = createYandexTask(pageUrl, siteKey, userAgent, cookies, effectiveProxy);
            return pollTaskResult(taskId, kind);
        });
    }

    private ProxyCredentials resolveYandexProxy(ProxyCredentials proxy) {
        if (!yandexUseBrowserProxy || proxy == null) {
            return null;
        }
        if (proxy.getHost() == null || proxy.getHost().isBlank() || proxy.getPort() <= 0) {
            return null;
        }
        return proxy;
    }

    /**
     * Решение PazlCaptcha: либо число кликов ({@code "8"}), либо точка на отправленном PNG
     * ({@code coordinates:x=189,y=63}). Ширина/letterbox учитываются в CaptchaService при маппинге на bbox.
     */
    public record PazlSolution(Integer clicks, Integer imageX, Integer imageY, int imageWidth, long twoCaptchaTaskId) {
        public PazlSolution(Integer clicks, Integer imageX, int imageWidth) {
            this(clicks, imageX, null, imageWidth, 0L);
        }

        public PazlSolution(Integer clicks, Integer imageX, Integer imageY, int imageWidth) {
            this(clicks, imageX, imageY, imageWidth, 0L);
        }

        public boolean hasImageX() {
            return imageX != null && imageX >= 0;
        }

        public boolean hasClicks() {
            return clicks != null && clicks >= 0;
        }

        public boolean hasTaskId() {
            return twoCaptchaTaskId > 0;
        }
    }

    /** Результат одной серии запросов PazlCaptchaTask к 2Captcha. */
    public record PazlCaptchaInvokeResult(Optional<PazlSolution> solution, boolean audioFallback) {

        public static PazlCaptchaInvokeResult solved(PazlSolution s) {
            return new PazlCaptchaInvokeResult(Optional.of(s), false);
        }

        public static PazlCaptchaInvokeResult forAudioFallback() {
            return new PazlCaptchaInvokeResult(Optional.empty(), true);
        }

        public static PazlCaptchaInvokeResult notSolved() {
            return new PazlCaptchaInvokeResult(Optional.empty(), false);
        }
    }

    /**
     * Калейдоскоп (слайдер-пазл). По доке 2Captcha solution = число кликов по стрелке ({@code "8"}).
     * Воркеры иногда отдают {@code coordinates:…} (клики по картинке) — это не ответ Pazl, такие
     * решения отбрасываем и создаём новую задачу.
     * <p>
     * При первом {@code solution=0} сразу {@link PazlCaptchaInvokeResult#forAudioFallback()} — без повторных Pazl.
     */
    public PazlCaptchaInvokeResult solvePazlCaptcha(String imageBase64, String taskJson,
                                                    int sliderMax, int imageWidth) {
        if (!isConfigured()) {
            log.warn("2Captcha: PazlCaptcha пропущен — enabled={}, clientKey={}",
                    enabled, maskClientKey(effectiveApiKey()));
            return PazlCaptchaInvokeResult.notSolved();
        }
        if (imageBase64 == null || imageBase64.isBlank() || taskJson == null || taskJson.isBlank()) {
            log.warn("2Captcha: PazlCaptcha — пустые image/task");
            return PazlCaptchaInvokeResult.notSolved();
        }
        String image = stripDataUrlPrefix(imageBase64);
        int max = sliderMax > 0 ? Math.min(sliderMax, 64) : 28;
        int imgW = imageWidth > 0 ? imageWidth : decodePngWidth(image).orElse(0);
        // coordinates от воркеров частые — больше попыток, пока не придёт число кликов
        int attempts = Math.max(4, taskMaxAttempts + 2);
        Exception lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                log.warn("2Captcha: PazlCaptcha — повторная задача {}/{}", attempt, attempts);
            }
            try {
                long taskId = createPazlTask(image, taskJson);
                Optional<String> raw = pollTaskResult(taskId, "PazlCaptcha");
                if (raw.isEmpty()) {
                    log.warn("2Captcha: PazlCaptcha — попытка {}/{} без solution", attempt, attempts);
                    continue;
                }
                String rawSol = raw.get();
                log.info("2Captcha: PazlCaptcha raw solution={} imageW={}", rawSol, imgW);
                Optional<PazlSolution> sol = parsePazlSolution(rawSol, max, imgW);
                if (sol.isEmpty()) {
                    log.warn("2Captcha: PazlCaptcha — не разобрали solution (см. raw выше), reportIncorrect");
                    reportIncorrect(taskId);
                    continue;
                }
                PazlSolution s = sol.get();
                if (s.hasClicks() && s.clicks() != null && s.clicks() > 0) {
                    PazlSolution withId = new PazlSolution(s.clicks(), s.imageX(), s.imageY(), s.imageWidth(), taskId);
                    log.info("2Captcha: PazlCaptcha → clicks={} sliderMax={} (taskId={})",
                            withId.clicks(), max, taskId);
                    CaptchaVendorLog.applyVendorAnswer(normalizedApiBase(), "PazlCaptchaTask", taskId,
                            "кликов по стрелке слайдера: " + withId.clicks(),
                            "Playwright YandexKaleidoscopeSolver.applySliderClicks");
                    return PazlCaptchaInvokeResult.solved(withId);
                }
                if (s.hasClicks() && s.clicks() != null && s.clicks() == 0) {
                    log.warn("2Captcha: PazlCaptcha → solution=0 — reportIncorrect и переход на audio, taskId={}",
                            taskId);
                    CaptchaVendorLog.vendorResponse(normalizedApiBase(), "PazlCaptchaTask", taskId, rawSol,
                            "НЕТ (solution=0)", "YandexSmartCaptchaAudioSolver (AudioTask в сессии браузера)");
                    reportIncorrect(taskId);
                    return PazlCaptchaInvokeResult.forAudioFallback();
                }
                // coordinates — воркер решил как click-captcha, для слайдера бесполезно
                log.warn("2Captcha: PazlCaptcha → coordinates вместо кликов (x={} y={}) — reportIncorrect",
                        s.imageX(), s.imageY());
                CaptchaVendorLog.vendorResponse(normalizedApiBase(), "PazlCaptchaTask", taskId, rawSol,
                        CaptchaVendorLog.interpretPazlRaw(rawSol), "никто — reportIncorrect + новая задача");
                reportIncorrect(taskId);
            } catch (Exception e) {
                lastError = e;
                log.warn("2Captcha: PazlCaptcha — попытка {}/{} ошибка: {}", attempt, attempts, e.getMessage());
            }
        }
        if (lastError != null) {
            log.error("2Captcha: не удалось решить PazlCaptcha", lastError);
        }
        log.warn("2Captcha: PazlCaptcha — за {} попыток не получили число кликов", attempts);
        return PazlCaptchaInvokeResult.notSolved();
    }

    public PazlCaptchaInvokeResult solvePazlCaptcha(String imageBase64, String taskJson, int sliderMax) {
        return solvePazlCaptcha(imageBase64, taskJson, sliderMax, 0);
    }

    public PazlCaptchaInvokeResult solvePazlCaptcha(String imageBase64, String taskJson) {
        return solvePazlCaptcha(imageBase64, taskJson, 28, 0);
    }

    /**
     * solution = "8" | {"clicks":8} | "coordinates:x=189,y=63;x=194,y=106" | {"coordinates":[{"x":189,"y":63}]}
     */
    static Optional<PazlSolution> parsePazlSolution(String raw, int sliderMax, int imageWidth) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        int max = sliderMax > 0 ? Math.min(sliderMax, 64) : 28;
        int imgW = imageWidth > 0 ? imageWidth : 0;
        if (s.matches("-?\\d{1,3}")) {
            int c = Integer.parseInt(s);
            return (c >= 0 && c <= 64) ? Optional.of(new PazlSolution(Math.min(c, max), null, imgW)) : Optional.empty();
        }
        // coordinates:x=189,y=63;x=194,y=106 — последний x/y как целевая точка на PNG
        Matcher coordStr = Pattern.compile(
                "x\\s*=\\s*(\\d+)\\s*,\\s*y\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
        int lastX = -1;
        int lastY = -1;
        while (coordStr.find()) {
            lastX = Integer.parseInt(coordStr.group(1));
            lastY = Integer.parseInt(coordStr.group(2));
        }
        if (lastX < 0) {
            Matcher xOnly = Pattern.compile("x\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
            while (xOnly.find()) {
                lastX = Integer.parseInt(xOnly.group(1));
            }
        }
        if (lastX >= 0) {
            return Optional.of(new PazlSolution(null, lastX, lastY >= 0 ? lastY : null, imgW));
        }
        try {
            JsonNode n = new ObjectMapper().readTree(s);
            if (n.isNumber()) {
                int c = n.asInt();
                return (c >= 0 && c <= 64) ? Optional.of(new PazlSolution(Math.min(c, max), null, imgW)) : Optional.empty();
            }
            if (n.isTextual()) {
                return parsePazlSolution(n.asText(), max, imgW);
            }
            if (n.isObject()) {
                JsonNode coords = n.get("coordinates");
                if (coords != null && coords.isArray() && coords.size() > 0) {
                    JsonNode last = coords.get(coords.size() - 1);
                    if (last != null && last.has("x")) {
                        int x = last.get("x").asInt(-1);
                        Integer y = last.has("y") ? last.get("y").asInt() : null;
                        if (x >= 0) {
                            return Optional.of(new PazlSolution(null, x, y, imgW));
                        }
                    }
                }
                for (String key : List.of("clicks", "click", "answer", "text", "solution")) {
                    JsonNode v = n.get(key);
                    if (v == null || v.isNull()) {
                        continue;
                    }
                    if (v.isNumber()) {
                        int c = v.asInt();
                        if (c >= 0 && c <= 64) {
                            return Optional.of(new PazlSolution(Math.min(c, max), null, imgW));
                        }
                    } else if (v.isTextual()) {
                        Optional<PazlSolution> nested = parsePazlSolution(v.asText(), max, imgW);
                        if (nested.isPresent()) {
                            return nested;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    static Optional<PazlSolution> parsePazlSolution(String raw, int sliderMax) {
        return parsePazlSolution(raw, sliderMax, 0);
    }

    static Optional<PazlSolution> parsePazlSolution(String raw) {
        return parsePazlSolution(raw, 28, 0);
    }

    /** Ширина PNG из base64 (без data: prefix). */
    static OptionalInt decodePngWidth(String imageBase64) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(imageBase64.trim());
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null || img.getWidth() <= 0) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(img.getWidth());
        } catch (Exception e) {
            return OptionalInt.empty();
        }
    }

    private static String stripDataUrlPrefix(String imageBase64) {
        String s = imageBase64.trim();
        int idx = s.indexOf("base64,");
        if (idx >= 0) {
            return s.substring(idx + "base64,".length());
        }
        return s;
    }

    /** @return hCaptcha response token (gRecaptchaResponse) */
    public Optional<String> solveHCaptcha(String pageUrl, String siteKey, String userAgent, String cookies) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        if (siteKey == null || siteKey.isBlank()) {
            log.warn("2Captcha: hCaptcha sitekey не найден для {}", pageUrl);
            return Optional.empty();
        }
        return solveWithRetries(pageUrl, "hCaptcha", () -> {
            long taskId = createHCaptchaTask(pageUrl, siteKey, userAgent, cookies);
            return pollTaskResult(taskId, "hCaptcha");
        });
    }

    public Optional<String> solveHCaptcha(String pageUrl, String siteKey, String userAgent) {
        return solveHCaptcha(pageUrl, siteKey, userAgent, null);
    }

    /**
     * Распознавание аудио (Yandex SmartCaptcha audio: «введите четыре цифры»).
     * @return raw text + taskId (для reportIncorrect, если не цифры)
     */
    public Optional<AudioResult> solveAudioCaptcha(byte[] audioBytes, String lang) {
        if (!isConfigured()) {
            log.warn("2Captcha: AudioTask пропущен — enabled={}, clientKey={}",
                    enabled, maskClientKey(effectiveApiKey()));
            return Optional.empty();
        }
        if (audioBytes == null || audioBytes.length < 200) {
            log.warn("2Captcha: AudioTask — пустое/короткое аудио len={}",
                    audioBytes == null ? -1 : audioBytes.length);
            return Optional.empty();
        }
        String language = (lang == null || lang.isBlank()) ? "ru" : lang.trim();
        String bodyB64 = java.util.Base64.getEncoder().encodeToString(audioBytes);
        int attempts = Math.max(2, taskMaxAttempts);
        Exception lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                log.warn("2Captcha: AudioTask — повторная задача {}/{}", attempt, attempts);
            }
            try {
                long taskId = createAudioTask(bodyB64, language);
                Optional<String> text = pollTaskResult(taskId, "AudioTask");
                if (text.isPresent() && !text.get().isBlank()) {
                    log.info("2Captcha: AudioTask → text='{}' taskId={}", text.get().trim(), taskId);
                    return Optional.of(new AudioResult(text.get().trim(), taskId));
                }
                log.warn("2Captcha: AudioTask — попытка {}/{} без текста", attempt, attempts);
            } catch (Exception e) {
                lastError = e;
                log.warn("2Captcha: AudioTask — попытка {}/{} ошибка: {}", attempt, attempts, e.getMessage());
            }
        }
        if (lastError != null) {
            log.error("2Captcha: не удалось решить AudioTask", lastError);
        }
        return Optional.empty();
    }

    public record AudioResult(String text, long taskId) {}

    /** Публичный reportIncorrect (неверный solution / не прошло на сайте). */
    public void reportIncorrect(long taskId) {
        if (!reportsEnabled || taskId <= 0) {
            return;
        }
        reportIncorrectQuiet(taskId);
    }

    /** reportCorrect — если solution реально помог на сайте (возврат не нужен, обучение воркеров). */
    public void reportCorrect(long taskId) {
        if (!reportsEnabled || taskId <= 0) {
            return;
        }
        reportCorrectQuiet(taskId);
    }

    private long createAudioTask(String audioBase64, String lang) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "AudioTask");
        task.put("body", audioBase64);
        task.put("lang", lang);

        Map<String, Object> body = new LinkedHashMap<>();
        String clientKey = effectiveApiKey();
        body.put("clientKey", clientKey);
        body.put("languagePool", "ru");
        body.put("task", task);

        log.info("2Captcha: createTask AudioTask → {} clientKey={} audioB64Len={} lang={}",
                apiUrl("/createTask"), maskClientKey(clientKey), audioBase64.length(), lang);

        JsonNode response = postCaptchaJson("/createTask", body, "createTask", "AudioTask", 0, true, true);
        int errorId = response.path("errorId").asInt(-1);
        if (errorId != 0) {
            throw new IllegalStateException("createTask AudioTask errorId=" + errorId
                    + ", code=" + response.path("errorCode").asText()
                    + ", desc=" + response.path("errorDescription").asText());
        }
        long taskId = response.path("taskId").asLong();
        if (taskId <= 0) {
            throw new IllegalStateException("createTask AudioTask: пустой taskId");
        }
        log.info("2Captcha: задача AudioTask создана, taskId={}", taskId);
        CaptchaVendorLog.vendorRequest(normalizedApiBase(), "createTask", "AudioTask", taskId,
                "mp3 base64 → ожидаем solution.text");
        return taskId;
    }

    private Optional<String> solveWithRetries(String pageUrl, String kind, TaskPollSupplier supplier) {
        int attempts = Math.max(1, taskMaxAttempts);
        Exception lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                log.warn("2Captcha: {} для {} — повторная задача {}/{}", kind, pageUrl, attempt, attempts);
            }
            try {
                Optional<String> token = supplier.poll();
                if (token.isPresent()) {
                    return token;
                }
                log.warn("2Captcha: {} для {} — попытка {}/{} без токена (timeout или processing)",
                        kind, pageUrl, attempt, attempts);
            } catch (Exception e) {
                lastError = e;
                log.warn("2Captcha: {} для {} — попытка {}/{} ошибка: {}",
                        kind, pageUrl, attempt, attempts, e.getMessage());
            }
        }
        if (lastError != null) {
            log.error("2Captcha: не удалось решить {} для {}", kind, pageUrl, lastError);
        }
        return Optional.empty();
    }

    @FunctionalInterface
    private interface TaskPollSupplier {
        Optional<String> poll() throws Exception;
    }

    private long createYandexTask(String pageUrl, String siteKey, String userAgent, String cookies,
                                  ProxyCredentials proxy) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>();
        if (proxy != null && proxy.getHost() != null && !proxy.getHost().isBlank() && proxy.getPort() > 0) {
            task.put("type", "YandexSmartCaptchaTask");
            task.put("proxyType", "http");
            task.put("proxyAddress", proxy.getHost());
            task.put("proxyPort", proxy.getPort());
            if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
                task.put("proxyLogin", proxy.getUsername());
                task.put("proxyPassword", proxy.getPassword() != null ? proxy.getPassword() : "");
            }
        } else {
            task.put("type", "YandexSmartCaptchaTaskProxyless");
        }
        task.put("websiteURL", pageUrl);
        task.put("websiteKey", siteKey);
        if (userAgent != null && !userAgent.isBlank()) {
            task.put("userAgent", userAgent);
        }
        if (cookies != null && !cookies.isBlank()) {
            task.put("cookies", cookies);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        String clientKey = effectiveApiKey();
        body.put("clientKey", clientKey);
        body.put("task", task);

        log.info("2Captcha: createTask → {} type={} clientKey={}",
                apiUrl("/createTask"), task.get("type"), maskClientKey(clientKey));
        log.debug("2Captcha: createTask clientKey (полностью)={}", clientKey);

        String taskType = String.valueOf(task.get("type"));
        JsonNode response = postCaptchaJson("/createTask", body, "createTask", taskType, 0, true, true);
        int errorId = response.path("errorId").asInt(-1);
        if (errorId != 0) {
            throw new IllegalStateException("createTask errorId=" + errorId
                    + ", code=" + response.path("errorCode").asText()
                    + ", desc=" + response.path("errorDescription").asText());
        }
        long taskId = response.path("taskId").asLong();
        if (taskId <= 0) {
            throw new IllegalStateException("createTask: пустой taskId");
        }
        log.info("2Captcha: задача Yandex SmartCaptcha создана, taskId={}, type={}", taskId, task.get("type"));
        CaptchaVendorLog.vendorRequest(normalizedApiBase(), "createTask", taskType, taskId,
                "websiteURL + websiteKey → ожидаем smart-token для Yandex SmartCaptcha");
        return taskId;
    }

    private long createPazlTask(String imageBase64, String taskJson) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "PazlCaptchaTask");
        task.put("image", imageBase64);
        task.put("task", taskJson);

        Map<String, Object> body = new LinkedHashMap<>();
        String clientKey = effectiveApiKey();
        body.put("clientKey", clientKey);
        // RU-воркеры лучше знают калейдоскоп Яндекса
        body.put("languagePool", "ru");
        body.put("task", task);

        log.info("2Captcha: createTask PazlCaptchaTask → {} clientKey={} imageLen={} taskLen={} languagePool=ru taskPreview={}",
                apiUrl("/createTask"), maskClientKey(clientKey), imageBase64.length(), taskJson.length(),
                summarizeTaskArray(taskJson));

        JsonNode response = postCaptchaJson("/createTask", body, "createTask", "PazlCaptchaTask", 0, true, true);
        int errorId = response.path("errorId").asInt(-1);
        if (errorId != 0) {
            throw new IllegalStateException("createTask PazlCaptcha errorId=" + errorId
                    + ", code=" + response.path("errorCode").asText()
                    + ", desc=" + response.path("errorDescription").asText());
        }
        long taskId = response.path("taskId").asLong();
        if (taskId <= 0) {
            throw new IllegalStateException("createTask PazlCaptcha: пустой taskId");
        }
        log.info("2Captcha: задача PazlCaptcha создана, taskId={}", taskId);
        CaptchaVendorLog.vendorRequest(normalizedApiBase(), "createTask", "PazlCaptchaTask", taskId,
                "image PNG base64 + task JSON (калейдоскоп Яндекс)");
        return taskId;
    }

    /** Кратко: длина массива + первые значения (для сверки с докой / тикетом). */
    static String summarizeTaskArray(String taskJson) {
        if (taskJson == null || taskJson.isBlank()) {
            return "<empty>";
        }
        try {
            JsonNode n = new ObjectMapper().readTree(taskJson.trim());
            if (!n.isArray()) {
                return "not-array preview=" + (taskJson.length() > 60 ? taskJson.substring(0, 60) + "…" : taskJson);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("len=").append(n.size()).append(" first=[");
            int show = Math.min(10, n.size());
            for (int i = 0; i < show; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(n.get(i).asText());
            }
            if (n.size() > show) {
                sb.append(",…");
            }
            sb.append(']');
            return sb.toString();
        } catch (Exception e) {
            return "parse-fail preview=" + (taskJson.length() > 60 ? taskJson.substring(0, 60) + "…" : taskJson);
        }
    }

    private void reportIncorrectQuiet(long taskId) {
        try {
            Map<String, Object> body = Map.of(
                    "clientKey", effectiveApiKey(),
                    "taskId", taskId
            );
            JsonNode response = postCaptchaJson("/reportIncorrect", body, "reportIncorrect", "", taskId, true, true);
            log.info("2Captcha: reportIncorrect taskId={} errorId={} status={}",
                    taskId, response.path("errorId").asInt(-1), response.path("status").asText(""));
        } catch (Exception e) {
            log.debug("2Captcha: reportIncorrect taskId={}: {}", taskId, e.getMessage());
        }
    }

    private void reportCorrectQuiet(long taskId) {
        try {
            Map<String, Object> body = Map.of(
                    "clientKey", effectiveApiKey(),
                    "taskId", taskId
            );
            JsonNode response = postCaptchaJson("/reportCorrect", body, "reportCorrect", "", taskId, true, true);
            log.info("2Captcha: reportCorrect taskId={} errorId={} status={}",
                    taskId, response.path("errorId").asInt(-1), response.path("status").asText(""));
        } catch (Exception e) {
            log.debug("2Captcha: reportCorrect taskId={}: {}", taskId, e.getMessage());
        }
    }

    private long createHCaptchaTask(String pageUrl, String siteKey, String userAgent, String cookies) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "HCaptchaTaskProxyless");
        task.put("websiteURL", pageUrl);
        task.put("websiteKey", siteKey);
        if (userAgent != null && !userAgent.isBlank()) {
            task.put("userAgent", userAgent);
        }
        if (cookies != null && !cookies.isBlank()) {
            task.put("cookies", cookies);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        String clientKey = effectiveApiKey();
        body.put("clientKey", clientKey);
        body.put("task", task);

        log.info("2Captcha: createTask hCaptcha → {} clientKey={}", apiUrl("/createTask"), maskClientKey(clientKey));

        JsonNode response = postCaptchaJson("/createTask", body, "createTask", "HCaptchaTaskProxyless", 0, true, true);
        int errorId = response.path("errorId").asInt(-1);
        if (errorId != 0) {
            throw new IllegalStateException("createTask errorId=" + errorId
                    + ", code=" + response.path("errorCode").asText()
                    + ", desc=" + response.path("errorDescription").asText());
        }
        long taskId = response.path("taskId").asLong();
        if (taskId <= 0) {
            throw new IllegalStateException("createTask: пустой taskId");
        }
        log.info("2Captcha: задача hCaptcha создана, taskId={}", taskId);
        return taskId;
    }

    private Optional<String> pollTaskResult(long taskId, String captchaKind) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int pollAttempt = 0;
        String taskTypeForLog = taskTypeLabel(captchaKind);
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollIntervalMs);
            pollAttempt++;

            Map<String, Object> body = Map.of(
                    "clientKey", effectiveApiKey(),
                    "taskId", taskId
            );
            JsonNode response;
            try {
                boolean logExchange = pollAttempt == 1;
                response = postCaptchaJson("/getTaskResult", body, "getTaskResult", taskTypeForLog, taskId,
                        logExchange, logExchange);
            } catch (RestClientException e) {
                log.warn("2Captcha: ошибка getTaskResult для taskId={}: {}", taskId, e.getMessage());
                continue;
            }

            int errorId = response.path("errorId").asInt(-1);
            if (errorId != 0) {
                throw new IllegalStateException("getTaskResult errorId=" + errorId
                        + ", code=" + response.path("errorCode").asText()
                        + ", desc=" + response.path("errorDescription").asText());
            }

            String status = response.path("status").asText("");
            if ("processing".equalsIgnoreCase(status)) {
                if (pollAttempt == 1 || pollAttempt % 5 == 0) {
                    long leftMs = deadline - System.currentTimeMillis();
                    log.info("2Captcha: {} taskId={} poll #{} status=processing (~{} ms left)",
                            captchaKind, taskId, pollAttempt, Math.max(0, leftMs));
                }
                continue;
            }
            if ("ready".equalsIgnoreCase(status)) {
                if (pollAttempt > 1) {
                    logCaptchaJsonExchange("/getTaskResult", "getTaskResult", taskTypeForLog, taskId, body, response);
                }
                // PazlCaptcha: solution = "8" | объект | coordinates
                JsonNode solutionNode = response.get("solution");
                Optional<String> token = Optional.empty();
                if (solutionNode != null && solutionNode.isTextual() && !solutionNode.asText().isBlank()) {
                    token = Optional.of(solutionNode.asText().trim());
                } else if (solutionNode != null && solutionNode.isNumber()) {
                    token = Optional.of(String.valueOf(solutionNode.asInt()));
                } else if (solutionNode != null && solutionNode.isObject()) {
                    token = extractSolutionToken(solutionNode);
                    if (token.isEmpty()) {
                        for (String key : List.of("token", "gRecaptchaResponse", "clicks", "click", "answer", "text")) {
                            JsonNode v = solutionNode.get(key);
                            if (v != null && !v.isNull() && !v.asText("").isBlank()) {
                                token = Optional.of(v.asText().trim());
                                break;
                            }
                        }
                    }
                    // coordinates: [{x,y},...] → строка как у v1 API
                    if (token.isEmpty() && solutionNode.has("coordinates") && solutionNode.get("coordinates").isArray()) {
                        StringBuilder sb = new StringBuilder("coordinates:");
                        boolean first = true;
                        for (JsonNode p : solutionNode.get("coordinates")) {
                            if (!first) {
                                sb.append(';');
                            }
                            first = false;
                            sb.append("x=").append(p.path("x").asInt(0))
                                    .append(",y=").append(p.path("y").asInt(0));
                        }
                        if (!first) {
                            token = Optional.of(sb.toString());
                        }
                    }
                    // весь объект как JSON — parsePazlSolution разберёт
                    if (token.isEmpty()) {
                        token = Optional.of(solutionNode.toString());
                    }
                }
                if (token.isEmpty()) {
                    throw new IllegalStateException("getTaskResult: пустой token/solution: " + response);
                }
                // Полный ответ 2Captcha (для Pazl — solution целиком; для токенов — без обрезки длины)
                if ("PazlCaptcha".equals(captchaKind)) {
                    String rawJson = response.toString();
                    log.info("2Captcha: PazlCaptcha ready taskId={} fullResponse={}", taskId, rawJson);
                    log.info("2Captcha: PazlCaptcha solution={}", token.get());
                    CaptchaVendorLog.vendorResponse(normalizedApiBase(), "PazlCaptchaTask", taskId, token.get(),
                            CaptchaVendorLog.interpretPazlRaw(token.get()),
                            "пока никто — решает YandexKaleidoscopeSolver (клики слайдера)");
                } else if ("AudioTask".equals(captchaKind)) {
                    log.info("2Captcha: AudioTask ready taskId={}, textLen={}", taskId, token.get().length());
                    CaptchaVendorLog.vendorResponse(normalizedApiBase(), "AudioTask", taskId, token.get(),
                            "текст ответа audio challenge",
                            "YandexSmartCaptchaAudioSolver → поле ввода в iframe");
                } else {
                    log.info(
                            "2Captcha: {} getTaskResult=ready (токен от воркера, НЕ значит что YouDo принял капчу), taskId={}, tokenLen={}",
                            captchaKind, taskId, token.get().length());
                    String taskType = captchaKind.contains("proxy") && !captchaKind.contains("proxyless")
                            ? "YandexSmartCaptchaTask"
                            : "YandexSmartCaptchaTaskProxyless";
                    CaptchaVendorLog.vendorResponse(normalizedApiBase(), taskType, taskId,
                            "smart-token, длина=" + token.get().length() + " символов",
                            "ДА — вставка в поле smart-token на youdo.com",
                            "наш код CaptchaService.applyYandexSmartCaptchaToken");
                }
                return token;
            }
            throw new IllegalStateException("getTaskResult: неизвестный status=" + status);
        }
        log.warn("2Captcha: timeout {} ms для taskId={}", timeoutMs, taskId);
        return Optional.empty();
    }

    private Optional<String> extractSolutionToken(JsonNode solution) {
        if (solution == null || solution.isMissingNode()) {
            return Optional.empty();
        }
        String token = solution.path("token").asText(null);
        if (token != null && !token.isBlank()) {
            return Optional.of(token);
        }
        token = solution.path("gRecaptchaResponse").asText(null);
        if (token != null && !token.isBlank()) {
            return Optional.of(token);
        }
        token = solution.path("text").asText(null);
        if (token != null && !token.isBlank()) {
            return Optional.of(token);
        }
        return Optional.empty();
    }

    /** Для лога: полный JSON getTaskResult, token в solution заменён на &lt;redacted len=N&gt;. */
    private static String redactSmartTokenInJson(JsonNode response) {
        if (response == null) {
            return "";
        }
        try {
            JsonNode copy = response.deepCopy();
            JsonNode sol = copy.get("solution");
            if (sol != null && sol.isObject() && sol.has("token")) {
                String t = sol.get("token").asText("");
                ((com.fasterxml.jackson.databind.node.ObjectNode) sol).put("token",
                        "<redacted len=" + t.length() + ">");
            } else if (sol != null && sol.isTextual()) {
                String t = sol.asText("");
                if (t.length() > 40) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("solution",
                            "<redacted len=" + t.length() + ">");
                }
            }
            return copy.toString();
        } catch (Exception e) {
            return response.toString();
        }
    }

    private static String taskTypeLabel(String captchaKind) {
        if (captchaKind == null) {
            return "";
        }
        if ("PazlCaptcha".equals(captchaKind)) {
            return "PazlCaptchaTask";
        }
        if (captchaKind.contains("Audio")) {
            return "AudioTask";
        }
        if ("hCaptcha".equalsIgnoreCase(captchaKind)) {
            return "HCaptchaTaskProxyless";
        }
        if (captchaKind.contains("proxy") && !captchaKind.contains("proxyless")) {
            return "YandexSmartCaptchaTask";
        }
        return "YandexSmartCaptchaTaskProxyless";
    }

    private JsonNode postCaptchaJson(String path, Map<String, Object> body, String apiMethod, String taskType,
                                     long taskId, boolean logSend, boolean logReceive) {
        if (logSend) {
            CaptchaVendorLog.vendorJsonSend(normalizedApiBase(), path, apiMethod, taskType, taskId,
                    toLogRequestJson(body));
        }
        JsonNode response = postJsonInternal(apiUrl(path), body);
        if (logReceive) {
            CaptchaVendorLog.vendorJsonReceive(normalizedApiBase(), path, apiMethod, taskType,
                    taskId > 0 ? taskId : response.path("taskId").asLong(0), toLogResponseJson(response));
        }
        return response;
    }

    private void logCaptchaJsonExchange(String path, String apiMethod, String taskType, long taskId,
                                        Map<String, Object> requestBody, JsonNode response) {
        CaptchaVendorLog.vendorJsonSend(normalizedApiBase(), path, apiMethod, taskType, taskId,
                toLogRequestJson(requestBody));
        CaptchaVendorLog.vendorJsonReceive(normalizedApiBase(), path, apiMethod, taskType, taskId,
                toLogResponseJson(response));
    }

    private String toLogRequestJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(sanitizeBodyForLog(body));
        } catch (Exception e) {
            return String.valueOf(body);
        }
    }

    private String toLogResponseJson(JsonNode response) {
        if (response == null) {
            return "";
        }
        String status = response.path("status").asText("");
        if ("ready".equalsIgnoreCase(status)) {
            return redactSmartTokenInJson(response);
        }
        return response.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeBodyForLog(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if ("clientKey".equals(key)) {
                out.put(key, maskClientKey(val == null ? "" : String.valueOf(val)));
            } else if ("task".equals(key) && val instanceof Map) {
                out.put(key, sanitizeTaskForLog((Map<String, Object>) val));
            } else {
                out.put(key, val);
            }
        }
        return out;
    }

    private Map<String, Object> sanitizeTaskForLog(Map<String, Object> task) {
        Map<String, Object> t = new LinkedHashMap<>(task);
        replaceWithLenPlaceholder(t, "image");
        replaceWithLenPlaceholder(t, "body");
        if (t.containsKey("proxyPassword")) {
            t.put("proxyPassword", "***");
        }
        if (t.containsKey("cookies")) {
            String c = String.valueOf(t.get("cookies"));
            if (c.length() > 160) {
                t.put("cookies", c.substring(0, 160) + "…(len=" + c.length() + ")");
            }
        }
        return t;
    }

    private static void replaceWithLenPlaceholder(Map<String, Object> task, String field) {
        if (!task.containsKey(field)) {
            return;
        }
        Object v = task.get(field);
        int len = v == null ? 0 : String.valueOf(v).length();
        task.put(field, "<base64 len=" + len + ">");
    }

    private JsonNode postJsonInternal(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RestClientException("Не удалось разобрать ответ 2Captcha: " + e.getMessage(), e);
        }
    }
}
