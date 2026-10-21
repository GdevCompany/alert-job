package by.gdev.common.service.playwright.captcha;

import by.gdev.common.service.playwright.debug.PlaywrightCdpDebugSink;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Yandex SmartCaptcha audio challenge → 2Captcha {@link TwoCaptchaClient#solveAudioCaptcha}.
 * Аудио: voice URL в onResponse, скачивание только на потоке solve (не request в onResponse — deadlock).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class YandexSmartCaptchaAudioSolver {

    /** Маркер в логе: убедиться, что notification подхватил свежий common-alert-job после Stop→Run. */
    private static final String SOLVER_BUILD = "2026-03-21-no-kaleido-seed";

    private static final Pattern MP3_URL = Pattern.compile("\\.mp3([?#]|$)", Pattern.CASE_INSENSITIVE);

    private static final String[] ADVANCED_IFRAME_SELECTORS = {
            "iframe[data-testid='advanced-iframe']",
            "iframe[src*='smartcaptcha'][src*='advanced']",
    };

    /** Кнопка из DOM виджета: «Изменить тип задания». */
    private static final String[] CHALLENGE_TYPE_SELECTORS = {
            "button[data-testid='challenge-type'][aria-label='Изменить тип задания']",
            "button[data-testid='challenge-type'][aria-label*='Изменить тип']",
            "button.ActionButtons-ActionButton[data-testid='challenge-type']",
            "button[data-testid='challenge-type']",
    };

    private static final int CHALLENGE_TYPE_CLICK_TIMEOUT_MS = 2_500;

    private static final String[] PLAY_SELECTORS = {
            "button[data-testid='play'].AdvancedCaptcha_audio-button",
            "button[data-testid='play'][aria-label*='Прослушать']",
            "[data-testid='play']",
            "button[aria-label*='Прослушать']",
            "button[aria-label*='Воспроизвести']",
            "button[aria-label*='Play']",
            ".AdvancedCaptcha_audio-button",
            ".AdvancedCaptcha-Button_type_play",
            ".AdvancedCaptcha_audio button"
    };

    /** Поле «Например, 1234» в аудио-режиме SmartCaptcha. */
    private static final String[] ANSWER_INPUT_SELECTORS = {
            "input.Textinput-Control[name='rep'][inputmode='numeric']",
            "input[name='rep'][inputmode='numeric'].Textinput-Control",
            "input[name='rep'][inputmode='numeric']",
            "input[name='rep'].Textinput-Control",
            "input[name='rep']",
            "input.Textinput-Control[inputmode='numeric']",
    };

    private static final String[] SUBMIT_SELECTORS = {
            "button[data-testid='submit'].AdvancedCaptcha_submit",
            "button[data-testid='submit']",
            "button[type='submit'][data-testid='submit']",
            "button[type='submit']",
            ".AdvancedCaptcha_submit",
            ".AdvancedCaptcha-Button_action_submit",
            "button.AdvancedCaptcha-Button[data-testid='submit']"
    };

    /** Видимый challenge (калейдоскоп canvas / слайдер / аудио-форма). */
    private static final String ACTIVE_CHALLENGE_LOCATOR =
            ".AdvancedCaptcha-KaleidoscopeCanvas:visible, #captcha-slider:visible, "
                    + "input[name='rep']:visible, button[data-testid='play']:visible";

    private final TwoCaptchaClient twoCaptchaClient;

    public boolean isAudioChallengeVisible(Page page) {
        if (page == null) {
            return false;
        }
        try {
            for (Frame f : page.frames()) {
                if (!isSmartCaptchaFrame(f)) {
                    continue;
                }
                if (isAudioFormVisibleInFrame(f)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: visibility check: {}", e.getMessage());
        }
        return false;
    }

    /** Аудио-режим: «Прослушайте и введите четыре цифры» + play (класс kaleidoscope на корне может остаться). */
    private static boolean isAudioFormVisibleInFrame(Frame frame) {
        try {
            Locator rep = frame.locator(
                    "input.Textinput-Control[name='rep'], input[name='rep'][inputmode='numeric'], input[name='rep']");
            if (rep.count() > 0 && isVisibleQuick(rep.first())) {
                return true;
            }
            Locator play = frame.locator("button[data-testid='play']");
            return play.count() > 0 && isVisibleQuick(play.first());
        } catch (Exception e) {
            return false;
        }
    }

    /** Калейдоскоп/слайдер — seed voice из /check не тот трек, нужен клик challenge-type и новый voice?key=. */
    private static boolean isKaleidoscopeChallengeInFrame(Frame frame) {
        try {
            Locator canvas = frame.locator(
                    ".AdvancedCaptcha-KaleidoscopeCanvas, canvas.AdvancedCaptcha-KaleidoscopeCanvas");
            if (canvas.count() > 0 && isVisibleQuick(canvas.first())) {
                return true;
            }
            Locator slider = frame.locator("#captcha-slider");
            return slider.count() > 0 && isVisibleQuick(slider.first());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDedicatedAudioChallenge(Frame frame) {
        return isAudioFormVisibleInFrame(frame) && !isKaleidoscopeChallengeInFrame(frame);
    }

    private static boolean isVisibleQuick(Locator locator) {
        try {
            return locator.isVisible(new Locator.IsVisibleOptions().setTimeout(UI_PROBE_TIMEOUT_MS));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Переключиться на аудио (если нужно), скачать mp3, распознать через 2Captcha, ввести ответ.
     */
    public boolean solve(Page page) {
        return solve(page, List.of());
    }

    /** Короткий таймаут для проверок UI в iframe (дефолт Playwright ~30s — зависает на калейдоскопе). */
    private static final int UI_PROBE_TIMEOUT_MS = 500;

    /**
     * @param seedVoiceUrls например {@code voice} из ответа {@code /check} до перехвата сети
     */
    public boolean solve(Page page, List<String> seedVoiceUrls) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (!twoCaptchaClient.isConfigured()) {
            log.warn("SmartCaptcha audio: 2Captcha не настроен");
            return false;
        }

        List<String> capturedUrls = new CopyOnWriteArrayList<>();
        if (seedVoiceUrls != null) {
            for (String u : seedVoiceUrls) {
                if (u != null && !u.isBlank()) {
                    capturedUrls.add(u);
                }
            }
        }
        AtomicReference<byte[]> mp3FromIntercept = new AtomicReference<>();
        Consumer<Response> listener = captureAudioUrls(page, capturedUrls, mp3FromIntercept);
        page.context().onResponse(listener);
        try {
            log.info("SmartCaptcha audio: solver build {}", SOLVER_BUILD);
            for (int round = 1; round <= 2; round++) {
                log.info("SmartCaptcha audio: раунд {}/2", round);
                if (round > 1) {
                    capturedUrls.clear();
                    mp3FromIntercept.set(null);
                }
                Optional<Frame> frameOpt = findAdvancedFrame(page);
                if (frameOpt.isEmpty()) {
                    log.warn("SmartCaptcha audio: advanced frame не найден (round={})", round);
                    page.waitForTimeout(800);
                    continue;
                }
                Frame frame = frameOpt.get();
                log.info("SmartCaptcha audio: frame url={}", truncate(safeUrl(frame), 100));
                if (Boolean.parseBoolean(System.getenv().getOrDefault("PLAYWRIGHT_DEBUG_MARKUP", "false"))) {
                    PlaywrightCdpDebugSink.publishSmartCaptcha(page, frame, "audio-round-" + round + "-start");
                }
                expandVoiceUrlsInSink(capturedUrls);
                boolean kaleidoscope = isKaleidoscopeChallengeInFrame(frame);
                if (kaleidoscope && !capturedUrls.isEmpty()) {
                    log.info(
                            "SmartCaptcha audio: калейдоскоп — очищаем {} seed URL (нужен challenge-type)",
                            capturedUrls.size());
                    capturedUrls.clear();
                    mp3FromIntercept.set(null);
                }
                log.info("SmartCaptcha audio: voice urls (seed): {}", voiceUrlsPreview(capturedUrls));

                byte[] mp3 = null;
                boolean dedicatedAudio = isDedicatedAudioChallenge(frame);
                if (!kaleidoscope && dedicatedAudio) {
                    mp3 = tryDownloadMp3(page, frame, capturedUrls, mp3FromIntercept);
                    if (mp3 != null && mp3.length >= 200) {
                        log.info("SmartCaptcha audio: mp3 в аудио-режиме (без калейдоскопа), {} bytes",
                                mp3.length);
                    }
                }
                if (mp3 == null || mp3.length < 200) {
                    if (kaleidoscope) {
                        log.info("SmartCaptcha audio: калейдоскоп — seed не используем, жмём challenge-type");
                    }
                    log.info("SmartCaptcha audio: [1/3] переключение на аудио (dedicatedAudio={})",
                            dedicatedAudio);
                    if (!dedicatedAudio) {
                        if (!clickChallengeTypeButton(frame, page)) {
                            log.warn("SmartCaptcha audio: challenge-type не нажата (round={})", round);
                        }
                        briefPauseForVoiceUrl(page, capturedUrls, 2_000);
                    }
                    log.info("SmartCaptcha audio: [2/3] скачивание mp3 по voice URL");
                    mp3 = resolveMp3WithRetries(page, frame, capturedUrls, mp3FromIntercept, 3);
                }
                if (mp3 == null || mp3.length < 200) {
                    log.error("SmartCaptcha audio: mp3 не скачан — 2Captcha не вызываем (round={})",
                            round);
                    continue;
                }
                if (mp3.length > 1_000_000) {
                    log.warn("SmartCaptcha audio: файл > 1 MB, пропуск");
                    continue;
                }

                log.info("SmartCaptcha audio: [3/3] отправка в 2Captcha AudioTask, mp3={} bytes",
                        mp3.length);
                Optional<TwoCaptchaClient.AudioResult> audioResult =
                        twoCaptchaClient.solveAudioCaptcha(mp3, "ru");
                if (audioResult.isEmpty()) {
                    log.warn("SmartCaptcha audio: 2Captcha не вернула текст (round={})", round);
                    continue;
                }
                String answer = normalizeAnswer(audioResult.get().text());
                if (answer.isBlank()) {
                    log.warn("SmartCaptcha audio: пустой ответ после normalize, raw='{}'",
                            truncate(audioResult.get().text(), 40));
                    continue;
                }
                if (answer.length() != 4) {
                    log.warn("SmartCaptcha audio: нужно ровно 4 цифры (len={}, raw='{}') — reportIncorrect, повтор",
                            answer.length(), truncate(audioResult.get().text(), 60));
                    twoCaptchaClient.reportIncorrect(audioResult.get().taskId());
                    continue;
                }
                log.info("SmartCaptcha audio: ответ 2Captcha → «{}» (digits len={})",
                        maskAnswer(answer), answer.length());

                if (!isAudioFormVisibleInFrame(frame)) {
                    log.warn("SmartCaptcha audio: после 2Captcha нет поля rep — ждём UI");
                    page.waitForTimeout(1_500);
                }
                if (!submitAnswer(frame, page, answer)) {
                    log.warn("SmartCaptcha audio: не удалось ввести/отправить ответ (rep visible={})",
                            isAudioFormVisibleInFrame(frame));
                    continue;
                }
                page.waitForTimeout(2500);
                if (isChallengeGone(page)) {
                    log.info("SmartCaptcha audio: challenge снят после ответа «{}»", maskAnswer(answer));
                    return true;
                }
                log.warn("SmartCaptcha audio: ответ отправлен, challenge остался (round={})", round);
            }
            return isChallengeGone(page);
        } finally {
            try {
                page.context().offResponse(listener);
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: offResponse: {}", e.getMessage());
            }
        }
    }

    private byte[] tryDownloadMp3(Page page, Frame frame, List<String> urlSink,
            AtomicReference<byte[]> mp3FromIntercept) {
        return takeInterceptedOrDownload(page, frame, urlSink, mp3FromIntercept);
    }

    private byte[] resolveMp3WithRetries(Page page, Frame frame, List<String> urlSink,
            AtomicReference<byte[]> mp3FromIntercept, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            byte[] mp3 = takeInterceptedOrDownload(page, frame, urlSink, mp3FromIntercept);
            if (mp3 != null && mp3.length >= 200) {
                return mp3;
            }
            log.warn("SmartCaptcha audio: mp3 попытка {}/{} — нет файла, urls={}",
                    attempt, maxAttempts, voiceUrlsPreview(urlSink));
            if (attempt < maxAttempts) {
                tryClickPlayQuick(frame, page);
                briefPauseForVoiceUrl(page, urlSink, 1_200);
            }
        }
        return null;
    }

    private byte[] takeInterceptedOrDownload(Page page, Frame frame, List<String> urlSink,
            AtomicReference<byte[]> mp3FromIntercept) {
        expandVoiceUrlsInSink(urlSink);
        byte[] cached = mp3FromIntercept.get();
        if (cached != null && cached.length >= 200) {
            log.info("SmartCaptcha audio: mp3 уже в буфере, {} bytes", cached.length);
            return cached;
        }
        byte[] downloaded = downloadVoiceByUrl(page, frame, urlSink);
        if (downloaded != null && downloaded.length >= 200) {
            mp3FromIntercept.compareAndSet(null, downloaded);
        }
        return downloaded;
    }

    /** Скачать mp3 по ссылке voice из /check или перехвата (без циклов ожидания UI). */
    private byte[] downloadVoiceByUrl(Page page, Frame frame, List<String> urlSink) {
        expandVoiceUrlsInSink(urlSink);
        if (pickBestVoiceUrl(urlSink).isEmpty()) {
            log.warn("SmartCaptcha audio: нет voice URL для скачивания");
            return null;
        }
        for (String url : voiceUrlsOrdered(urlSink)) {
            log.info("SmartCaptcha audio: скачивание по ссылке {}", truncate(url, 120));
            Optional<byte[]> bytes = downloadVoiceBytes(page, frame, url);
            if (bytes.isPresent() && bytes.get().length >= 200) {
                log.info("SmartCaptcha audio: mp3 скачан, {} bytes", bytes.get().length);
                return bytes.get();
            }
        }
        log.warn("SmartCaptcha audio: ни одна ссылка не дала mp3, urls={}", voiceUrlsPreview(urlSink));
        return null;
    }

    /** Добавить распакованный voice?key= из load-captcha-voice?base64. */
    private static void expandVoiceUrlsInSink(List<String> urlSink) {
        if (urlSink == null || urlSink.isEmpty()) {
            return;
        }
        for (String url : new ArrayList<>(urlSink)) {
            if (url == null || url.isBlank()) {
                continue;
            }
            String inner = unwrapLoadCaptchaVoiceUrl(url);
            if (inner != null && !inner.equals(url) && !urlSink.contains(inner)) {
                urlSink.add(inner);
                log.info("SmartCaptcha audio: voice URL распакован → {}", truncate(inner, 100));
            }
        }
    }

    private static String voiceUrlsPreview(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (String u : urls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            if (n > 0) {
                sb.append(", ");
            }
            sb.append(truncate(u, 70));
            n++;
            if (n >= 4) {
                sb.append(", …");
                break;
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /** Предпочитаем прямой voice?key=, не load-captcha-voice-обёртку. */
    private static java.util.List<String> voiceUrlsOrdered(List<String> urlSink) {
        java.util.List<String> direct = new ArrayList<>();
        java.util.List<String> other = new ArrayList<>();
        if (urlSink != null) {
            for (String url : urlSink) {
                if (url == null || url.isBlank() || isIntroOrStaticNoise(url.toLowerCase())) {
                    continue;
                }
                String resolved = unwrapLoadCaptchaVoiceUrl(url);
                if (resolved != null && !resolved.equals(url) && !urlSink.contains(resolved)) {
                    urlSink.add(resolved);
                }
                String use = resolved != null ? resolved : url;
                String lower = use.toLowerCase();
                if (lower.contains("voice?") || lower.contains("/voice?")) {
                    direct.add(use);
                } else if (lower.contains("/voice") || lower.contains("load-captcha-voice")) {
                    other.add(use);
                }
            }
        }
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        ordered.addAll(direct);
        ordered.addAll(other);
        return new ArrayList<>(ordered);
    }

    /** {@code load-captcha-voice?&lt;base64(real voice url)&gt;} из ответа /check. */
    private static String unwrapLoadCaptchaVoiceUrl(String url) {
        if (url == null || !url.toLowerCase().contains("load-captcha-voice")) {
            return url;
        }
        int q = url.indexOf('?');
        if (q < 0 || q >= url.length() - 1) {
            return url;
        }
        String payload = url.substring(q + 1);
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            String inner = new String(decoded, StandardCharsets.UTF_8).trim();
            if (inner.startsWith("http") && inner.toLowerCase().contains("voice")) {
                return inner;
            }
        } catch (Exception ignored) {
            try {
                byte[] decoded = Base64.getDecoder().decode(payload);
                String inner = new String(decoded, StandardCharsets.UTF_8).trim();
                if (inner.startsWith("http") && inner.toLowerCase().contains("voice")) {
                    return inner;
                }
            } catch (Exception ignored2) {
                // keep original url
            }
        }
        return url;
    }

    private static Optional<String> pickBestVoiceUrl(List<String> urls) {
        java.util.List<String> ordered = voiceUrlsOrdered(urls);
        return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.get(0));
    }

    private Optional<byte[]> downloadVoiceBytes(Page page, Frame frame, String url) {
        java.util.LinkedHashSet<String> tryUrls = new java.util.LinkedHashSet<>();
        String unwrapped = unwrapLoadCaptchaVoiceUrl(url);
        if (unwrapped != null && unwrapped.contains("voice")) {
            tryUrls.add(unwrapped);
        }
        tryUrls.add(url);
        for (String candidate : tryUrls) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (candidate.contains("voice?key=")) {
                Optional<byte[]> viaBrowser = fetchVoiceInBrowser(frame, candidate);
                if (viaBrowser.isPresent()) {
                    return viaBrowser;
                }
            }
            Optional<byte[]> viaApi = fetchUrl(page, frame, candidate);
            if (viaApi.isPresent()) {
                return viaApi;
            }
        }
        return Optional.empty();
    }

    /**
     * fetch() внутри iframe advanced — cookies сессии SmartCaptcha (иначе voice 403/пусто).
     */
    private Optional<byte[]> fetchVoiceInBrowser(Frame frame, String url) {
        try {
            Object raw = frame.evaluate(
                    """
                            async (voiceUrl) => {
                              const ctrl = new AbortController();
                              const t = setTimeout(() => ctrl.abort(), 12000);
                              let r;
                              try {
                                r = await fetch(voiceUrl, {
                                  credentials: 'include', cache: 'no-store', signal: ctrl.signal
                                });
                              } finally {
                                clearTimeout(t);
                              }
                              if (!r.ok) return { ok: false, status: r.status, len: 0 };
                              const buf = await r.arrayBuffer();
                              const u8 = new Uint8Array(buf);
                              const out = [];
                              for (let i = 0; i < u8.length; i++) out.push(u8[i]);
                              return { ok: true, status: r.status, len: u8.length, bytes: out };
                            }
                            """,
                    url);
            if (!(raw instanceof java.util.Map<?, ?> map)) {
                return Optional.empty();
            }
            Object ok = map.get("ok");
            Object status = map.get("status");
            Object len = map.get("len");
            if (!Boolean.TRUE.equals(ok)) {
                log.warn("SmartCaptcha audio: fetch в iframe status={} url={}",
                        status, truncate(url, 90));
                return Optional.empty();
            }
            Object bytesObj = map.get("bytes");
            if (!(bytesObj instanceof java.util.List<?> list) || list.isEmpty()) {
                log.warn("SmartCaptcha audio: fetch в iframe пустой body len={} url={}",
                        len, truncate(url, 90));
                return Optional.empty();
            }
            byte[] body = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object v = list.get(i);
                body[i] = (byte) (v instanceof Number n ? n.intValue() : 0);
            }
            if (body.length < 200) {
                log.warn("SmartCaptcha audio: fetch в iframe слишком маленький {} bytes", body.length);
                return Optional.empty();
            }
            log.info("SmartCaptcha audio: fetch в iframe ok, {} bytes, status={}", body.length, status);
            return Optional.of(body);
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: fetch в iframe {}: {}", truncate(url, 80), e.getMessage());
            return Optional.empty();
        }
    }

    private static String maskAnswer(String answer) {
        if (answer == null || answer.length() <= 2) {
            return "***";
        }
        return answer.charAt(0) + "***" + answer.charAt(answer.length() - 1);
    }

    private static final java.util.Map<String, String> RU_NUM_WORD = java.util.Map.ofEntries(
            java.util.Map.entry("ноль", "0"),
            java.util.Map.entry("один", "1"),
            java.util.Map.entry("одна", "1"),
            java.util.Map.entry("два", "2"),
            java.util.Map.entry("две", "2"),
            java.util.Map.entry("три", "3"),
            java.util.Map.entry("четыре", "4"),
            java.util.Map.entry("пять", "5"),
            java.util.Map.entry("шесть", "6"),
            java.util.Map.entry("семь", "7"),
            java.util.Map.entry("восемь", "8"),
            java.util.Map.entry("девять", "9"));

    /** 4 цифры: из текста 2Captcha (цифры или слова «три четыре…», мусорные слова пропускаем). */
    private static String normalizeAnswer(String raw) {
        if (raw == null) {
            return "";
        }
        String digitsOnly = raw.replaceAll("\\D", "");
        if (digitsOnly.length() >= 4) {
            return digitsOnly.substring(0, 4);
        }
        StringBuilder fromWords = new StringBuilder();
        for (String part : raw.toLowerCase(java.util.Locale.ROOT).split("[\\s,.;:!?-]+")) {
            if (part.isEmpty()) {
                continue;
            }
            String d = RU_NUM_WORD.get(part);
            if (d != null) {
                fromWords.append(d);
            }
            if (fromWords.length() >= 4) {
                return fromWords.substring(0, 4);
            }
        }
        return "";
    }

    private static boolean hasDirectVoiceKeyUrl(List<String> urlSink) {
        if (urlSink == null) {
            return false;
        }
        for (String url : urlSink) {
            if (url != null && url.contains("voice?key=")) {
                return true;
            }
        }
        return false;
    }

    private boolean isChallengeGone(Page page) {
        try {
            Object len = page.evaluate(
                    """
                            () => {
                              const t = document.querySelector(
                                'input[name="smart-token"], input[data-testid="smart-token"], input[name="smart_token"]');
                              return t && t.value ? t.value.length : 0;
                            }
                            """);
            if (len instanceof Number n && n.intValue() > 20) {
                return true;
            }
            for (Frame f : page.frames()) {
                if (!isSmartCaptchaFrame(f)) {
                    continue;
                }
                try {
                    Locator challenge = f.locator(ACTIVE_CHALLENGE_LOCATOR);
                    if (challenge.count() > 0 && isVisibleQuick(challenge.first())) {
                        return false;
                    }
                } catch (Exception ignored) {
                    // next frame
                }
            }
            return !isAudioChallengeVisible(page);
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Только URL в sink. Скачивание — на потоке solve ({@link #takeInterceptedOrDownload}):
     * {@code fetchUrl}/{@code request().get()} внутри onResponse даёт deadlock с {@code waitForTimeout}.
     */
    private Consumer<Response> captureAudioUrls(Page page, List<String> sink,
            AtomicReference<byte[]> mp3FromIntercept) {
        return response -> {
            try {
                String url = response.url();
                if (!isCapturableAudioUrl(url, response.headers().get("content-type"))) {
                    return;
                }
                if (response.status() < 200 || response.status() >= 400) {
                    return;
                }
                String direct = unwrapLoadCaptchaVoiceUrl(url);
                String downloadUrl = direct != null && direct.toLowerCase().contains("voice") ? direct : url;
                if (!isChallengeVoiceUrl(downloadUrl) && !downloadUrl.toLowerCase().contains("load-captcha-voice")) {
                    return;
                }
                if (!sink.contains(url)) {
                    sink.add(url);
                    log.info("SmartCaptcha audio: voice URL {}", truncate(url, 120));
                }
                if (direct != null && !direct.equals(url) && !sink.contains(direct)) {
                    sink.add(direct);
                    log.info("SmartCaptcha audio: voice URL (direct) {}", truncate(direct, 120));
                }
            } catch (Exception e) {
                log.warn("SmartCaptcha audio: перехват URL: {}", e.getMessage());
            }
        };
    }

    private static boolean isCapturableAudioUrl(String url, String contentType) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        if (!lower.contains("smartcaptcha") && !lower.contains("yandexcloud.net")
                && !lower.contains("captcha.yandex")) {
            return false;
        }
        if (isIntroOrStaticNoise(lower)) {
            return false;
        }
        if (lower.contains("/voice") || lower.contains("voice?")) {
            return true;
        }
        boolean audioCt = contentType != null && contentType.toLowerCase().contains("audio");
        return audioCt || MP3_URL.matcher(url).find() || lower.contains("/audio");
    }

    private static boolean isChallengeVoiceUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        if (isIntroOrStaticNoise(lower)) {
            return false;
        }
        return lower.contains("/voice") || lower.contains("voice?");
    }

    private static boolean isIntroOrStaticNoise(String urlLower) {
        return urlLower.contains("intro-ru")
                || urlLower.contains("intro-en")
                || urlLower.contains("/static/intro")
                || urlLower.contains("/static/");
    }

    private Optional<byte[]> resolveAudioBytes(Page page, Frame frame, List<String> urls) {
        for (String url : voiceUrlsOrdered(urls)) {
            Optional<byte[]> bytes = downloadVoiceBytes(page, frame, url);
            if (bytes.isPresent()) {
                return bytes;
            }
        }
        try {
            Object src = frame.evaluate(
                    """
                            () => {
                              const a = document.querySelector('audio');
                              if (a && a.src) return a.src;
                              const s = document.querySelector('source[type="audio/mpeg"], source[src*=".mp3"]');
                              if (s && s.src) return s.src;
                              return null;
                            }
                            """);
            if (src instanceof String url && !url.isBlank() && !isIntroOrStaticNoise(url.toLowerCase())) {
                return downloadVoiceBytes(page, frame, url);
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: audio[src]: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> pickBestAudioUrl(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }
        List<String> candidates = new ArrayList<>();
        for (String url : urls) {
            if (url == null || isIntroOrStaticNoise(url.toLowerCase())) {
                continue;
            }
            candidates.add(url);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort(Comparator.comparingInt((String u) -> scoreAudioUrl(u)).reversed());
        return Optional.of(candidates.get(0));
    }

    private static int scoreAudioUrl(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("/voice") || lower.contains("voice?")) {
            return 100;
        }
        if (MP3_URL.matcher(url).find()) {
            return 50;
        }
        return 10;
    }

    private Optional<byte[]> fetchUrl(Page page, Frame frame, String url) {
        try {
            String referer = frame != null ? safeUrl(frame) : null;
            if (referer == null || referer.isBlank()) {
                referer = page.url();
            }
            var opts = RequestOptions.create().setTimeout(8_000);
            if (referer != null && !referer.isBlank()) {
                opts.setHeader("Referer", referer);
            }
            var response = page.context().request().get(url, opts);
            int status = response.status();
            byte[] body = response.body();
            int len = body == null ? 0 : body.length;
            if (status >= 200 && status < 300 && body != null && len >= 200 && len <= 1_000_000) {
                log.info("SmartCaptcha audio: APIRequest скачано {} bytes (status={}) {}", len, status,
                        truncate(url, 90));
                return Optional.of(body);
            }
            log.warn("SmartCaptcha audio: APIRequest неудача status={} len={} url={}",
                    status, len, truncate(url, 90));
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: APIRequest {}: {}", truncate(url, 80), e.getMessage());
        }
        return Optional.empty();
    }

    /** Калейдоскоп → форма rep/play (для ввода ответа). Без Playwright click — он зависает на navigation. */
    private void ensureAudioChallengeUi(Frame frame, Page page, List<String> capturedUrls) {
        if (isAudioFormVisibleInFrame(frame)) {
            log.info("SmartCaptcha audio: аудио-форма уже на экране (rep/play)");
            return;
        }
        switchToAudioDomOnly(frame, page);
        page.waitForTimeout(500);
        if (isAudioFormVisibleInFrame(frame)) {
            log.info("SmartCaptcha audio: аудио-режим включён (rep/play видны)");
        } else {
            log.warn("SmartCaptcha audio: rep/play ещё нет — повтор DOM-клик «Аудио»");
            switchToAudioDomOnly(frame, page);
            page.waitForTimeout(600);
        }
    }

    private void switchToAudioDomOnly(Frame frame, Page page) {
        if (isAudioFormVisibleInFrame(frame)) {
            return;
        }
        clickChallengeTypeButton(frame, page);
        page.waitForTimeout(350);
    }

    /**
     * Реальный клик по координатам (React в iframe не реагирует на synthetic MouseEvent).
     * Playwright locator.click с коротким timeout — запасной вариант.
     */
    private boolean clickChallengeTypeButton(Frame frame, Page page) {
        if (isAudioFormVisibleInFrame(frame)) {
            log.info("SmartCaptcha audio: аудио-форма уже видна, challenge-type не нужна");
            return true;
        }
        if (clickChallengeTypeViaMouse(page, frame)) {
            return true;
        }
        if (clickChallengeTypeViaPageIframe(page)) {
            return true;
        }
        if (clickChallengeTypePlaywright(frame, page)) {
            return true;
        }
        return clickChallengeTypeInDom(frame);
    }

    /** Короткая пауза, пока onResponse положит voice?key= в sink (без locator — без зависаний). */
    private void briefPauseForVoiceUrl(Page page, List<String> capturedUrls, int maxWaitMs) {
        int step = 80;
        for (int waited = 0; waited < maxWaitMs; waited += step) {
            if (hasDirectVoiceKeyUrl(capturedUrls) || pickBestVoiceUrl(capturedUrls).isPresent()) {
                log.info("SmartCaptcha audio: voice URL в sink за {} ms", waited);
                return;
            }
            page.waitForTimeout(step);
        }
    }

    /** Клик в viewport: iframe offset + getBoundingClientRect кнопки внутри advanced frame. */
    private boolean clickChallengeTypeViaMouse(Page page, Frame frame) {
        try {
            Object raw = frame.evaluate(
                    """
                            () => {
                              const btn = document.querySelector('button[data-testid="challenge-type"]')
                                || document.querySelector('button[aria-label*="Изменить тип"]');
                              if (!btn) return { ok: false, reason: 'no-button' };
                              const r = btn.getBoundingClientRect();
                              if (r.width < 2 || r.height < 2) return { ok: false, reason: 'zero-size' };
                              return {
                                ok: true,
                                x: r.x, y: r.y, w: r.width, h: r.height,
                                label: (btn.getAttribute('aria-label') || '').trim().slice(0, 80)
                              };
                            }
                            """);
            if (!(raw instanceof java.util.Map<?, ?> map) || !Boolean.TRUE.equals(map.get("ok"))) {
                log.warn("SmartCaptcha audio: mouse click — кнопка не готова ({})",
                        raw instanceof java.util.Map<?, ?> m ? m.get("reason") : raw);
                return false;
            }
            ElementHandle frameEl = frame.frameElement();
            if (frameEl == null) {
                log.warn("SmartCaptcha audio: mouse click — frameElement null");
                return false;
            }
            BoundingBox iframeBox = frameEl.boundingBox();
            if (iframeBox == null) {
                log.warn("SmartCaptcha audio: mouse click — iframe boundingBox null");
                return false;
            }
            double bx = ((Number) map.get("x")).doubleValue();
            double by = ((Number) map.get("y")).doubleValue();
            double bw = ((Number) map.get("w")).doubleValue();
            double bh = ((Number) map.get("h")).doubleValue();
            double cx = iframeBox.x + bx + bw / 2;
            double cy = iframeBox.y + by + bh / 2;
            log.info("SmartCaptcha audio: mouse click challenge-type at ({}, {}) iframe=({}, {}) label={}",
                    Math.round(cx), Math.round(cy), Math.round(iframeBox.x), Math.round(iframeBox.y),
                    map.get("label"));
            page.mouse().move(cx, cy);
            page.mouse().click(cx, cy, new Mouse.ClickOptions().setDelay(40));
            page.waitForTimeout(450);
            return true;
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: mouse click challenge-type: {}", e.getMessage());
            return false;
        }
    }

    private boolean clickChallengeTypeViaPageIframe(Page page) {
        for (String iframeSel : ADVANCED_IFRAME_SELECTORS) {
            for (String btnSel : CHALLENGE_TYPE_SELECTORS) {
                try {
                    Locator btn = page.frameLocator(iframeSel).locator(btnSel).first();
                    log.info("SmartCaptcha audio: КЛИК challenge-type iframe={} btn={}", iframeSel, btnSel);
                    btn.click(new Locator.ClickOptions()
                            .setTimeout(CHALLENGE_TYPE_CLICK_TIMEOUT_MS)
                            .setNoWaitAfter(true)
                            .setForce(true));
                    log.info("SmartCaptcha audio: КЛИК challenge-type завершён");
                    return true;
                } catch (Exception e) {
                    log.warn("SmartCaptcha audio: frameLocator клик {} / {}: {}", iframeSel, btnSel, e.getMessage());
                }
            }
        }
        return false;
    }

    private static boolean clickChallengeTypeInDom(Frame frame) {
        try {
            Object clicked = frame.evaluate(
                    """
                            () => {
                              const fire = (el) => {
                                el.focus();
                                for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
                                  el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
                                }
                              };
                              const btn = document.querySelector('button[data-testid="challenge-type"]')
                                || document.querySelector('button[aria-label*="Изменить тип"]');
                              if (!btn) return { ok: false, reason: 'no-button' };
                              fire(btn);
                              return { ok: true, label: (btn.getAttribute('aria-label') || '').trim().slice(0, 80) };
                            }
                            """);
            if (clicked instanceof java.util.Map<?, ?> map && Boolean.TRUE.equals(map.get("ok"))) {
                log.info("SmartCaptcha audio: DOM challenge-type label={}", map.get("label"));
                return true;
            }
            log.warn("SmartCaptcha audio: DOM challenge-type — кнопка не найдена ({})",
                    clicked instanceof java.util.Map<?, ?> m ? m.get("reason") : clicked);
            return false;
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: DOM challenge-type: {}", e.getMessage());
            return false;
        }
    }

    private boolean clickChallengeTypePlaywright(Frame frame, Page page) {
        for (String sel : CHALLENGE_TYPE_SELECTORS) {
            try {
                Locator btn = frame.locator(sel).first();
                log.info("SmartCaptcha audio: Playwright КЛИК challenge-type selector={}", sel);
                btn.click(new Locator.ClickOptions()
                        .setTimeout(CHALLENGE_TYPE_CLICK_TIMEOUT_MS)
                        .setNoWaitAfter(true)
                        .setForce(true));
                page.waitForTimeout(400);
                return true;
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: Playwright challenge-type {}: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    /** Не блокировать поток на 10–30 с — только короткий force-click. */
    private void tryClickPlayQuick(Frame frame, Page page) {
        for (String sel : PLAY_SELECTORS) {
            try {
                Locator play = frame.locator(sel).first();
                if (play.count() == 0) {
                    continue;
                }
                play.click(new Locator.ClickOptions()
                        .setTimeout(800)
                        .setNoWaitAfter(true)
                        .setForce(true));
                log.info("SmartCaptcha audio: play (fallback) selector={}", sel);
                return;
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: play quick {}: {}", sel, e.getMessage());
            }
        }
        log.warn("SmartCaptcha audio: play (fallback) — кнопка не нажата");
    }

    private boolean submitAnswer(Frame frame, Page page, String answer) {
        if (answer == null || answer.length() != 4) {
            log.warn("SmartCaptcha audio: submit пропущен — answer len={}", answer == null ? -1 : answer.length());
            return false;
        }
        if (!isAudioFormVisibleInFrame(frame)) {
            log.warn("SmartCaptcha audio: rep/play не видны — сначала откройте аудио-форму");
            return false;
        }
        if (submitAnswerPlaywright(frame, page, answer)) {
            return true;
        }
        if (submitAnswerViaPageIframe(page, answer)) {
            return true;
        }
        return trySubmitAnswerViaDom(frame, page, answer);
    }

    private boolean submitAnswerPlaywright(Frame frame, Page page, String answer) {
        for (String sel : ANSWER_INPUT_SELECTORS) {
            try {
                Locator input = frame.locator(sel).first();
                if (input.count() == 0 || !isVisibleQuick(input)) {
                    continue;
                }
                log.info("SmartCaptcha audio: ввод в видимое поле selector={}", sel);
                input.click(new Locator.ClickOptions().setTimeout(2_000).setNoWaitAfter(true));
                input.fill("");
                input.pressSequentially(answer, new Locator.PressSequentiallyOptions().setDelay(40));
                log.info("SmartCaptcha audio: введено 4 цифры в name=rep (Playwright)");
                return clickSubmitAfterAnswer(frame, page);
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: input {}: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    private boolean submitAnswerViaPageIframe(Page page, String answer) {
        for (String iframeSel : ADVANCED_IFRAME_SELECTORS) {
            for (String sel : ANSWER_INPUT_SELECTORS) {
                try {
                    Locator input = page.frameLocator(iframeSel).locator(sel).first();
                    if (input.count() == 0 || !isVisibleQuick(input)) {
                        continue;
                    }
                    log.info("SmartCaptcha audio: ввод rep через frameLocator iframe={} sel={}", iframeSel, sel);
                    input.click(new Locator.ClickOptions().setTimeout(2_000).setNoWaitAfter(true));
                    input.fill("");
                    input.pressSequentially(answer, new Locator.PressSequentiallyOptions().setDelay(40));
                    page.waitForTimeout(200);
                    Locator submit = page.frameLocator(iframeSel).locator("button[data-testid='submit']").first();
                    if (submit.count() > 0 && isVisibleQuick(submit)) {
                        submit.click(new Locator.ClickOptions()
                                .setTimeout(2_000)
                                .setNoWaitAfter(true)
                                .setForce(true));
                        log.info("SmartCaptcha audio: submit через frameLocator");
                        return true;
                    }
                    input.press("Enter");
                    return true;
                } catch (Exception e) {
                    log.debug("SmartCaptcha audio: frameLocator rep {}: {}", sel, e.getMessage());
                }
            }
        }
        return false;
    }

    private boolean clickSubmitAfterAnswer(Frame frame, Page page) {
        for (String submitSel : SUBMIT_SELECTORS) {
            try {
                Locator submit = frame.locator(submitSel).first();
                if (submit.count() > 0 && isVisibleQuick(submit)) {
                    submit.click(new Locator.ClickOptions()
                            .setTimeout(2_000)
                            .setNoWaitAfter(true)
                            .setForce(true));
                    log.info("SmartCaptcha audio: submit {}", submitSel);
                    return true;
                }
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: submit {}: {}", submitSel, e.getMessage());
            }
        }
        try {
            Locator rep = frame.locator(
                    "input.Textinput-Control[name='rep']:visible, input[name='rep'][inputmode='numeric']:visible")
                    .first();
            if (rep.count() > 0 && isVisibleQuick(rep)) {
                rep.press("Enter");
                log.info("SmartCaptcha audio: submit через Enter на видимом rep");
                return true;
            }
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: submit не удался: {}", e.getMessage());
        }
        return false;
    }

    private boolean trySubmitAnswerViaDom(Frame frame, Page page, String answer) {
        if (answer == null || answer.length() != 4) {
            return false;
        }
        try {
            Object raw = frame.evaluate(
                    """
                            (digits) => {
                              const isVisible = (el) => {
                                if (!el) return false;
                                const st = window.getComputedStyle(el);
                                if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                                const r = el.getBoundingClientRect();
                                return r.width > 2 && r.height > 2;
                              };
                              const input = document.querySelector(
                                "input.Textinput-Control[name='rep'], input[name='rep'][inputmode='numeric'], input[name='rep']");
                              if (!input) return { ok: false, reason: 'no-rep' };
                              if (!isVisible(input)) return { ok: false, reason: 'rep-hidden' };
                              input.focus();
                              input.value = '';
                              input.value = digits;
                              input.dispatchEvent(new Event('input', { bubbles: true }));
                              input.dispatchEvent(new Event('change', { bubbles: true }));
                              const submit = document.querySelector(
                                "button[data-testid='submit'], button[type='submit'], .AdvancedCaptcha_submit");
                              if (submit && !submit.disabled && isVisible(submit)) {
                                submit.click();
                                return { ok: true, via: 'submit-click', len: digits.length };
                              }
                              input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
                              input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
                              return { ok: true, via: 'enter', len: digits.length };
                            }
                            """,
                    answer);
            if (raw instanceof java.util.Map<?, ?> map && Boolean.TRUE.equals(map.get("ok"))) {
                log.info("SmartCaptcha audio: ответ введён через DOM ({}), len={}", map.get("via"), map.get("len"));
                page.waitForTimeout(300);
                return true;
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: DOM ввод rep: {}", e.getMessage());
        }
        return false;
    }

    private Optional<Frame> findAdvancedFrame(Page page) {
        Frame advanced = null;
        for (Frame f : page.frames()) {
            String url = safeUrl(f);
            if (url.contains("smartcaptcha") && url.contains("/advanced")) {
                advanced = f;
            }
            try {
                if (f.locator(ACTIVE_CHALLENGE_LOCATOR.replace(":visible", "")).count() > 0) {
                    return Optional.of(f);
                }
            } catch (Exception ignored) {
            }
        }
        return Optional.ofNullable(advanced);
    }

    private static boolean isSmartCaptchaFrame(Frame f) {
        String url = safeUrl(f);
        return url.contains("smartcaptcha") || url.contains("captcha.yandex") || url.contains("yandexcloud.net");
    }

    private static String safeUrl(Frame frame) {
        try {
            return frame != null ? frame.url() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
