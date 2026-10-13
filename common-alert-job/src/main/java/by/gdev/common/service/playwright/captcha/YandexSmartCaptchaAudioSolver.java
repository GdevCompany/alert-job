package by.gdev.common.service.playwright.captcha;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Yandex SmartCaptcha audio challenge → 2Captcha {@link TwoCaptchaClient#solveAudioCaptcha}.
 * Аудио скачивается в браузере (свой прокси), в 2Captcha уходит только base64 mp3.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class YandexSmartCaptchaAudioSolver {

    private static final Pattern MP3_URL = Pattern.compile("\\.mp3([?#]|$)", Pattern.CASE_INSENSITIVE);

    private static final String[] SWITCH_TO_AUDIO_SELECTORS = {
            "button[data-testid='challenge-type'][aria-label*='Аудио']",
            "button[data-testid='challenge-type'][aria-label*='Audio']",
            ".AdvancedCaptcha-Button_type_audio",
            "button.AdvancedCaptcha-Button[aria-label*='Аудио']",
            "button.AdvancedCaptcha-Button[aria-label*='audio']",
            "[data-testid='challenge-type']"
    };

    private static final String[] PLAY_SELECTORS = {
            "[data-testid='play']",
            "button[aria-label*='Воспроизвести']",
            "button[aria-label*='Play']",
            ".AdvancedCaptcha-Button_type_play",
            ".AdvancedCaptcha_audio button"
    };

    private static final String[] ANSWER_INPUT_SELECTORS = {
            "input.AdvancedCaptcha-Input",
            "input[name='rep']",
            "input[type='text'][autocomplete='off']",
            ".AdvancedCaptcha_audio input[type='text']"
    };

    private static final String[] SUBMIT_SELECTORS = {
            "button[type='submit']",
            ".AdvancedCaptcha-Button_action_submit",
            "button.AdvancedCaptcha-Button[data-testid='submit']",
            ".AdvancedCaptcha-Button:not([data-testid='play']):not([data-testid='challenge-type'])"
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

        List<byte[]> capturedMp3 = new CopyOnWriteArrayList<>();
        Consumer<Response> listener = captureAudioResponses(capturedMp3);
        page.context().onResponse(listener);
        try {
            for (int round = 1; round <= 2; round++) {
                Optional<Frame> frameOpt = findAdvancedFrame(page);
                if (frameOpt.isEmpty()) {
                    log.warn("SmartCaptcha audio: advanced frame не найден (round={})", round);
                    page.waitForTimeout(800);
                    continue;
                }
                Frame frame = frameOpt.get();
                switchToAudioIfNeeded(frame, page);
                page.waitForTimeout(400);
                clickPlay(frame, page);
                page.waitForTimeout(2500);

                Optional<byte[]> audioBytes = resolveAudioBytes(page, frame, capturedMp3);
                if (audioBytes.isEmpty()) {
                    log.warn("SmartCaptcha audio: не удалось получить mp3 (round={})", round);
                    continue;
                }
                byte[] mp3 = audioBytes.get();
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
                    continue;
                }
                if (!submitAnswer(frame, page, answer)) {
                    log.warn("SmartCaptcha audio: не удалось ввести/отправить ответ");
                    continue;
                }
                page.waitForTimeout(2000);
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

    private static String maskAnswer(String answer) {
        if (answer == null || answer.length() <= 2) {
            return "***";
        }
        return answer.charAt(0) + "***" + answer.charAt(answer.length() - 1);
    }

    private static String normalizeAnswer(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ");
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
            Locator challenges = page.locator(
                    ".AdvancedCaptcha_kaleidoscope, .AdvancedCaptcha_audio, #captcha-slider");
            int n = challenges.count();
            for (int i = 0; i < Math.min(n, 6); i++) {
                if (challenges.nth(i).isVisible()) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private Consumer<Response> captureAudioResponses(List<byte[]> sink) {
        return response -> {
            try {
                String url = response.url();
                if (url == null || (!url.contains("smartcaptcha") && !url.contains("captcha.yandex"))) {
                    return;
                }
                if (response.status() < 200 || response.status() >= 300) {
                    return;
                }
                String contentType = response.headers().get("content-type");
                boolean audio = contentType != null && contentType.toLowerCase().contains("audio");
                boolean mp3Url = MP3_URL.matcher(url).find() || url.toLowerCase().contains("/audio");
                if (!audio && !mp3Url) {
                    return;
                }
                byte[] body = response.body();
                if (body == null || body.length < 500) {
                    return;
                }
                if (body.length <= 1_000_000) {
                    sink.add(body);
                    log.info("SmartCaptcha audio: перехват {} bytes url={}", body.length, truncate(url, 90));
                }
            } catch (Exception e) {
                log.debug("SmartCaptcha audio: network capture: {}", e.getMessage());
            }
        };
    }

    private Optional<byte[]> resolveAudioBytes(Page page, Frame frame, List<byte[]> captured) {
        if (!captured.isEmpty()) {
            byte[] best = captured.get(captured.size() - 1);
            return Optional.of(best);
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
            if (src instanceof String url && !url.isBlank()) {
                return fetchUrl(page, url);
            }
        } catch (Exception e) {
            log.debug("SmartCaptcha audio: audio[src]: {}", e.getMessage());
        }
        return Optional.empty();
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
                page.waitForTimeout(600);
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
    }

    private boolean submitAnswer(Frame frame, Page page, String answer) {
        for (String sel : ANSWER_INPUT_SELECTORS) {
            try {
                Locator input = frame.locator(sel).first();
                if (input.count() == 0 || !input.isVisible()) {
                    continue;
                }
                input.fill(answer);
                log.info("SmartCaptcha audio: ответ введён в {}", sel);
                for (String submitSel : SUBMIT_SELECTORS) {
                    Locator submit = frame.locator(submitSel).first();
                    if (submit.count() > 0 && submit.isVisible()) {
                        submit.click();
                        log.info("SmartCaptcha audio: submit {}", submitSel);
                        return true;
                    }
                }
                input.press("Enter");
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
        return url.contains("smartcaptcha") || url.contains("captcha.yandex");
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
