package by.gdev.alert.job.notification.service.ai.parser.youdo;

import by.gdev.common.model.SiteName;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.Locale;

/** Страницы блокировки YouDo (WAF / 403 под прокси). */
public final class YouDoPlaywrightGuards {

    public static final String YOUDO_PROXY_BLOCKED_CODE = "YOUDO_PROXY_BLOCKED";

    public static final String PROXY_BLOCKED_MARKER = "доступ к youdo.com ограничен системой защиты";

    private YouDoPlaywrightGuards() {
    }

    public static String proxyBlockedFailMessage(SiteName site, String details) {
        String base = site + ": IP/прокси заблокирован защитой YouDo (403 Forbidden). "
                + PROXY_BLOCKED_MARKER;
        if (details == null || details.isBlank()) {
            return base;
        }
        return base + " " + details;
    }

    public static boolean isProxyBlockedInBody(String htmlOrText) {
        if (htmlOrText == null || htmlOrText.isEmpty()) {
            return false;
        }
        String lower = htmlOrText.toLowerCase(Locale.ROOT);
        if (lower.contains("something_broken") && lower.contains("403")) {
            return true;
        }
        if (lower.contains("403 forbidden") && lower.contains(PROXY_BLOCKED_MARKER)) {
            return true;
        }
        return lower.contains("id=\"message\"") && lower.contains("request-id")
                && lower.contains("ограничен системой защиты");
    }

    public static boolean isProxyBlockedPage(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            Locator message = page.locator("#Message.something_broken, div.something_broken #Message").first();
            if (message.count() > 0 && message.isVisible()) {
                return true;
            }
            Locator title = page.locator("div.something_broken .title, #Message .title").first();
            if (title.count() > 0 && title.isVisible()) {
                String t = title.innerText();
                if (t != null && t.toLowerCase(Locale.ROOT).contains("403")) {
                    return true;
                }
            }
            if (isProxyBlockedInBody(page.content())) {
                return true;
            }
            String bodyText = page.locator("body").innerText();
            return isProxyBlockedInBody(bodyText);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** IP, Request ID и время с страницы блокировки — для лога. */
    public static String formatBlockDetails(Page page) {
        if (page == null || page.isClosed()) {
            return "";
        }
        try {
            Object raw = page.evaluate(
                    """
                            () => {
                              const pick = (id) => {
                                const el = document.getElementById(id);
                                return el ? (el.innerText || el.textContent || '').trim() : '';
                              };
                              const title = document.querySelector('.title, #Message .title');
                              return JSON.stringify({
                                title: title ? (title.textContent || '').trim() : '',
                                requestDate: pick('REQUEST-DATE'),
                                requestIp: pick('REQUEST-IP'),
                                requestId: pick('REQUEST-ID')
                              });
                            }
                            """);
            return raw != null ? raw.toString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
