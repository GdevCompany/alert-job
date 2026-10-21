package by.gdev.alert.job.notification.service.ai.parser.youdo;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Firefox/Camoufox: «Продолжить изменения» при beforeunload во время капчи/логина.
 */
@Slf4j
@Component
public class YouDoBrowserGuards {

    public void installForLogin(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        page.onDialog(dialog -> {
            try {
                String message = dialog.message() != null ? dialog.message() : "";
                log.info("YOUDO dialog: type={} message={}", dialog.type(), truncate(message));
                // beforeunload: dismiss = нельзя закрыть вкладку; accept = закрыть (нужно пользователю и Stop в IDE).
                if ("beforeunload".equalsIgnoreCase(dialog.type())) {
                    dialog.accept();
                    log.info("YOUDO dialog: accept beforeunload — разрешаем закрыть вкладку/браузер");
                } else if (message.contains("изменен")
                        || message.contains("Изменен")
                        || message.toLowerCase().contains("changes")) {
                    dialog.dismiss();
                    log.info("YOUDO dialog: dismiss — остаёмся на странице");
                } else {
                    dialog.accept();
                }
            } catch (Exception e) {
                log.debug("YOUDO dialog handler: {}", e.getMessage());
            }
        });
    }

    /** In-page кнопки, если диалог уже отрисован в DOM (не нативный). */
    public void clickContinueEditingIfPresent(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }
        String[] selectors = {
                "button:has-text('Продолжить изменения')",
                "button:has-text('Продолжить')",
                "[role='dialog'] button:has-text('Продолжить')"
        };
        for (String selector : selectors) {
            try {
                Locator btn = page.locator(selector).first();
                if (btn.count() > 0 && btn.isVisible()) {
                    btn.click(new Locator.ClickOptions().setTimeout(3000).setForce(true));
                    log.info("YOUDO: нажато «Продолжить изменения» ({})", selector);
                    page.waitForTimeout(400);
                    return;
                }
            } catch (Exception ignored) {
                // next
            }
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
