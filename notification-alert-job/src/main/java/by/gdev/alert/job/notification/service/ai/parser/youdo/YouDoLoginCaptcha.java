package by.gdev.alert.job.notification.service.ai.parser.youdo;

import by.gdev.common.service.playwright.captcha.CaptchaService;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Тонкая обёртка над {@link CaptchaService} для перехвата network калейдоскопа на YouDo.
 */
@Component
@RequiredArgsConstructor
public class YouDoLoginCaptcha {

    private final CaptchaService captchaService;

    public void beginNetworkCapture(Page page) {
        captchaService.beginKaleidoscopeNetworkCapture(page);
    }

    public void endNetworkCapture(Page page) {
        captchaService.endKaleidoscopeNetworkCapture(page);
    }
}
