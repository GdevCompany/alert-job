package by.gdev.common.service.playwright.captcha;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
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
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Yandex SmartCaptcha audio challenge → 2Captcha {@link TwoCaptchaClient#solveAudioCaptcha}.
 * Аудио скачивается через APIRequestContext (не {@code response.body()} в onResponse — deadlock).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class YandexSmartCaptchaAudioSolver {

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
        Consumer<Response> listener = captureAudioUrls(capturedUrls);
        page.context().onResponse(listener);
        try {
            for (int round = 1; round <= 2; round++) {
                log.info("SmartCaptcha audio: раунд {}/2", round);
                if (round > 1) {
                    capturedUrls.clear();
                    if (seedVoiceUrls != null) {
                        for (String u : seedVoiceUrls) {
                            if (u != null && !u.isBlank()) {
                                capturedUrls.add(u);
                            }
                        }
                    }
                }
                Optional<Frame> frameOpt = findAdvancedFrame(page);
                if (frameOpt.isEmpty()) {
                    log.warn("SmartCaptcha audio: advanced frame не найден (round={})", round);
                    page.waitForTimeout(800);
                    continue;
                }
                Frame frame = frameOpt.get();
                log.info("SmartCaptcha audio: frame url={}", truncate(safeUrl(frame), 100));
                log.info("SmartCaptcha audio: voice urls на старте раунда: {}",
                        voiceUrlsPreview(capturedUrls));

                if (!isAudioFormVisibleInFrame(frame)) {
                    log.info("SmartCaptcha audio: СНАЧАЛА клик «Изменить тип задания» (challenge-type)");
                    openAudioFormForInput(frame, page, capturedUrls, 12_000);
                }

                byte[] mp3 = null;
                if (!pickBestVoiceUrl(capturedUrls).isEmpty()) {
                    log.info("SmartCaptcha audio: скачивание mp3 (seed/перехват)");
                    mp3 = guaranteedDownloadVoice(page, frame, capturedUrls);
                }
                if (mp3 == null || mp3.length < 200) {
                    switchToAudioDomOnly(frame, page);
                    page.waitForTimeout(400);
                    if (pickBestVoiceUrl(capturedUrls).isEmpty()) {
                        log.info("SmartCaptcha audio: voice URL нет — play + короткое ожидание");
                        clickPlay(frame, page);
                        waitForVoiceUrl(page, capturedUrls, 4_000);
                        log.info("SmartCaptcha audio: urls после play: count={} preview={}",
                                capturedUrls.size(), voiceUrlsPreview(capturedUrls));
                    }
                    mp3 = guaranteedDownloadVoice(page, frame, capturedUrls);
                }
                if (mp3 == null || mp3.length < 200) {
                    log.warn("SmartCaptcha audio: повтор после play (round={})", round);
                    clickPlay(frame, page);
                    page.waitForTimeout(1500);
                    waitForVoiceUrl(page, capturedUrls, 5_000);
                    mp3 = guaranteedDownloadVoice(page, frame, capturedUrls);
                }
                if (mp3 == null || mp3.length < 200) {
                    log.error(
                            "SmartCaptcha audio: mp3 НЕ скачан — AudioTask НЕ отправлен (round={}, urls={})",
                            round, voiceUrlsPreview(capturedUrls));
                    continue;
                }
                if (mp3.length > 1_000_000) {
                    log.warn("SmartCaptcha audio: файл > 1 MB, пропуск");
                    continue;
                }
                log.info("SmartCaptcha audio: mp3 bytes={} → ОБЯЗАТЕЛЬНАЯ отправка AudioTask в 2Captcha",
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

                if (!openAudioFormForInput(frame, page, capturedUrls, 15_000)) {
                    log.warn("SmartCaptcha audio: не открыли аудио-форму (input[name=rep]) перед вводом");
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

    /**
     * Скачать mp3 по URL из перехвата (сессия браузера), без {@code Response#body()} в CDP.
     */
    /**
     * Если voice URL есть в списке — всегда делаем несколько попыток скачать (без тихого пропуска).
     */
    private byte[] guaranteedDownloadVoice(Page page, Frame frame, List<String> urlSink) {
        expandVoiceUrlsInSink(urlSink);
        if (pickBestVoiceUrl(urlSink).isEmpty()) {
            return null;
        }
        for (int attempt = 1; attempt <= 5; attempt++) {
            expandVoiceUrlsInSink(urlSink);
            log.info("SmartCaptcha audio: скачивание попытка {}/5, urls={}",
                    attempt, voiceUrlsPreview(urlSink));
            byte[] mp3 = fetchVoiceMp3(page, frame, urlSink);
            if (mp3 != null && mp3.length >= 200) {
                return mp3;
            }
            page.waitForTimeout(350L * attempt);
        }
        return null;
    }

    private byte[] fetchVoiceMp3(Page page, Frame frame, List<String> urlSink) {
        for (String url : voiceUrlsOrdered(urlSink)) {
            String direct = url.contains("voice?key=") ? truncate(url, 120) : truncate(url, 100);
            log.info("SmartCaptcha audio: скачиваем voice url={}", direct);
            Optional<byte[]> bytes = downloadVoiceBytes(page, frame, url);
            if (bytes.isPresent()) {
                return bytes.get();
            }
        }
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

    private void waitForVoiceUrl(Page page, List<String> urlSink, int maxWaitMs) {
        int step = 250;
        int attempts = Math.max(1, maxWaitMs / step);
        for (int i = 0; i < attempts; i++) {
            if (!pickBestVoiceUrl(urlSink).isEmpty()) {
                return;
            }
            page.waitForTimeout(step);
        }
    }

    /**
     * Калейдоскоп → аудио: кнопка {@code data-testid=challenge-type} («Изменить тип задания»), затем {@code input[name=rep]}.
     */
    private boolean openAudioFormForInput(Frame frame, Page page, List<String> capturedUrls, int maxWaitMs) {
        if (isAudioFormVisibleInFrame(frame)) {
            log.info("SmartCaptcha audio: аудио-форма уже видна (rep/play)");
            return true;
        }
        long deadline = System.currentTimeMillis() + maxWaitMs;
        int clickRound = 0;
        while (System.currentTimeMillis() < deadline) {
            clickRound++;
            log.info("SmartCaptcha audio: клик challenge-type (раунд {})", clickRound);
            if (clickChallengeTypeButton(frame, page)) {
                page.waitForTimeout(500);
                if (isAudioFormVisibleInFrame(frame)) {
                    log.info("SmartCaptcha audio: аудио-форма открыта после challenge-type (раунд {})", clickRound);
                    return true;
                }
                if (hasDirectVoiceKeyUrl(capturedUrls)) {
                    log.info("SmartCaptcha audio: voice?key= после challenge-type — не ждём rep в цикле (раунд {})",
                            clickRound);
                    waitForVoiceUrl(page, capturedUrls, 1_500);
                    page.waitForTimeout(600);
                    if (isAudioFormVisibleInFrame(frame)) {
                        return true;
                    }
                    return false;
                }
            }
            waitForVoiceUrl(page, capturedUrls, 600);
            page.waitForTimeout(350);
        }
        boolean ok = isAudioFormVisibleInFrame(frame);
        if (!ok) {
            log.warn("SmartCaptcha audio: rep не появился за {} ms (кликов challenge-type={})", maxWaitMs, clickRound);
        }
        return ok;
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
            Optional<byte[]> viaBrowser = fetchVoiceInBrowser(frame, candidate);
            if (viaBrowser.isPresent()) {
                return viaBrowser;
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
                              const r = await fetch(voiceUrl, { credentials: 'include', cache: 'no-store' });
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

    /** Только цифры из ответа 2Captcha; ровно 4 — иначе пусто (повтор / reportIncorrect). */
    private static String normalizeAnswer(String raw) {
        if (raw == null) {
            return "";
        }
        String digitsOnly = raw.replaceAll("\\D", "");
        if (digitsOnly.length() >= 4) {
            return digitsOnly.substring(0, 4);
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

    /** Только URL — никогда {@code response.body()} здесь (deadlock Playwright). */
    private Consumer<Response> captureAudioUrls(List<String> sink) {
        return response -> {
            try {
                String url = response.url();
                if (!isCapturableAudioUrl(url, response.headers().get("content-type"))) {
                    return;
                }
                if (response.status() < 200 || response.status() >= 400) {
                    return;
                }
                if (!sink.contains(url)) {
                    sink.add(url);
                    log.info("SmartCaptcha audio: URL перехват {} (скачивание после switch/wait)",
                            truncate(url, 90));
                }
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: url capture: {}", e.getMessage());
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
            String referer = safeUrl(frame);
            var opts = RequestOptions.create().setTimeout(30_000);
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

    /** Один клик challenge-type: сначала frameLocator на странице, без повторных Playwright-кликов (зависания). */
    private boolean clickChallengeTypeButton(Frame frame, Page page) {
        if (clickChallengeTypeViaPageIframe(page)) {
            page.waitForTimeout(400);
            return true;
        }
        if (clickChallengeTypePlaywright(frame, page)) {
            page.waitForTimeout(400);
            return true;
        }
        return clickChallengeTypeInDom(frame);
    }

    private boolean clickChallengeTypeViaPageIframe(Page page) {
        for (String iframeSel : ADVANCED_IFRAME_SELECTORS) {
            for (String btnSel : CHALLENGE_TYPE_SELECTORS) {
                try {
                    Locator btn = page.frameLocator(iframeSel).locator(btnSel).first();
                    if (btn.count() == 0 || !isVisibleQuick(btn)) {
                        continue;
                    }
                    log.info("SmartCaptcha audio: КЛИК challenge-type iframe={} btn={}", iframeSel, btnSel);
                    btn.click(new Locator.ClickOptions()
                            .setTimeout(CHALLENGE_TYPE_CLICK_TIMEOUT_MS)
                            .setNoWaitAfter(true)
                            .setForce(true));
                    page.waitForTimeout(450);
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
                if (btn.count() == 0 || !isVisibleQuick(btn)) {
                    continue;
                }
                log.info("SmartCaptcha audio: Playwright КЛИК challenge-type selector={}", sel);
                btn.click(new Locator.ClickOptions()
                        .setTimeout(CHALLENGE_TYPE_CLICK_TIMEOUT_MS)
                        .setNoWaitAfter(true)
                        .setForce(true));
                page.waitForTimeout(400);
                return true;
            } catch (Exception e) {
                log.warn("SmartCaptcha audio: Playwright challenge-type {}: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    private void clickPlay(Frame frame, Page page) {
        for (String sel : PLAY_SELECTORS) {
            try {
                Locator play = frame.locator(sel).first();
                if (play.count() == 0 || !isVisibleQuick(play)) {
                    continue;
                }
                play.click(new Locator.ClickOptions()
                        .setTimeout(2_000)
                        .setNoWaitAfter(true)
                        .setForce(true));
                log.info("SmartCaptcha audio: play selector={}", sel);
                return;
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: play {}: {}", sel, e.getMessage());
            }
        }
        log.warn("SmartCaptcha audio: кнопка play не найдена");
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
