package by.gdev.common.service.playwright.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.ScreenshotType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yandex SmartCaptcha Kaleidoscope (слайдер-пазл) → 2Captcha {@link TwoCaptchaClient#solvePazlCaptcha}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class YandexKaleidoscopeSolver {

    private static final int PAZL_WIDTH = 320;
    private static final int PAZL_HEIGHT = 180;
    private static final Pattern ARRAY_IN_TEXT = Pattern.compile("\\[\\s*(?:\\d+\\s*,\\s*){15,}\\d+\\s*\\]");

    private static final String KALEIDOSCOPE_MARKER =
            ".AdvancedCaptcha_kaleidoscope, .AdvancedCaptcha-KaleidoscopeCanvas, #captcha-slider";

    /** Чекбокс «Я не робот» иногда оставляет 1×1 canvas в advanced-frame — не пазл. */
    private static final double MIN_KALEIDOSCOPE_CANVAS_W = 80;
    private static final double MIN_KALEIDOSCOPE_CANVAS_H = 40;
    private static final int MIN_PAZL_IMAGE_BASE64_LEN = 4_000;

    private volatile boolean lastPazlAbortedForAudio;

    private static final String[] SLIDER_ARROW_SELECTORS = {
            ".CaptchaSlider-Button_direction_right",
            ".AdvancedCaptcha-SliderControl-Button_direction_right",
            "button[aria-label*='вправо']",
            "button[aria-label*='Right']",
            "[data-testid='slider-control-forward']",
            ".CaptchaSlider-Button:last-child",
            ".AdvancedCaptcha-SliderControl button:last-of-type"
    };

    private final TwoCaptchaClient twoCaptchaClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<BrowserContext, NetworkCaptureSession> networkCaptures =
            new ConcurrentHashMap<>();

    /** Слушать ответы SmartCaptcha до вызова {@link #solve} (например до клика «Далее» на YouDo). */
    public void beginNetworkCapture(Page page) {
        if (page == null) {
            return;
        }
        BrowserContext ctx = page.context();
        networkCaptures.computeIfAbsent(ctx, c -> {
            NetworkCaptureSession session = new NetworkCaptureSession();
            session.tasks = new CopyOnWriteArrayList<>();
            session.listener = captureTaskFromResponse(session);
            ctx.onResponse(session.listener);
            log.debug("Kaleidoscope: перехват network запущен (context)");
            return session;
        });
    }

    public void endNetworkCapture(Page page) {
        if (page == null) {
            return;
        }
        BrowserContext ctx = page.context();
        NetworkCaptureSession session = networkCaptures.remove(ctx);
        if (session != null) {
            try {
                ctx.offResponse(session.listener);
            } catch (Exception e) {
                log.debug("Kaleidoscope: offResponse: {}", e.getMessage());
            }
            log.debug("Kaleidoscope: перехват network остановлен, task-событий={}", session.tasks.size());
        }
    }

    /** URL поля {@code voice} из ответа {@code /check} (load-captcha-voice или прямой voice?key). */
    public Optional<String> getLastVoiceUrl(Page page) {
        if (page == null) {
            return Optional.empty();
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session == null || session.lastVoiceUrl == null || session.lastVoiceUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(session.lastVoiceUrl);
    }

    public boolean hasCapturedPuzzleTask(Page page) {
        if (page == null) {
            return false;
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session == null || session.tasks == null || session.tasks.isEmpty()) {
            return false;
        }
        return pickValidatedNetworkTask(session.tasks).isPresent();
    }

    /** Сброс task из /check после внешнего smart-token — иначе YouDo снова уходит в Pazl без canvas. */
    public void clearCapturedPuzzleTasks(Page page) {
        if (page == null) {
            return;
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session != null && session.tasks != null) {
            int n = session.tasks.size();
            session.tasks.clear();
            if (n > 0) {
                log.debug("Kaleidoscope: очищено network task-событий: {}", n);
            }
        }
    }

    public boolean isVisible(Page page) {
        return isKaleidoscopePuzzleVisible(page);
    }

    /** Слайдер-пазл (canvas), не чекбокс «Я не робот». */
    public boolean isKaleidoscopePuzzleVisible(Page page) {
        if (page == null) {
            return false;
        }
        try {
            for (Frame f : page.frames()) {
                try {
                    Locator canvas = f.locator(".AdvancedCaptcha-KaleidoscopeCanvas").first();
                    if (canvas.count() > 0 && canvas.isVisible()) {
                        BoundingBoxSafe box = boundingBox(canvas);
                        if (box != null && box.onScreen() && box.isKaleidoscopeSize()) {
                            return true;
                        }
                    }
                } catch (Exception ignored) {
                }
                if (hasKaleidoscopeInFrame(f)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Kaleidoscope: visibility check: {}", e.getMessage());
        }
        return false;
    }

    /** Последний {@link #solve}: 2Captcha вернула Pazl {@code solution=0}, дальше нужен audio. */
    public boolean lastPazlAbortedForAudio() {
        return lastPazlAbortedForAudio;
    }

    /** URL аудио из ответа {@code /check} (то же, что открываешь в браузере). */
    public Optional<String> getLastCapturedVoiceUrl(Page page) {
        if (page == null) {
            return Optional.empty();
        }
        NetworkCaptureSession session = networkCaptures.get(page.context());
        if (session == null || session.lastVoiceUrl == null || session.lastVoiceUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(session.lastVoiceUrl);
    }

    /**
     * Решает калейдоскоп, если виден. Возвращает true, если challenge нет или успешно пройден.
     */
    public boolean solve(Page page) {
        lastPazlAbortedForAudio = false;
        boolean puzzleVisible = isVisible(page);
        boolean hasNetworkTask = hasCapturedPuzzleTask(page);
        if (!puzzleVisible && !hasNetworkTask) {
            return true;
        }
        if (!twoCaptchaClient.isConfigured()) {
            log.warn("Kaleidoscope: 2Captcha не настроен");
            return false;
        }

        NetworkCaptureSession existing = networkCaptures.get(page.context());
        List<String> networkTasks;
        Consumer<Response> extraListener = null;
        if (existing != null) {
            networkTasks = existing.tasks;
        } else {
            NetworkCaptureSession temp = new NetworkCaptureSession();
            temp.tasks = new CopyOnWriteArrayList<>();
            networkTasks = temp.tasks;
            extraListener = captureTaskFromResponse(temp);
            page.context().onResponse(extraListener);
        }
        try {
            for (int round = 1; round <= 3; round++) {
                if (!isVisible(page) && !hasCapturedPuzzleTask(page)) {
                    log.info("Kaleidoscope: challenge снят (round={})", round);
                    return true;
                }
                log.info("Kaleidoscope: попытка {}/3", round);
                Optional<Frame> frame = findKaleidoscopeFrame(page);
                if (frame.isEmpty()) {
                    log.warn("Kaleidoscope: iframe/frame с canvas не найден (networkTasks={})", networkTasks.size());
                    page.waitForTimeout(800);
                    continue;
                }
                Frame captchaFrame = frame.get();
                log.info("Kaleidoscope: frame для solve url={}", truncate(safeFrameUrl(captchaFrame), 100));

                Optional<String> taskJson = extractTaskJson(page, captchaFrame, networkTasks);
                if (taskJson.isEmpty()) {
                    log.warn("Kaleidoscope: массив task не извлечён (frame={}, networkTasks={}) — ждём",
                            truncate(safeFrameUrl(captchaFrame), 90), networkTasks.size());
                    page.waitForTimeout(1200);
                    continue;
                }
                log.info("Kaleidoscope: task для 2Captcha preview={} (источник: network или DOM)",
                        TwoCaptchaClient.summarizeTaskArray(taskJson.get()));

                Optional<String> imageBase64 = captureCanvasPngBase64(captchaFrame, page);
                if (imageBase64.isEmpty()) {
                    log.warn("Kaleidoscope: не удалось снять canvas PNG для PazlCaptchaTask");
                    page.waitForTimeout(800);
                    continue;
                }
                int imageLen = imageBase64.get().length();
                log.info("Kaleidoscope: canvas PNG base64 len={} (ожидаем 320x180 после resize)",
                        imageLen);
                if (imageLen < MIN_PAZL_IMAGE_BASE64_LEN) {
                    log.warn("Kaleidoscope: canvas слишком маленький ({}), вероятно чекбокс — Pazl пропускаем",
                            imageLen);
                    return false;
                }

                int sliderMax = readSliderMax(captchaFrame);
                log.info("Kaleidoscope: → 2Captcha solvePazlCaptcha sliderMax={}", sliderMax);
                TwoCaptchaClient.PazlCaptchaInvokeResult pazlResult = twoCaptchaClient.solvePazlCaptcha(
                        imageBase64.get(), taskJson.get(), sliderMax, PAZL_WIDTH);
                if (pazlResult.audioFallback()) {
                    lastPazlAbortedForAudio = true;
                    log.warn("Kaleidoscope: Pazl solution=0 — прекращаем Pazl, переключаемся на audio");
                    break;
                }
                Optional<TwoCaptchaClient.PazlSolution> solution = pazlResult.solution();
                if (solution.isEmpty() || !solution.get().hasClicks() || solution.get().clicks() <= 0) {
                    log.warn("Kaleidoscope: 2Captcha не вернула положительное число кликов (sliderMax={})",
                            sliderMax);
                    if (clickRetryIfPresent(captchaFrame)) {
                        page.waitForTimeout(1500);
                    }
                    continue;
                }
                TwoCaptchaClient.PazlSolution pazl = solution.get();
                int clicks = pazl.clicks();
                long taskId = pazl.twoCaptchaTaskId();
                if (!applySliderClicks(captchaFrame, page, clicks)) {
                    log.warn("Kaleidoscope: не удалось кликнуть по стрелке слайдера");
                    continue;
                }
                page.waitForTimeout(2000);
                if (!isVisible(page)) {
                    log.info("Kaleidoscope: пройдено после {} кликов (taskId={})", clicks, taskId);
                    twoCaptchaClient.reportCorrect(taskId);
                    return true;
                }
                if (isRetryLabelVisible(captchaFrame)) {
                    log.warn("Kaleidoscope: retry-label после {} кликов — reportIncorrect taskId={}",
                            clicks, taskId);
                    twoCaptchaClient.reportIncorrect(taskId);
                    clickRetryIfPresent(captchaFrame);
                    page.waitForTimeout(1500);
                } else {
                    log.warn("Kaleidoscope: капча осталась после {} кликов — reportIncorrect taskId={}",
                            clicks, taskId);
                    twoCaptchaClient.reportIncorrect(taskId);
                }
            }
            if (lastPazlAbortedForAudio) {
                return false;
            }
            boolean gone = !isVisible(page);
            if (!gone) {
                log.warn("Kaleidoscope: не пройден за 3 раунда");
            }
            if (!gone && hasCapturedPuzzleTask(page) && findKaleidoscopeFrame(page).isEmpty()) {
                log.warn("Kaleidoscope: есть network task, но canvas/iframe пазла нет — считаем неуспехом");
                return false;
            }
            return gone;
        } finally {
            if (extraListener != null) {
                page.context().offResponse(extraListener);
            }
        }
    }

    private static String safeFrameUrl(Frame frame) {
        try {
            return frame != null ? frame.url() : "null";
        } catch (Exception e) {
            return "?";
        }
    }

    private static final class NetworkCaptureSession {
        CopyOnWriteArrayList<String> tasks;
        Consumer<Response> listener;
        volatile String lastVoiceUrl;
    }

    private boolean hasKaleidoscopeInFrame(Frame frame) {
        try {
            Locator loc = frame.locator(KALEIDOSCOPE_MARKER);
            int n = loc.count();
            for (int i = 0; i < Math.min(n, 4); i++) {
                Locator one = loc.nth(i);
                if (one.isVisible()) {
                    BoundingBoxSafe box = boundingBox(one);
                    if (box != null && box.onScreen() && box.isKaleidoscopeSize()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private Optional<Frame> findKaleidoscopeFrame(Page page) {
        Frame advancedUrlFrame = null;
        Frame withCanvas = null;
        Frame withSlider = null;
        for (Frame f : page.frames()) {
            String url = safeFrameUrl(f);
            if (url.contains("smartcaptcha.cloud.yandex.ru") && url.contains("/advanced")) {
                advancedUrlFrame = f;
            }
            if (!hasKaleidoscopeInFrame(f)) {
                continue;
            }
            try {
                if (f.locator(".AdvancedCaptcha-KaleidoscopeCanvas, canvas").count() > 0) {
                    withCanvas = f;
                }
                if (f.locator("#captcha-slider, [data-testid='slider']").count() > 0) {
                    withSlider = f;
                }
            } catch (Exception ignored) {
            }
        }
        if (withCanvas != null) {
            return Optional.of(withCanvas);
        }
        if (advancedUrlFrame != null && hasKaleidoscopeInFrame(advancedUrlFrame)) {
            return Optional.of(advancedUrlFrame);
        }
        if (withSlider != null) {
            return Optional.of(withSlider);
        }
        return Optional.empty();
    }

    private Consumer<Response> captureTaskFromResponse(NetworkCaptureSession session) {
        return response -> {
            try {
                String url = response.url();
                if (url == null || (!url.contains("smartcaptcha") && !url.contains("captcha.yandex"))) {
                    return;
                }
                if (response.status() < 200 || response.status() >= 300) {
                    return;
                }
                String body = response.text();
                if (body == null || body.isBlank()) {
                    return;
                }
                extractVoiceUrlFromCheckBody(body).ifPresent(voice -> {
                    session.lastVoiceUrl = voice;
                    log.info("Kaleidoscope: voice из /check {}", truncate(voice, 120));
                });
                findTaskArrayInJson(body).ifPresentOrElse(task -> {
                    session.tasks.add(task);
                    log.info("Kaleidoscope: task из network {} len={}", truncate(url, 80),
                            TwoCaptchaClient.summarizeTaskArray(task));
                }, () -> {
                    if (url.contains("/advanced") || url.contains("kaleidoscope") || url.contains("puzzle")) {
                        log.debug("Kaleidoscope: smartcaptcha response без task url={} bodyPreview={}",
                                truncate(url, 70), truncate(body.replaceAll("\\s+", " "), 120));
                    }
                });
            } catch (Exception e) {
                log.debug("Kaleidoscope: network capture: {}", e.getMessage());
            }
        };
    }

    private Optional<String> extractTaskJson(Page page, Frame frame, List<String> networkTasks) {
        Optional<String> fromNetwork = pickValidatedNetworkTask(networkTasks);
        if (fromNetwork.isPresent()) {
            return fromNetwork;
        }
        for (Frame f : page.frames()) {
            String url = safeFrameUrl(f);
            if (url.contains("smartcaptcha") || url.contains("captcha.yandex")) {
                Optional<String> fromFrame = extractTaskJsonFromFrameDom(f);
                if (fromFrame.isPresent()) {
                    return fromFrame;
                }
            }
        }
        return extractTaskJsonFromFrameDom(frame);
    }

    private Optional<String> extractTaskJsonFromFrameDom(Frame frame) {
        try {
            Object raw = frame.evaluate(
                    """
                            () => {
                              const isPuzzleArray = (arr) => {
                                if (!Array.isArray(arr) || arr.length < 15 || arr.length > 100) return false;
                                return arr.every((n) => Number.isInteger(n) && n >= 0 && n <= 20);
                              };
                              const pickLongestArray = (text) => {
                                if (!text) return null;
                                const re = /\\[(?:\\s*\\d+\\s*,\\s*){14,}\\s*\\d+\\s*\\]/g;
                                let best = null;
                                let m;
                                while ((m = re.exec(text)) !== null) {
                                  if (!best || m[0].length > best.length) best = m[0];
                                }
                                return best;
                              };
                              const canvas = document.querySelector('.AdvancedCaptcha-KaleidoscopeCanvas, canvas');
                              if (canvas) {
                                for (const attr of ['data-task', 'data-puzzle', 'data-state', 'data-steps']) {
                                  const v = canvas.getAttribute(attr);
                                  if (v && v.includes('[')) return v;
                                }
                                const parent = canvas.closest('.AdvancedCaptcha_kaleidoscope, .AdvancedCaptcha');
                                if (parent) {
                                  for (const attr of ['data-task', 'data-puzzle', 'data-state']) {
                                    const v = parent.getAttribute(attr);
                                    if (v && v.includes('[')) return v;
                                  }
                                }
                              }
                              const htmlArr = pickLongestArray(document.documentElement.innerHTML);
                              if (htmlArr) return htmlArr;
                              const scripts = document.querySelectorAll('script');
                              for (const s of scripts) {
                                const t = s.textContent || '';
                                const m = pickLongestArray(t);
                                if (m) return m;
                              }
                              const keys = Object.keys(window);
                              for (const k of keys) {
                                try {
                                  const v = window[k];
                                  if (isPuzzleArray(v)) return JSON.stringify(v);
                                  if (v && typeof v === 'object') {
                                    for (const sub of ['task', 'puzzle', 'steps', 'permutation', 'order', 'cells']) {
                                      if (isPuzzleArray(v[sub])) return JSON.stringify(v[sub]);
                                    }
                                  }
                                } catch (e) { /* ignore */ }
                              }
                              return null;
                            }
                            """);
            if (raw instanceof String s && !s.isBlank()) {
                return normalizeTaskJson(s);
            }
        } catch (Exception e) {
            log.warn("Kaleidoscope: DOM extract task: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> extractVoiceUrlFromCheckBody(String body) {
        if (body == null || body.isBlank() || !body.contains("voice")) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode voice = root.path("captcha").path("voice");
            if (voice.isTextual()) {
                String v = voice.asText().trim();
                if (!v.isEmpty()) {
                    return Optional.of(v);
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private Optional<String> findTaskArrayInJson(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            Optional<JsonNode> arr = findPuzzleArrayNode(root);
            if (arr.isPresent()) {
                return Optional.of(arr.get().toString());
            }
        } catch (Exception ignored) {
        }
        Matcher m = ARRAY_IN_TEXT.matcher(body);
        while (m.find()) {
            Optional<String> candidate = normalizeTaskJson(m.group());
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    private Optional<String> pickValidatedNetworkTask(List<String> networkTasks) {
        if (networkTasks == null || networkTasks.isEmpty()) {
            return Optional.empty();
        }
        for (int i = networkTasks.size() - 1; i >= 0; i--) {
            Optional<String> task = normalizeTaskJson(networkTasks.get(i));
            if (task.isPresent()) {
                return task;
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findPuzzleArrayNode(JsonNode node) {
        if (node == null) {
            return Optional.empty();
        }
        if (node.isArray() && isPuzzleArray(node)) {
            return Optional.of(node);
        }
        if (node.isObject()) {
            for (String field : List.of("task", "puzzle", "steps", "permutation", "order", "cells", "data")) {
                JsonNode child = node.get(field);
                if (child != null && child.isArray() && isPuzzleArray(child)) {
                    return Optional.of(child);
                }
            }
            for (JsonNode child : node) {
                Optional<JsonNode> nested = findPuzzleArrayNode(child);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isPuzzleArray(JsonNode arr) {
        if (!arr.isArray() || arr.size() < 15 || arr.size() > 100) {
            return false;
        }
        for (JsonNode n : arr) {
            if (!n.isInt() && !n.isIntegralNumber()) {
                return false;
            }
            int v = n.asInt();
            if (v < 0 || v > 20) {
                return false;
            }
        }
        return true;
    }

    private static Optional<String> normalizeTaskJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"");
        }
        try {
            JsonNode n = new ObjectMapper().readTree(s);
            if (n.isArray() && isPuzzleArray(n)) {
                return Optional.of(n.toString());
            }
            if (n.isTextual()) {
                return normalizeTaskJson(n.asText());
            }
        } catch (Exception e) {
            Matcher m = ARRAY_IN_TEXT.matcher(s);
            while (m.find()) {
                Optional<String> inner = normalizeTaskJson(m.group());
                if (inner.isPresent()) {
                    return inner;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> captureCanvasPngBase64(Frame frame, Page page) {
        String[] canvasSelectors = {
                ".AdvancedCaptcha-KaleidoscopeCanvas",
                "canvas.AdvancedCaptcha-Canvas",
                "canvas"
        };
        for (String sel : canvasSelectors) {
            try {
                Locator canvas = frame.locator(sel).first();
                if (canvas.count() == 0) {
                    continue;
                }
                canvas.waitFor(new Locator.WaitForOptions().setTimeout(8_000));
                byte[] png = canvas.screenshot(new Locator.ScreenshotOptions().setType(ScreenshotType.PNG));
                byte[] resized = resizePngTo320x180(png);
                log.info("Kaleidoscope: canvas screenshot selector={}", sel);
                return Optional.of(Base64.getEncoder().encodeToString(resized));
            } catch (Exception e) {
                log.debug("Kaleidoscope: canvas {}: {}", sel, e.getMessage());
            }
        }
        log.warn("Kaleidoscope: canvas не найден во frame {}", truncate(safeFrameUrl(frame), 80));
        return Optional.empty();
    }

    static byte[] resizePngTo320x180(byte[] png) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        if (src == null) {
            throw new IllegalStateException("PNG decode failed");
        }
        if (src.getWidth() == PAZL_WIDTH && src.getHeight() == PAZL_HEIGHT) {
            return png;
        }
        BufferedImage dst = new BufferedImage(PAZL_WIDTH, PAZL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src.getScaledInstance(PAZL_WIDTH, PAZL_HEIGHT, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", out);
        return out.toByteArray();
    }

    private int readSliderMax(Frame frame) {
        try {
            Locator slider = frame.locator("#captcha-slider, [data-testid='slider']").first();
            if (slider.count() == 0) {
                return 28;
            }
            String max = slider.getAttribute("aria-valuemax");
            if (max != null && !max.isBlank()) {
                int v = Integer.parseInt(max.trim());
                if (v > 0 && v <= 64) {
                    return v;
                }
            }
        } catch (Exception e) {
            log.debug("Kaleidoscope: aria-valuemax: {}", e.getMessage());
        }
        return 28;
    }

    private boolean applySliderClicks(Frame frame, Page page, int clicks) {
        if (clicks <= 0) {
            return false;
        }
        Locator arrow = null;
        for (String sel : SLIDER_ARROW_SELECTORS) {
            try {
                Locator loc = frame.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    arrow = loc;
                    log.info("Kaleidoscope: стрелка слайдера selector={}", sel);
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (arrow == null) {
            log.warn("Kaleidoscope: стрелка слайдера не найдена — пробуем клавиши на #captcha-slider");
            return applySliderViaKeyboard(frame, page, clicks);
        }
        try {
            for (int i = 0; i < clicks; i++) {
                arrow.click(new Locator.ClickOptions().setTimeout(5000));
                page.waitForTimeout(120 + (i % 3) * 40);
            }
            return true;
        } catch (Exception e) {
            log.warn("Kaleidoscope: клики по стрелке: {}", e.getMessage());
            return false;
        }
    }

    private boolean applySliderViaKeyboard(Frame frame, Page page, int clicks) {
        try {
            Locator slider = frame.locator("#captcha-slider").first();
            if (slider.count() == 0) {
                return false;
            }
            slider.focus();
            for (int i = 0; i < clicks; i++) {
                slider.press("ArrowRight");
                page.waitForTimeout(100);
            }
            return true;
        } catch (Exception e) {
            log.warn("Kaleidoscope: ArrowRight на слайдере: {}", e.getMessage());
            return false;
        }
    }

    private boolean isRetryLabelVisible(Frame frame) {
        try {
            Locator retry = frame.locator("[data-testid='retry-label'], text=Попробуйте ещё раз");
            return retry.count() > 0 && retry.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clickRetryIfPresent(Frame frame) {
        try {
            Locator retry = frame.locator(
                    "[data-testid='retry-label'], button:has-text('Попробуйте'), .AdvancedCaptcha-RetryButton");
            if (retry.count() > 0) {
                retry.first().click(new Locator.ClickOptions().setTimeout(5000));
                return true;
            }
        } catch (Exception e) {
            log.debug("Kaleidoscope: retry click: {}", e.getMessage());
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record BoundingBoxSafe(double x, double y, double width, double height) {
        boolean onScreen() {
            return width > 2 && height > 2 && x > -500 && y > -500;
        }

        boolean isKaleidoscopeSize() {
            return width >= MIN_KALEIDOSCOPE_CANVAS_W && height >= MIN_KALEIDOSCOPE_CANVAS_H;
        }
    }

    private static BoundingBoxSafe boundingBox(Locator locator) {
        try {
            var box = locator.boundingBox();
            if (box == null) {
                return null;
            }
            return new BoundingBoxSafe(box.x, box.y, box.width, box.height);
        } catch (Exception e) {
            return null;
        }
    }
}
