package by.gdev.alert.job.llm.service.aiautoreply;

import by.gdev.alert.job.llm.domain.dto.AiDecision;
import by.gdev.alert.job.llm.domain.dto.order.AiOrderRequest;
import by.gdev.alert.job.llm.domain.dto.order.AiOrderRequestBase;
import by.gdev.alert.job.llm.domain.dto.order.AiOrderRequestTest;
import by.gdev.alert.job.llm.domain.dto.order.OrderDTO;
import by.gdev.alert.job.llm.service.aiautoreply.analysis.AiOrderAnalysisService;
import by.gdev.alert.job.llm.service.aiautoreply.sender.DummyReplySender;
import by.gdev.alert.job.llm.service.aiautoreply.sender.NotificationReplySender;
import by.gdev.alert.job.llm.service.aiautoreply.sender.ReplySender;
import by.gdev.alert.job.llm.service.template.LlmUserService;
import by.gdev.common.util.ReplyTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Основной конвейер обработки заказов и отправки автоответов.
 * <p>
 * Отвечает за:
 * <ul>
 *     <li>вызов анализа заказа через {@link AiOrderAnalysisService};</li>
 *     <li>выбор подходящего отправщика ответа ({@link ReplySender});</li>
 *     <li>исключение дубликатов отправки (по ссылке заказа);</li>
 *     <li>обработку заказов как без контекста, так и с контекстом пользователя;</li>
 *     <li>сохранение пользователя в локальной базе через {@link LlmUserService}.</li>
 * </ul>
 * <p>
 * Конвейер поддерживает два режима:
 * <ul>
 *     <li>обработка одиночного заказа без контекста;</li>
 *     <li>обработка расширенного запроса {@link AiOrderRequest} с пользователем, модулем и шаблоном.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AutoReplyPipeline {

    /**
     * Сервис для работы с пользователями LLM‑модуля.
     */
    private final LlmUserService llmUserService;

    /**
     * Сервис анализа заказа и генерации решения AI.
     */
    private final AiOrderAnalysisService analysisService;

    /**
     * Список доступных отправщиков ответов.
     */
    private final List<ReplySender> replySenders;

    /**
     * Множество ссылок заказов, для которых уже был отправлен ответ.
     * Используется для защиты от дубликатов.
     */
    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    /**
     * Возвращает отправщик, который пишет ответ в лог (тестовый режим).
     */
    private ReplySender getDummyReplySender() {
        return replySenders.stream()
                .filter(sender -> sender instanceof DummyReplySender)
                .findFirst()
                .orElse(null);
    }

    /**
     * Возвращает отправщик, который отправляет ответ в Notification‑сервис.
     */
    private ReplySender getNotificationyReplySender() {
        return replySenders.stream()
                .filter(sender -> sender instanceof NotificationReplySender)
                .findFirst()
                .orElse(null);
    }

    /**
     * Обрабатывает расширенный запрос, содержащий:
     * <ul>
     *     <li>список заказов;</li>
     *     <li>данные пользователя;</li>
     *     <li>данные модуля;</li>
     *     <li>ID шаблона.</li>
     * </ul>
     * <p>
     * Реализует защиту от дубликатов: один и тот же заказ (по ссылке)
     * не будет отправлен повторно.
     *
     * @param request расширенный запрос
     */
    public void process(AiOrderRequest request) {
        llmUserService.saveUser(request.getUser());
        if (request.getOrders() == null) {
            return;
        }
        for (OrderDTO order : request.getOrders()) {
            processOne(request, order, null);
        }
    }

    public void process(AiOrderRequestTest request, String llmReply) {
        llmUserService.saveUser(request.getUser());
        processOne(request, request.getOrder(), llmReply);
    }

    private void processOne(AiOrderRequestBase context, OrderDTO order, String llmReply) {
        String uuid = context.getUser().getUuid();
        if (!sent.add(order.getLink())) {
            return;
        }

        AiDecision decision = processItem(
                order, context.getTemplateId(), context.getPromtId(), uuid, llmReply);

        if (!decision.isValid()) {
            return;
        }
        String reply = finalizeReply(decision);
        if (reply == null || reply.trim().isEmpty()) {
            return;
        }

        getDummyReplySender().send(order, reply, decision);
        getNotificationyReplySender().sendToNotificationService(
                order, context.getUser(), context.getModule(),
                decision, context.getCredentialId(), context.getNotificationType());
    }

    /**
     * Выполняет анализ одного заказа с учётом контекста.
     */
    private AiDecision processItem(OrderDTO order, Long templateId, Long promtId, String uuid, String llmReply) {
        if (llmReply == null) {
            log.info("""
[LLM] Начат анализ заказа:
  link: {}
  title: {}
  templateId: {}
  promtId: {}
""",
                    order.getLink(),
                    order.getTitle(),
                    templateId,
                    promtId
            );
        }
        return analysisService.analyze(order, templateId, promtId, uuid, llmReply);
    }

    /**
     * Приводит текст ответа к финальному виду.
     *
     * @param decision решение AI
     * @return очищенный текст ответа или null
     */
    private String finalizeReply(AiDecision decision) {
        if (decision.getReply() == null) return null;
        return ReplyTextNormalizer.normalize(decision.getReply().trim());
    }
}
