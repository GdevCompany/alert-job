package by.gdev.alert.job.parser.service.category;

import by.gdev.alert.job.parser.domain.db.SiteSourceJob;
import by.gdev.common.model.SiteName;
import by.gdev.alert.job.parser.service.playwright.PlaywrightCategoryParser;
import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.alert.job.parser.service.order.FlRuPlaywrightGuards;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class FLCategoryParser extends PlaywrightCategoryParser implements CategoryParser {

    private static final String BASE_URL = "https://www.fl.ru";

    @Value("${flru.proxy.active:false}")
    private boolean proxyActive;

    @Value("${parser.headless.fl.ru:true}")
    private void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public Map<ParsedCategory, List<ParsedCategory>> parse(SiteSourceJob siteSourceJob) {
        return parseWithRetry(siteSourceJob);
    }

    @Override
    protected Map<ParsedCategory, List<ParsedCategory>> parsePlaywright(SiteSourceJob job) {
        Map<ParsedCategory, List<ParsedCategory>> result = new LinkedHashMap<>();
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;

        try {
            playwright = createPlaywright();
            ProxyCredentials proxy = proxyActive ? getProxyWithRetry(5, 2000) : null;
            browser = createBrowser(playwright, proxy, headless, proxyActive);
            context = createBrowserContext(browser, proxy, proxyActive);
            page = context.newPage();

            // Загружаем главную страницу
            log.debug("Загрузка главной страницы fl.ru");
            page.navigate(BASE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            page.waitForLoadState();
            ensurePageAccessible(page);

            // Ищем все категории верхнего уровня
            Locator categoryItems = page.locator("div.fl-home-page__spec-item > a");
            int catCount = categoryItems.count();
            log.debug("Найдено категорий на {}: {}", getSiteName(), catCount);

            for (int i = 0; i < catCount; i++) {
                Locator catLink = categoryItems.nth(i);
                String catName = catLink.textContent().trim();
                String catHref = catLink.getAttribute("href");

                if (catName == null || catName.isEmpty() || catHref == null) {
                    continue;
                }

                // Очищаем название
                catName = catName.replaceAll("\\s+", " ").trim();
                String fullCatUrl = catHref.startsWith("http") ? catHref : BASE_URL + catHref;

                log.debug("Обработка категории: {}", catName);

                // Переходим на страницу категории и получаем подкатегории
                List<ParsedCategory> subcategories = parseSubcategories(page, fullCatUrl, catName);

                // Сохраняем категорию с подкатегориями
                ParsedCategory category = new ParsedCategory(
                        null,
                        catName,
                        null,
                        fullCatUrl
                );

                result.put(category, subcategories);
                log.debug("Добавлена категория: {} с {} подкатегориями", catName, subcategories.size());

                // Возвращаемся на главную страницу
                page.navigate(BASE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                page.waitForLoadState();
                page.waitForTimeout(500);
            }
            log.debug("Успешно распаршено {} категорий с fl.ru", result.size());
        } catch (Exception e) {
            log.error("Ошибка при парсинге категорий fl.ru", e);
            throw new RuntimeException("Не удалось распарсить категории fl.ru", e);
        } finally {
            closeResources(page, context, browser, playwright);
        }
        return result;
    }

    /**
     * Парсит подкатегории на странице категории.
     */
    private List<ParsedCategory> parseSubcategories(Page page, String categoryUrl, String categoryName) {
        List<ParsedCategory> subcategories = new ArrayList<>();

        try {
            // Переходим на страницу категории
            log.debug("Переход на страницу категории: {}", categoryUrl);
            page.navigate(categoryUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30000));

            page.waitForLoadState();

            // Ищем блок с подкатегориями
            Locator subContainer = page.locator("div.fl-home-page__spec-list.fl-home-page__spec-list-truncated");
            if (subContainer.count() == 0) {
                log.debug("Подкатегории не найдены для категории: {}", categoryName);
                return subcategories;
            }

            // Ждём появления подкатегорий
            try {
                page.waitForSelector("div.fl-home-page__spec-list.fl-home-page__spec-list-truncated div.fl-home-page__spec-item",
                        new Page.WaitForSelectorOptions().setTimeout(5000));
            } catch (Exception e) {
                log.debug("Не дождались подкатегорий для категории: {}", categoryName);
                return subcategories;
            }

            // Получаем все подкатегории
            Locator subItems = subContainer.locator("div.fl-home-page__spec-item > a");
            int subCount = subItems.count();
            log.debug("Найдено подкатегорий для '{}': {}", categoryName, subCount);

            for (int i = 0; i < subCount; i++) {
                Locator subLink = subItems.nth(i);
                String subName = subLink.textContent().trim();
                String subHref = subLink.getAttribute("href");

                if (subName == null || subName.isEmpty() || subHref == null) {
                    continue;
                }

                // Очищаем название от иконок и лишних пробелов
                subName = subName.replaceAll("\\s+", " ").trim();
                // Убираем SVG-иконки если есть
                subName = subName.replaceAll("<svg.*?</svg>", "").trim();

                String fullSubUrl = subHref.startsWith("http") ? subHref : BASE_URL + subHref;

                ParsedCategory subcategory = new ParsedCategory(
                        null,
                        subName,
                        null,
                        fullSubUrl
                );

                subcategories.add(subcategory);
                log.debug("Найдена подкатегория: {} (порядок #{})", subName, i + 1);
            }

            log.debug("Всего подкатегорий для '{}': {}", categoryName, subcategories.size());

        } catch (Exception e) {
            log.error("Ошибка при парсинге подкатегорий для категории '{}': {}", categoryName, e.getMessage());
        }

        return subcategories;
    }

    @Override
    public SiteName getSiteName() {
        return SiteName.FLRU;
    }

    private void ensurePageAccessible(Page page) {
        if (FlRuPlaywrightGuards.isAntiDdosOrBotWall(page, null)) {
            throw new RuntimeException(FlRuPlaywrightGuards.ddosRetryFailMessage(getSiteName()));
        }
    }
}