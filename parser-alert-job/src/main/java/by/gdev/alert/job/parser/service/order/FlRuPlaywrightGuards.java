package by.gdev.alert.job.parser.service.order;

import by.gdev.common.model.SiteName;
import by.gdev.common.service.playwright.captcha.CaptchaService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.Locale;

/** Эвристики FL.ru для {@link FLOrderParser} / {@link by.gdev.alert.job.parser.service.category.FLCategoryParser}. */
public final class FlRuPlaywrightGuards {

    public static final String DDOS_RETRY_FAIL_MARKER = "не удалось открыть страницу логина";

    private static final String SUSPICIOUS_IP_ACTIVITY_MARKER = "подозрительная активность";

    private FlRuPlaywrightGuards() {
    }

    public static String ddosRetryFailMessage(SiteName site) {
        return site + ": сработала защита от DDoS — " + DDOS_RETRY_FAIL_MARKER;
    }

    public static boolean isSuspiciousIpActivityInBody(String htmlOrText) {
        if (htmlOrText == null || htmlOrText.isEmpty()) {
            return false;
        }
        return containsSuspiciousIpActivityText(htmlOrText.toLowerCase(Locale.ROOT));
    }

    public static boolean isSuspiciousIpActivityBlock(Page page) {
        try {
            if (isSuspiciousIpActivityInBody(page.content())) {
                return true;
            }
            String text = page.locator("body").innerText();
            return isSuspiciousIpActivityInBody(text);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean containsSuspiciousIpActivityText(String lowerText) {
        if (lowerText == null || lowerText.isEmpty()) {
            return false;
        }
        if (!lowerText.contains(SUSPICIOUS_IP_ACTIVITY_MARKER)) {
            return false;
        }
        return lowerText.contains("ip-адрес") || lowerText.contains("ip адрес")
                || lowerText.contains("с вашего ip");
    }

    public static boolean isAntiDdosOrBotWall(Page page, CaptchaService captchaService) {
        try {
            if (isSuspiciousIpActivityBlock(page)) {
                return true;
            }
            if (captchaService != null && captchaService.isCloudflareChallengePresent(page)) {
                return true;
            }
            if (page.locator("input[name='username']").count() > 0) {
                Locator login = page.locator("input[name='username']").first();
                if (login.isVisible()) {
                    return false;
                }
            }
            String html = page.content().toLowerCase(Locale.ROOT);
            if (html.contains("ddos-guard") || html.contains("ddguard")) {
                return true;
            }
            String text = page.locator("body").innerText().toLowerCase(Locale.ROOT);
            if (text.contains("ddos-guard") || text.contains("distributed denial")) {
                return true;
            }
            if (text.contains("checking your browser") || text.contains("just a moment")) {
                return true;
            }
            if (text.contains("провер") && text.contains("браузер")) {
                return true;
            }
            if ((text.contains("атак") || text.contains("attack"))
                    && (text.contains("защит") || text.contains("protect"))
                    && text.length() < 4000) {
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }
}
