package by.gdev.alert.job.notification.service.ai.parser.youdo;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Опционально: жёсткая правка CSS модалки (было под Camoufox). По умолчанию выключено — на Chromium ломает разметку YouDo.
 */
@Slf4j
@Component
public class YouDoModalLayoutFix {

    @Value("${parser.youdo.modal-layout-fix:false}")
    private boolean enabled;

    public void apply(Page page) {
        if (!enabled) {
            return;
        }
        if (page == null || page.isClosed()) {
            return;
        }
        try {
            Integer viewportWidth = page.viewportSize() != null ? page.viewportSize().width : null;
            if (viewportWidth == null || viewportWidth < 1200) {
                page.setViewportSize(1366, 900);
                log.info("YOUDO layout: viewport → 1366×900");
            }
        } catch (Exception e) {
            log.debug("YOUDO layout: viewport: {}", e.getMessage());
        }
        try {
            Object result = page.evaluate(
                    """
                            () => {
                              const dialog = document.querySelector('[role="dialog"]');
                              if (!dialog) {
                                return JSON.stringify({ ok: false, reason: 'no-dialog' });
                              }
                              dialog.style.setProperty('max-height', 'min(92vh, 780px)', 'important');
                              dialog.style.setProperty('overflow-y', 'auto', 'important');
                              dialog.style.setProperty('overflow-x', 'hidden', 'important');

                              const kaleidoscope = !!document.querySelector(
                                '.AdvancedCaptcha_kaleidoscope, .AdvancedCaptcha-KaleidoscopeCanvas, .CaptchaSlider');

                              const fixChain = (el) => {
                                let n = el;
                                for (let i = 0; i < 10 && n && n !== document.body; i++) {
                                  n.style.setProperty('overflow', 'visible', 'important');
                                  n.style.setProperty('max-height', 'none', 'important');
                                  if (n === dialog) break;
                                  n = n.parentElement;
                                }
                              };

                              let advanced = 0;
                              let checkbox = 0;
                              for (const iframe of document.querySelectorAll('iframe[src*="smartcaptcha"]')) {
                                fixChain(iframe);
                                const testId = iframe.getAttribute('data-testid') || '';
                                const src = iframe.src || '';
                                const isAdvanced = testId === 'advanced-iframe' || src.includes('/advanced.');
                                if (isAdvanced) {
                                  advanced++;
                                  if (!kaleidoscope) {
                                    iframe.style.setProperty('width', '1px', 'important');
                                    iframe.style.setProperty('height', '1px', 'important');
                                    iframe.style.setProperty('opacity', '0', 'important');
                                    iframe.style.setProperty('pointer-events', 'none', 'important');
                                    iframe.style.setProperty('position', 'absolute', 'important');
                                    iframe.style.setProperty('left', '-9999px', 'important');
                                  } else {
                                    iframe.style.setProperty('position', 'relative', 'important');
                                    iframe.style.setProperty('left', '0', 'important');
                                    iframe.style.setProperty('top', '0', 'important');
                                    iframe.style.setProperty('width', 'min(100%, 360px)', 'important');
                                    iframe.style.setProperty('height', '360px', 'important');
                                    iframe.style.setProperty('display', 'block', 'important');
                                    iframe.style.setProperty('opacity', '1', 'important');
                                  }
                                } else {
                                  checkbox++;
                                  iframe.style.setProperty('position', 'relative', 'important');
                                  iframe.style.setProperty('left', '0', 'important');
                                  iframe.style.setProperty('top', '0', 'important');
                                  iframe.style.setProperty('transform', 'none', 'important');
                                  iframe.style.setProperty('visibility', 'visible', 'important');
                                  iframe.style.setProperty('display', 'block', 'important');
                                  iframe.style.setProperty('width', '320px', 'important');
                                  iframe.style.setProperty('height', '72px', 'important');
                                  iframe.style.setProperty('opacity', '1', 'important');
                                }
                              }

                              for (const root of document.querySelectorAll(
                                '[data-sentry-component="SmartCaptcha"], [data-sitekey], .smart-captcha')) {
                                root.style.setProperty('min-height', '72px', 'important');
                                root.style.setProperty('max-height', kaleidoscope ? '420px' : '120px', 'important');
                                root.style.setProperty('overflow', 'visible', 'important');
                                root.style.setProperty('position', 'relative', 'important');
                                root.style.setProperty('left', '0', 'important');
                                root.style.setProperty('top', '0', 'important');
                              }

                              const host = dialog.querySelector('[data-sentry-component="SmartCaptcha"], .smart-captcha');
                              if (host) {
                                host.scrollIntoView({ block: 'center', behavior: 'auto' });
                              }
                              return JSON.stringify({ ok: true, advanced, checkbox, kaleidoscope });
                            }
                            """);
            log.info("YOUDO layout: fixLoginModalCaptchaDisplay → {}", result);
            page.waitForTimeout(350);
        } catch (Exception e) {
            log.warn("YOUDO layout: fixLoginModalCaptchaDisplay: {}", e.getMessage());
        }
    }
}
