package by.gdev.alert.job.llm.service.aiautoreply.analysis;

import by.gdev.alert.job.llm.constants.LlmConstants;
import by.gdev.alert.job.llm.domain.AiReplyTemplate;
import by.gdev.alert.job.llm.domain.dto.AiDecision;
import by.gdev.alert.job.llm.domain.dto.order.OrderDTO;
import by.gdev.alert.job.llm.domain.dto.promt.AiPromptDto;
import by.gdev.alert.job.llm.service.aiautoreply.promt.AiPromptService;
import by.gdev.alert.job.llm.service.template.AiReplyTemplateService;
import by.gdev.common.util.ReplyTextNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static by.gdev.alert.job.llm.constants.LlmConstants.AUTO_GENERATED_PLACEHOLDER;


/**
 * Сервис анализа заказов с использованием LLM.
 * <p>
 * Отвечает за:
 * <ul>
 *     <li>формирование промта на основе данных заказа;</li>
 *     <li>выбор шаблона письма (пользовательский, модульный или дефолтный);</li>
 *     <li>вызов LLM и парсинг результата в {@link AiDecision};</li>
 *     <li>подстановку сгенерированного текста в HTML‑шаблон;</li>
 *     <li>загрузку шаблонов из classpath;</li>
 *     <li>определение типа проекта (front/back/mobile/dev).</li>
 * </ul>
 * <p>
 * Все вызовы LLM выполняются через отдельный {@link ExecutorService},
 * чтобы избежать блокировки основного потока.
 */
@Service
@Slf4j
public class AiOrderAnalysisService {

    /**
     * Клиент для общения с LLM.
     */
    private final ChatClient chatClient;

    /**
     * JSON‑маппер для десериализации ответа модели.
     */
    private final ObjectMapper mapper;
    //private final TokenBucket tokenBucket;
    //private final TimeRateLimiter timeRateLimiter;
    /**
     * Исполнитель для асинхронных вызовов LLM.
     */
    private final ExecutorService llmExecutor;

    /**
     * Сервис для получения HTML‑шаблонов ответов.
     */
    private final AiReplyTemplateService templateService;

    /**
     * Сервис для получения текстовых промтов.
     */
    private final AiPromptService aiPromptService;

    /**
     * Конструктор, инициализирующий зависимости.
     *
     * @param builder          фабрика ChatClient
     * @param mapper           JSON‑маппер
     * @param llmExecutor      executor для LLM‑вызовов
     * @param templateService  сервис шаблонов ответов
     * @param aiPromptService  сервис текстовых промтов
     */
    @Autowired
    public AiOrderAnalysisService(ChatClient.Builder builder, ObjectMapper mapper,
                                  ExecutorService llmExecutor, AiReplyTemplateService templateService,
                                  AiPromptService aiPromptService) {
        this.chatClient = builder.build();
        this.mapper = mapper;
        this.llmExecutor = llmExecutor;
        this.templateService = templateService;
        this.aiPromptService = aiPromptService;
    }

    /**
     * Основной метод анализа заказа.
     * <p>
     * Логика:
     * <ul>
     *     <li>определяет тип проекта;</li>
     *     <li>загружает HTML‑шаблон;</li>
     *     <li>формирует промт;</li>
     *     <li>выполняет вызов LLM в отдельном потоке;</li>
     *     <li>парсит JSON‑ответ в {@link AiDecision};</li>
     *     <li>подставляет сгенерированный текст в HTML‑шаблон.</li>
     * </ul>
     *
     * @param llmReply если не {@code null} — не вызывать LLM; взять этот текст как готовый ответ
     *                 (без повторной склейки с шаблоном), применить только нормализацию и {@link ReplyLanguageGuard}
     * @return решение AI с текстом ответа
     */
    public AiDecision analyze(OrderDTO order, Long templateId, Long promtId, String uuid, String llmReply) {

        String orderTitle = order.getTitle();
        String orderContent = order.getMessage();
        String orderLink = order.getLink();
        String priceText = order.getPrice() != null ? order.getPrice().getPrice() : "не указана";
        int priceValue = order.getPrice() != null ? order.getPrice().getValue() : 0;
        String siteName = order.getSourceSite() != null ? order.getSourceSite().getSourceName() : null;
        String orderDate = order.getDateTime() != null ? order.getDateTime().toString() : "не указана";
        List<String> keywords = List.of();

        // Загружаем шаблон письма
        String replyTemplate;
        if (templateId != null) {
            AiReplyTemplate t = templateService.getTemplateById(uuid, templateId);
            replyTemplate = t.getText();
        } else {
            AiReplyTemplate t = templateService.getDefaultTemplate();
            replyTemplate = t.getText();
        }

        if (llmReply != null) {
            return editDecision(new AiDecision(llmReply, "", true), order, replyTemplate, true);
        }

        AiPromptDto promptEntity = aiPromptService.getPromptByIdOrDefault(uuid, promtId);
        String promptText = promptEntity.getText();
        String safePromptText = promptText.replace(AUTO_GENERATED_PLACEHOLDER,
                LlmConstants.ESCAPED_AUTO_GENERATED_PLACEHOLDER
        );

        // Формируем prompt
        String prompt;
        if (safePromptText.contains("%s")) {
            prompt = safePromptText.formatted(
                    orderTitle,
                    orderContent,
                    priceText,
                    priceValue,
                    orderLink,
                    orderDate,
                    siteName,
                    keywords
            );
        } else {
            prompt = safePromptText;
        }

        Future<AiDecision> future = llmExecutor.submit(() -> {
            try {
                // Вызов LLM
                String raw = chatClient.prompt()
                        .system(LlmConstants.SYSTEM_PROMT)
                        .user(prompt)
                        .call()
                        .content();

                if (raw == null || raw.isBlank()) {
                    log.error("LLM вернул пустой ответ (raw == null)");
                    return new AiDecision(
                            "LLM вернул пустой ответ",
                            "",
                            false
                    );
                }

                //НЕ РАБОТАЕТ - StringTemplate v4- подходит для мелких шаблонов типа
                /*
                Hello <name>
                <if(condition)> ... <endif>
                 <list:{x|...}>*/
                /*2026-03-30T12:31:45.903+03:00 ERROR 25588 --- [llm] [pool-3-thread-1] b.g.a.j.l.s.a.AiOrderAnalysisService     : Unexpected LLM error
                java.lang.IllegalArgumentException: The template string is not valid.
                at org.springframework.ai.chat.prompt.PromptTemplate.<init>(PromptTemplate.java:86)
                at org.springframework.ai.chat.client.advisor.api.AdvisedRequest.toPrompt(AdvisedRequest.java:171)
                at org.springframework.ai.chat.client.DefaultChatClient$DefaultChatClientRequestSpec$1.aroundCall(DefaultChatClient.java:680)
                at org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain.lambda$nextAroundCall$1(DefaultAroundAdvisorChain.java:98)
                at io.micrometer.observation.Observation.observe(Observation.java:565)
                at org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain.nextAroundCall(DefaultAroundAdvisorChain.java:98)
                at org.springframework.ai.chat.client.DefaultChatClient$DefaultCallResponseSpec.doGetChatResponse(DefaultChatClient.java:493)
                at org.springframework.ai.chat.client.DefaultChatClient$DefaultCallResponseSpec.lambda$doGetObservableChatResponse$1(DefaultChatClient.java:482)
                at io.micrometer.observation.Observation.observe(Observation.java:565)
                at org.springframework.ai.chat.client.DefaultChatClient$DefaultCallResponseSpec.doGetObservableChatResponse(DefaultChatClient.java:482)
                at org.springframework.ai.chat.client.DefaultChatClient$DefaultCallResponseSpec.doSingleWithBeanOutputConverter(DefaultChatClient.java:456)
                at org.springframework.ai.chat.client.DefaultChatClient$DefaultCallResponseSpec.entity(DefaultChatClient.java:451)
                at by.gdev.alert.job.llm.service.aiautoreply.AiOrderAnalysisService.lambda$analyze$0(AiOrderAnalysisService.java:186)
                at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:317)
                at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
                at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
                at java.base/java.lang.Thread.run(Thread.java:1583)
                Caused by: org.stringtemplate.v4.compiler.STException: null
                at org.stringtemplate.v4.compiler.Compiler.reportMessageAndThrowSTException(Compiler.java:224)
                at org.stringtemplate.v4.compiler.Compiler.compile(Compiler.java:154)
                at org.stringtemplate.v4.STGroup.compile(STGroup.java:514)
                at org.stringtemplate.v4.ST.<init>(ST.java:162)
                at org.stringtemplate.v4.ST.<init>(ST.java:156)
                at org.springframework.ai.chat.prompt.PromptTemplate.<init>(PromptTemplate.java:80)
	... 16 common frames omitted

                2026-03-30T12:31:45.905+03:00  INFO 25588 --- [llm] [nio-8033-exec-8] b.g.a.j.l.s.a.AiOrderAnalysisService     : AI ANALYSIS PROMPT FILE: prompts/analysis_default.txt
                2026-03-30T12:31:45.906+03:00  INFO 25588 --- [llm] [nio-8033-exec-8] b.g.a.j.l.s.a.AiOrderAnalysisService     : AI ANALYSIS PROMPT FILE: prompts/analysis_default.txt, estimatedTokens: 1253
                106:17: 'true' came as a complete surprise to me*/
                /*AiDecision decision = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .entity(AiDecision.class);
                 */


                raw = raw.replace("```json", "")
                        .replace("```", "")
                        .trim();
                String json = extractJson(raw);
                AiDecision decision = mapper.readValue(json, AiDecision.class);
                decision.setReply(ReplyTextNormalizer.normalize(decision.getReply()));
                decision.setPrefix(ReplyTextNormalizer.normalize(decision.getPrefix()));
                decision.setValid(true);
                log.info("""
[LLM] AiDecision:
  valid: {}
  prefix: {}
  reply: {}
""",
                        decision.isValid(),
                        decision.getPrefix() != null ? decision.getPrefix() : "<null>",
                        decision.getReply() != null ? decision.getReply() : "<null>"
                );
                return decision;

            } catch (org.springframework.ai.retry.NonTransientAiException e) {
                // Это 429, 400, 50
                log.error("API error: {}", e.getMessage());

                return new AiDecision(
                        "API error: " + e.getMessage(),
                        "",
                        false
                );

            } catch (Exception e) {
                log.error("Unexpected LLM error", e);

                return new AiDecision(
                        "Unexpected LLM error: " + e.getMessage(),
                        "",
                        false
                );
            }
        });

        try {
            return editDecision(future.get(), order, replyTemplate);
        } catch (Exception e) {
            log.error("Executor error", e);
            return new AiDecision(
                    "Executor error: " + e.getMessage(),
                    "", false
            );
        }
    }

    private AiDecision editDecision(AiDecision decision, OrderDTO order, String replyTemplate) {
        return editDecision(decision, order, replyTemplate, false);
    }

    private AiDecision editDecision(
            AiDecision decision, OrderDTO order, String replyTemplate, boolean skipTemplateMerge) {
        if (decision.getReply() != null && !decision.getReply().isEmpty()) {
            String generated = ReplyTextNormalizer.normalize(decision.getReply());
            String beforeGuard = generated;
            generated = ReplyLanguageGuard.alignReplyWithOrderLanguage(
                    generated, order.getTitle(), order.getMessage());
            if (!generated.equals(beforeGuard)) {
                log.warn(
                        "Из ответа LLM убраны чужие CJK-вставки (заказ не на китайском/японском/корейском): {}",
                        order.getLink());
            }
            String finalReply = skipTemplateMerge
                    ? generated
                    : buildFinalReply(replyTemplate, generated, decision.getPrefix());
            decision.setReply(ReplyTextNormalizer.normalize(finalReply));
        }
        return decision;
    }

    /**
     * Вставляет сгенерированный текст после приветствия в шаблоне,
     * и добавляет префикс в начало.
     */
    private String buildFinalReply(String replyTemplate, String generatedText, String prefix) {
        if (replyTemplate == null || replyTemplate.isEmpty()) {
            // Если шаблона нет — возвращаем префикс + сгенерированный текст
            return (prefix != null && !prefix.isEmpty()) ? prefix + " " + generatedText : generatedText;
        }

        String result;
        // Если есть маркер — используем его
        if (replyTemplate.contains(AUTO_GENERATED_PLACEHOLDER)) {
            result = replyTemplate.replace(AUTO_GENERATED_PLACEHOLDER, generatedText);
        } else {
            // Ищем конец приветствия
            int endOfGreeting = -1;
            String[] delimiters = {". ", "?\n", "!\n", ".", "?", "!"};
            for (String delim : delimiters) {
                int idx = replyTemplate.indexOf(delim);
                if (idx != -1) {
                    endOfGreeting = idx + delim.length() - 1;
                    break;
                }
            }

            if (endOfGreeting != -1 && endOfGreeting < replyTemplate.length() - 1) {
                String greeting = replyTemplate.substring(0, endOfGreeting + 1);
                String rest = replyTemplate.substring(endOfGreeting + 1);
                result = greeting + "\n" + generatedText + rest;
            } else {
                result = replyTemplate + "\n" + generatedText;
            }
        }

        if (prefix != null && !prefix.isEmpty()) {
            result = prefix + "\n" + result;
        }

        return result;
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            throw new IllegalArgumentException("No JSON object found in response");
        }
        return raw.substring(start, end + 1);
    }


}