package by.gdev.alert.job.notification.service.ai.parser.youdo;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class YoudoTariffChecker {

    /**
     * Проверяет наличие вкладки "Тарифы" в профиле пользователя.
     */
    public boolean isTariffsTabPresent(Page page) {
        try {
            Locator tariffsLink = page.locator("ul.ProfileMenu_navigation__3XYr_ li a:has-text('Тарифы')");
            return tariffsLink.count() > 0;
        } catch (Exception e) {
            log.warn("Ошибка при проверке вкладки 'Тарифы': {}", e.getMessage());
            return false;
        }
    }

    /**
     * Парсит количество оставшихся откликов из блока с тарифом.
     *
     * @param page страница Playwright
     * @return количество оставшихся откликов, или -1 если не найдено
     */
    public int getRemainingResponses(Page page) {
        try {
            // Ищем текст "Осталось X откликов до Y"
            Locator remainingText = page.locator("text=/Осталось\\s+(\\d+)\\s+откликов/i").first();
            if (remainingText.count() == 0) {
                // Пробуем альтернативный селектор
                remainingText = page.locator("text=/Осталось\\s+(\\d+)\\s+отклик/i").first();
            }
            if (remainingText.count() == 0) {
                log.debug("Не найден текст с количеством оставшихся откликов");
                return -1;
            }

            String text = remainingText.textContent();
            if (text == null) {
                return -1;
            }

            // Извлекаем число из текста
            Pattern pattern = Pattern.compile("(\\d+)");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                int count = Integer.parseInt(matcher.group(1));
                log.info("Найдено оставшихся откликов: {}", count);
                return count;
            }
            return -1;
        } catch (Exception e) {
            log.error("Ошибка парсинга количества откликов: {}", e.getMessage());
            return -1;
        }
    }

}