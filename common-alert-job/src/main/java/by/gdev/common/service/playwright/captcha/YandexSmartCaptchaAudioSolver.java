package by.gdev.common.service.playwright.captcha;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    private static final String[] SWITCH_TO_AUDIO_SELECTORS = {
            "button[data-testid='challenge-type'][aria-label*='Аудио']",
            "button[data-testid='challenge-type'][aria-label*='Audio']",
            "button[data-testid='challenge-type'][aria-label*='Изменить']",
            ".AdvancedCaptcha-Button_type_audio",
            "button.AdvancedCaptcha-Button[aria-label*='Аудио']",
            "button.AdvancedCaptcha-Button[aria-label*='audio']",
            "[data-testid='challenge-type']"
    };

    private static final String[] PLAY_SELECTORS = {
            "[data-testid='play']",
            "button[aria-label*='Прослушать']",
            "button[aria-label*='Воспроизвести']",
            "button[aria-label*='Play']",
            ".AdvancedCaptcha_audio-button",
            ".AdvancedCaptcha-Button_type_play",
            ".AdvancedCaptcha_audio button"
    };

    private static final String[] ANSWER_INPUT_SELECTORS = {
            "input[name='rep']",
            "input.Textinput-Control",
            "input.AdvancedCaptcha-Input",
            "input[type='text'][autocomplete='off']",
            ".AdvancedCaptcha_audio input[type='text']",
            ".AdvancedCaptcha_audio input"
    };

    private static final String[] SUBMIT_SELECTORS = {
            "button[data-testid='submit']",
            "button[type='submit']",
            ".AdvancedCaptcha_submit",
            ".AdvancedCaptcha-Button_action_submit",
            "button.AdvancedCaptcha-Button[data-testid='submit']"
    };

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
                Locator audio = f.locator(".AdvancedCaptcha_audio, [data-testid='play']");
                if (audio.count() > 0 && audio.first().isVisible()) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: visibility check: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Переключиться на аудио (если нужно), скачать mp3, распознать через 2Captcha, ввести ответ.
     */
    public boolean solve(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (!twoCaptchaClient.isConfigured()) {
            log.warn("SmartCaptcha audio: 2Captcha не настроен");
            return false;
        }

        List<String> capturedUrls = new CopyOnWriteArrayList<>();
        Consumer<Response> listener = captureAudioUrls(capturedUrls);
        page.context().onResponse(listener);
        try {
            for (int round = 1; round <= 2; round++) {
                log.info("SmartCaptcha audio: раунд {}/2", round);
                Optional<Frame> frameOpt = findAdvancedFrame(page);
                if (frameOpt.isEmpty()) {
                    log.warn("SmartCaptcha audio: advanced frame не найден (round={})", round);
                    page.waitForTimeout(800);
                    continue;
                }
                Frame frame = frameOpt.get();
                log.info("SmartCaptcha audio: frame url={}", truncate(safeUrl(frame), 100));

                switchToAudioIfNeeded(frame, page);
                page.waitForTimeout(400);

                capturedUrls.clear();
                byte[] mp3 = captureVoiceAfterPlay(page, frame, capturedUrls);
                if (mp3 == null || mp3.length < 500) {
                    log.warn("SmartCaptcha audio: не удалось получить mp3 (round={}, urls={})",
                            round, capturedUrls.size());
                    continue;
                }
                if (mp3.length > 1_000_000) {
                    log.warn("SmartCaptcha audio: файл > 1 MB, пропуск");
                    continue;
                }
                log.info("SmartCaptcha audio: mp3 bytes={}", mp3.length);

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
                log.info("SmartCaptcha audio: ответ 2Captcha → «{}» (digits len={})",
                        maskAnswer(answer), answer.length());

                if (!submitAnswer(frame, page, answer)) {
                    log.warn("SmartCaptcha audio: не удалось ввести/отправить ответ");
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
     * Клик «Прослушать» и загрузка challenge-аудио вне обработчика onResponse.
     */
    private byte[] captureVoiceAfterPlay(Page page, Frame frame, List<String> urlSink) {
        try {
            Response voice = page.waitForResponse(
                    r -> isChallengeVoiceUrl(r.url()) && r.status() >= 200 && r.status() < 400,
                    new Page.WaitForResponseOptions().setTimeout(25_000),
                    () -> clickPlay(frame, page));
            log.info("SmartCaptcha audio: voice response url={}", truncate(voice.url(), 90));
            return voice.body();
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: waitForResponse voice: {}", e.getMessage());
        }

        clickPlay(frame, page);
        page.waitForTimeout(3000);

        Optional<String> bestUrl = pickBestAudioUrl(urlSink);
        if (bestUrl.isPresent()) {
            return fetchUrl(page, bestUrl.get()).orElse(null);
        }
        return resolveAudioBytes(page, frame, urlSink).orElse(null);
    }

    private static String maskAnswer(String answer) {
        if (answer == null || answer.length() <= 2) {
            return "***";
        }
        return answer.charAt(0) + "***" + answer.charAt(answer.length() - 1);
    }

    /** Yandex audio: четыре цифры; 2Captcha иногда отдаёт слова — вытаскиваем цифры. */
    private static String normalizeAnswer(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        String digitsOnly = trimmed.replaceAll("\\D", "");
        if (digitsOnly.length() >= 4) {
            return digitsOnly.substring(0, 4);
        }
        if (digitsOnly.length() > 0) {
            return digitsOnly;
        }
        return trimmed.replaceAll("\\s+", " ");
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
                    Locator challenge = f.locator(
                            ".AdvancedCaptcha_audio:visible, .AdvancedCaptcha_kaleidoscope:visible, "
                                    + "#captcha-slider:visible, [data-testid='play']:visible");
                    if (challenge.count() > 0 && challenge.first().isVisible()) {
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
                    log.info("SmartCaptcha audio: URL перехват {}", truncate(url, 90));
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
        Optional<String> best = pickBestAudioUrl(urls);
        if (best.isPresent()) {
            return fetchUrl(page, best.get());
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
                return fetchUrl(page, url);
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

    private Optional<byte[]> fetchUrl(Page page, String url) {
        try {
            var response = page.context().request().get(url, RequestOptions.create().setTimeout(30_000));
            if (response.status() >= 200 && response.status() < 300) {
                byte[] body = response.body();
                if (body != null && body.length > 500 && body.length <= 1_000_000) {
                    log.info("SmartCaptcha audio: скачано {} bytes с {}", body.length, truncate(url, 90));
                    return Optional.of(body);
                }
            }
        } catch (Exception e) {
            log.warn("SmartCaptcha audio: download {}: {}", truncate(url, 80), e.getMessage());
        }
        return Optional.empty();
    }

    private void switchToAudioIfNeeded(Frame frame, Page page) {
        if (isAudioChallengeVisible(page)) {
            log.info("SmartCaptcha audio: UI аудио уже виден");
            return;
        }
        for (String sel : SWITCH_TO_AUDIO_SELECTORS) {
            try {
                Locator btn = frame.locator(sel).first();
                if (btn.count() == 0 || !btn.isVisible()) {
                    continue;
                }
                btn.click();
                log.info("SmartCaptcha audio: переключение на аудио selector={}", sel);
                page.waitForTimeout(800);
                return;
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: switch {}: {}", sel, e.getMessage());
            }
        }
    }

    private void clickPlay(Frame frame, Page page) {
        for (String sel : PLAY_SELECTORS) {
            try {
                Locator play = frame.locator(sel).first();
                if (play.count() == 0 || !play.isVisible()) {
                    continue;
                }
                play.click();
                log.info("SmartCaptcha audio: play selector={}", sel);
                return;
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: play {}: {}", sel, e.getMessage());
            }
        }
        log.warn("SmartCaptcha audio: кнопка play не найдена");
    }

    private boolean submitAnswer(Frame frame, Page page, String answer) {
        for (String sel : ANSWER_INPUT_SELECTORS) {
            try {
                Locator input = frame.locator(sel).first();
                if (input.count() == 0 || !input.isVisible()) {
                    continue;
                }
                input.click();
                input.fill("");
                input.fill(answer);
                log.info("SmartCaptcha audio: ответ введён в {}", sel);
                for (String submitSel : SUBMIT_SELECTORS) {
                    Locator submit = frame.locator(submitSel).first();
                    if (submit.count() > 0 && submit.isVisible() && submit.isEnabled()) {
                        submit.click();
                        log.info("SmartCaptcha audio: submit {}", submitSel);
                        return true;
                    }
                }
                input.press("Enter");
                log.info("SmartCaptcha audio: submit через Enter");
                return true;
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: input {}: {}", sel, e.getMessage());
            }
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
                if (f.locator(".AdvancedCaptcha_audio, [data-testid='play'], .AdvancedCaptcha_kaleidoscope").count() > 0) {
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
