package by.gdev.alert.job.notification.service.ai.parser.impl;

import by.gdev.alert.job.notification.model.AutoreplyMode;
import by.gdev.alert.job.notification.model.dto.AiNotificationPayload;
import by.gdev.alert.job.notification.model.dto.DecryptedCredential;
import by.gdev.alert.job.notification.service.ai.metrics.errors.AutoreplyErrorTypes;
import by.gdev.alert.job.notification.service.ai.parser.AutoreplyPlaywrightParser;
import by.gdev.alert.job.notification.service.ai.proxy.AssignedProxyService;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.common.model.SiteName;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FreelanceRuAutoreplyParser extends AutoreplyParser implements AutoreplyPlaywrightParser {

    private static final String EXECUTOR_SELECTED_STATUS_TITLE = "Исполнитель выбран";

    /** Плашка {@code div.status-card} с заголовком «Исполнитель выбран» — отклик недоступен. */
    private static final String[] EXECUTOR_SELECTED_STATUS_SELECTORS = {
            "div.status-card:has(div.status-card__title:text-is('" + EXECUTOR_SELECTED_STATUS_TITLE + "'))",
            "div.status-card .status-card__title:text-is('" + EXECUTOR_SELECTED_STATUS_TITLE + "')",
            "div.status-card__title:has-text('" + EXECUTOR_SELECTED_STATUS_TITLE + "')"
    };

    private static final String EXECUTOR_SELECTED_USER_MESSAGE =
            "На freelance.ru по этому заказу уже выбран исполнитель — новые отклики не принимаются. "
                    + "Автоответ не отправлен.";

    @Value("${parser.autoreply.headless.freelance.ru:true}")
    private void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Value("${parser.autoreply.proxy.freelance.ru:false}")
    private void setProxy(boolean proxy) {
        this.proxy = proxy;
    }

    @Value("${parser.autoreply.send.request.freelance.ru:true}")
    private void setOnSendRequest(boolean sendRequest) {
        this.sendRequest = sendRequest;
    }

    public FreelanceRuAutoreplyParser(AssignedProxyService assignedProxyService) {
        super(assignedProxyService);
    }

    @Override
    public SiteName getSiteName() {
        return SiteName.FREELANCERU;
    }

    @Override
    protected String sessionCookieCheckUrl() {
        return "https://freelance.ru";
    }

    @Override
    protected StepResult<Void> login(Page page, AiNotificationPayload payload, DecryptedCredential creds, AutoreplyMode mode) {
        log.info("АВТООТВЕТ: {} -> НАЧАЛО ЛОГИНА, пользователь: {}", getSiteName(), creds.login());

        try {
            safeNavigate(page, "https://freelance.ru/");
            dismissCookieBannerIfPresent(page);
            humanWarmup(page);
            getCurrentManager().humanScroll(page);
            log.info("АВТООТВЕТ: {} -> главная страница загружена, пользователь: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ОТКРЫТЬ ГЛАВНУЮ СТРАНИЦУ, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.ERROR_OPEN_PAGE);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось открыть главную страницу: " + e.getMessage(), captureScreenshot(page));
        }

        if (!clickOrFail(page, "a[href='/auth/login']", "Кнопка 'Вход'")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА 'Вход', пользователь: " + creds.login(),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка 'Вход' не найдена", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> кнопка 'Вход' нажата, пользователь: {}", getSiteName(), creds.login());

        if (!waitOrFail(page, "input[placeholder='логин или email']", "Поле логина")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ПОЛЕ ЛОГИНА, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле логина не найдено", captureScreenshot(page));
        }

        humanInteractionPause(page);
        try {
            getCurrentManager().humanType(page, "input[placeholder='логин или email']", creds.login());
            log.info("АВТООТВЕТ: {} -> логин заполнен: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ЛОГИН, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить логин: " + e.getMessage(), captureScreenshot(page));
        }

        humanInteractionPause(page);
        try {
            getCurrentManager().humanType(page, "input[type='password']", creds.password());
            log.info("АВТООТВЕТ: {} -> пароль заполнен для пользователя: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ПАРОЛЬ, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить пароль: " + e.getMessage(), captureScreenshot(page));
        }

        humanInteractionPause(page);
        if (!clickOrFail(page, "button:has-text('Войти')", "Кнопка 'Войти'")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА 'Войти', пользователь: " + creds.login(),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка 'Войти' не найдена", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> кнопка 'Войти' нажата, пользователь: {}", getSiteName(), creds.login());

        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);
            log.info("АВТООТВЕТ: {} -> страница загружена после входа, пользователь: {}", getSiteName(), creds.login());
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ДОЖДАТЬСЯ ЗАГРУЗКИ ПОСЛЕ ВХОДА, пользователь: "
                            + creds.login() + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.TIMEOUT);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось дождаться загрузки после входа: " + e.getMessage(), captureScreenshot(page));
        }

        if (isLoginErrorPresent(page)) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕВЕРНЫЙ ЛОГИН ИЛИ ПАРОЛЬ, пользователь: " + creds.login(),
                    AutoreplyErrorTypes.LOGIN_FAILED);
            return StepResult.fail(StepType.SEND_AUTOREPLY,
                    "Неверный логин или пароль",
                    captureScreenshot(page));
        }

        log.info("АВТООТВЕТ: {} -> ЛОГИН УСПЕШЕН, пользователь: {}", getSiteName(), creds.login());
        setOtp(payload, null, false);
        return StepResult.ok(StepType.SEND_AUTOREPLY, null);
    }

    private boolean isLoginErrorPresent(Page page) {
        try {
            Locator errorLocator = page.locator("div.fi-err:has-text('Неверный логин или пароль')");
            return errorLocator.count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected StepResult<Void> processAutoReply(Page page, AiNotificationPayload payload, DecryptedCredential creds) {
        String link = payload.getOrder().getLink();
        String login = creds.login();
        log.info("АВТООТВЕТ: {} -> НАЧАЛО ОБРАБОТКИ ЗАКАЗА: {}, пользователь: {}", getSiteName(), link, login);

        try {
            page.navigate(link);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            dismissCookieBannerIfPresent(page);
            humanWarmup(page);
            getCurrentManager().humanScroll(page);
            log.info("АВТООТВЕТ: {} -> страница заказа открыта, пользователь: {}", getSiteName(), login);
            takeScreenshot(page, getSiteName(), payload.getUser().getUuid(), "order_page");
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ОТКРЫТЬ ЗАКАЗ, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.ERROR_OPEN_PAGE);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось открыть заказ: " + e.getMessage(), captureScreenshot(page));
        }

        StepResult<Void> executorSelected = failIfExecutorAlreadySelected(page, login);
        if (executorSelected != null) {
            return executorSelected;
        }

        // Новая вёрстка: карточка #rootReplyCard с textarea#rootReplyText и кнопкой #rootCreateReply.
        // Старая: модалка после клика по «Откликнуться» и скрытый шаблон #replyText.
        String replySelector = "textarea#rootReplyText";
        String sendSelector = "button#rootCreateReply";

        if (!waitForVisibleOrFail(page, replySelector, "Поле ответа (rootReplyText)")) {
            executorSelected = failIfExecutorAlreadySelected(page, login);
            if (executorSelected != null) {
                return executorSelected;
            }
            log.info("АВТООТВЕТ: {} -> форма root не найдена, пробуем старый сценарий с модалкой, пользователь: {}",
                    getSiteName(), login);
            if (!clickOrFail(page,
                    "#rootCreateReply, button.btn.btn--success.btn--lg:has-text('Откликнуться')",
                    "Кнопка 'Откликнуться'")) {
                executorSelected = failIfExecutorAlreadySelected(page, login);
                if (executorSelected != null) {
                    return executorSelected;
                }
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА 'Откликнуться', пользователь: " + login,
                        AutoreplyErrorTypes.BUTTON_NOT_FOUND);
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка 'Откликнуться' не найдена", captureScreenshot(page));
            }
            replySelector = "textarea#replyText[name='TaskReply[text]']";
            sendSelector = "button#createReply.btn.btn--success.btn--sm";
            if (!waitForVisibleOrFail(page, replySelector, "Поле ответа (replyText)")) {
                executorSelected = failIfExecutorAlreadySelected(page, login);
                if (executorSelected != null) {
                    return executorSelected;
                }
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНО ВИДИМОЕ ПОЛЕ ОТВЕТА, пользователь: " + login,
                        AutoreplyErrorTypes.FIELD_NOT_FOUND);
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Поле ответа не найдено или скрыто", captureScreenshot(page));
            }
        } else {
            humanInteractionPause(page);
            try {
                page.locator(replySelector).first().scrollIntoViewIfNeeded();
            } catch (Exception ignored) {
                // scroll optional
            }
        }

        try {
            fillRootReplyMetaIfPresent(page);
            humanInteractionPause(page);
            String replyText = payload.getDecision().reply();
            getCurrentManager().humanType(page, replySelector, replyText);
            log.info("АВТООТВЕТ: {} -> текст ответа вставлен, длина: {}, пользователь: {}", getSiteName(),
                    replyText != null ? replyText.length() : 0, login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ ЗАПОЛНИТЬ ТЕКСТ ОТВЕТА, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.FIELD_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось заполнить текст ответа: " + e.getMessage(), captureScreenshot(page));
        }

        if (!waitForVisibleOrFail(page, sendSelector, "Кнопка отправки отклика")) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> НЕ НАЙДЕНА КНОПКА ОТПРАВКИ, пользователь: " + login,
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка отправки не найдена", captureScreenshot(page));
        }
        log.info("АВТООТВЕТ: {} -> кнопка отправки найдена: {}, пользователь: {}", getSiteName(), sendSelector, login);

        Locator sendBtn = page.locator(sendSelector).locator("visible=true").first();
        try {
            page.waitForCondition(sendBtn::isEnabled,
                    new Page.WaitForConditionOptions().setTimeout(5000));
            log.info("АВТООТВЕТ: {} -> кнопка отправки активна, пользователь: {}", getSiteName(), login);
        } catch (Exception e) {
            report(Level.WARN, log,
                    "АВТООТВЕТ: " + getSiteName() + " -> КНОПКА ОТПРАВКИ НЕАКТИВНА, пользователь: "
                            + login + ", ошибка: " + e.getMessage(),
                    AutoreplyErrorTypes.BUTTON_NOT_FOUND);
            return StepResult.fail(StepType.SEND_AUTOREPLY, "Кнопка отправки неактивна", captureScreenshot(page));
        }

        if (sendRequest) {
            try {
                humanInteractionPause(page);
                getCurrentManager().humanClick(page, sendSelector);
                log.info("АВТООТВЕТ: {} -> ЗАЯВКА УСПЕШНО ОТПРАВЛЕНА, пользователь: {}", getSiteName(), login);
            } catch (Exception e) {
                report(Level.WARN, log,
                        "АВТООТВЕТ: " + getSiteName() + " -> НЕ УДАЛОСЬ НАЖАТЬ КНОПКУ ОТПРАВКИ, пользователь: "
                                + login + ", ошибка: " + e.getMessage(),
                        AutoreplyErrorTypes.BUTTON_NOT_FOUND);
                return StepResult.fail(StepType.SEND_AUTOREPLY, "Не удалось нажать кнопку отправки: " + e.getMessage(), captureScreenshot(page));
            }
        } else {
            log.info("АВТООТВЕТ: {} -> dry-run: форма заполнена, клик по отправке пропущен (sendRequest=false), пользователь: {}",
                    getSiteName(), login);
            return StepResult.ok(StepType.SEND_AUTOREPLY, null);
        }

        page.waitForTimeout(2000);
        log.info("АВТООТВЕТ: {} -> ОТКЛИК УСПЕШНО ЗАВЕРШЁН, пользователь: {}", getSiteName(), login);
        return StepResult.ok(StepType.SEND_AUTOREPLY, null);
    }

    private StepResult<Void> failIfExecutorAlreadySelected(Page page, String login) {
        if (!isExecutorSelectedStatusCardVisible(page)) {
            return null;
        }
        log.warn("АВТООТВЕТ: {} -> исполнитель уже выбран (status-card), url={}, пользователь: {}",
                getSiteName(), page.url(), login);
        report(Level.WARN, log,
                "АВТООТВЕТ: " + getSiteName() + " -> ИСПОЛНИТЕЛЬ УЖЕ ВЫБРАН, пользователь: " + login,
                AutoreplyErrorTypes.ORDER_EXECUTOR_SELECTED);
        return StepResult.fail(StepType.SEND_AUTOREPLY, AutoreplyErrorTypes.ORDER_EXECUTOR_SELECTED,
                EXECUTOR_SELECTED_USER_MESSAGE, captureScreenshot(page));
    }

    private boolean isExecutorSelectedStatusCardVisible(Page page) {
        if (page == null) {
            return false;
        }
        try {
            for (String selector : EXECUTOR_SELECTED_STATUS_SELECTORS) {
                Locator card = page.locator(selector).first();
                if (card.count() > 0 && card.isVisible()) {
                    return true;
                }
            }
            Locator title = page.locator("div.status-card__title")
                    .filter(new Locator.FilterOptions().setHasText(EXECUTOR_SELECTED_STATUS_TITLE))
                    .first();
            if (title.count() > 0 && title.isVisible()) {
                Locator parent = title.locator("xpath=ancestor::div[contains(@class,'status-card')][1]");
                return parent.count() > 0 && parent.first().isVisible();
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> проверка status-card: {}", getSiteName(), e.getMessage());
        }
        return false;
    }

    private void dismissCookieBannerIfPresent(Page page) {
        try {
            Locator accept = page.locator("#cookie-accept");
            if (accept.count() > 0 && accept.first().isVisible()) {
                humanInteractionPause(page);
                getCurrentManager().humanClick(page, "#cookie-accept");
                page.waitForTimeout(400);
                log.debug("АВТООТВЕТ: {} -> закрыт баннер cookies", getSiteName());
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> баннер cookies: {}", getSiteName(), e.getMessage());
        }
    }

    /** Пауза с движением мыши и прокруткой, как на Weblancer / FL.ru. */
    private void humanInteractionPause(Page page) {
        getCurrentManager().humanMouse(page);
        getCurrentManager().humanDelay(page);
        getCurrentManager().humanScroll(page);
    }

    /** Срок и цена в карточке отклика (#rootReplyDays / #rootReplyPrice), если поля пустые. */
    private void fillRootReplyMetaIfPresent(Page page) {
        try {
            Locator days = page.locator("#rootReplyDays");
            if (days.count() > 0 && days.first().isVisible()
                    && (days.first().inputValue() == null || days.first().inputValue().isBlank())) {
                humanInteractionPause(page);
                getCurrentManager().humanType(page, "#rootReplyDays", String.valueOf(defaultDays));
            }
            Locator price = page.locator("#rootReplyPrice");
            if (price.count() > 0 && price.first().isVisible()
                    && (price.first().inputValue() == null || price.first().inputValue().isBlank())) {
                humanInteractionPause(page);
                getCurrentManager().humanType(page, "#rootReplyPrice", String.valueOf(defaultPrice));
            }
        } catch (Exception e) {
            log.debug("АВТООТВЕТ: {} -> не заполнили срок/цену: {}", getSiteName(), e.getMessage());
        }
    }
}