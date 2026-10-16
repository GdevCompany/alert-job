package by.gdev.alert.job.notification.service.ai.parser.youdo;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Логирует XHR/fetch YouDo при логине (отправка email, OTP), чтобы видеть отказ сервера при «тихом» UI.
 */
@Slf4j
@Component
public class YouDoLoginApiListener {

    private static final int BODY_LOG_MAX = 600;

    private final Map<Page, Consumer<Response>> handlers = new ConcurrentHashMap<>();

    public void begin(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        end(page);
        Consumer<Response> handler = response -> onResponse(response);
        handlers.put(page, handler);
        page.onResponse(handler);
        log.debug("YOUDO API: перехват ответов включён");
    }

    public void end(Page page) {
        if (page == null) {
            return;
        }
        Consumer<Response> handler = handlers.remove(page);
        if (handler != null) {
            try {
                page.offResponse(handler);
            } catch (Exception e) {
                log.debug("YOUDO API: offResponse: {}", e.getMessage());
            }
        }
    }

    private void onResponse(Response response) {
        try {
            String url = response.url();
            if (url == null || !url.contains("youdo.com")) {
                return;
            }
            String type = response.request().resourceType();
            if (!"xhr".equals(type) && !"fetch".equals(type)) {
                return;
            }
            String lower = url.toLowerCase();
            if (!isLoginRelatedUrl(lower)) {
                return;
            }
            int status = response.status();
            String method = response.request().method();
            String body = "";
            try {
                body = response.text();
            } catch (Exception e) {
                body = "<body unreadable: " + e.getMessage() + ">";
            }
            log.info("YOUDO API: {} {} {} → body={}",
                    status, method, truncateUrl(url), truncateBody(body));
        } catch (Exception e) {
            log.debug("YOUDO API: onResponse: {}", e.getMessage());
        }
    }

    private static boolean isLoginRelatedUrl(String lower) {
        return lower.contains("login")
                || lower.contains("auth")
                || lower.contains("signin")
                || lower.contains("sign-in")
                || lower.contains("otp")
                || lower.contains("verify")
                || lower.contains("code")
                || lower.contains("session")
                || lower.contains("captcha")
                || lower.contains("/api/")
                || lower.contains("graphql");
    }

    private static String truncateUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 160 ? url.substring(0, 160) + "…" : url;
    }

    private static String truncateBody(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > BODY_LOG_MAX ? oneLine.substring(0, BODY_LOG_MAX) + "…" : oneLine;
    }
}
