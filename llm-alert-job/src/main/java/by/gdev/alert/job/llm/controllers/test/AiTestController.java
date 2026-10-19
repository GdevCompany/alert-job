package by.gdev.alert.job.llm.controllers.test;

import by.gdev.alert.job.llm.domain.AiReplyTemplate;
import by.gdev.alert.job.llm.domain.dto.AiDecision;
import by.gdev.alert.job.llm.domain.dto.order.OrderDTO;
import by.gdev.alert.job.llm.domain.dto.test.TestAutoreplyRequest;
import by.gdev.alert.job.llm.domain.dto.test.TestAutoreplyResponse;
import by.gdev.alert.job.llm.domain.promt.AiPrompt;
import by.gdev.alert.job.llm.repository.AiReplyTemplateRepository;
import by.gdev.alert.job.llm.repository.promt.AiPromptRepository;
import by.gdev.alert.job.llm.service.aiautoreply.analysis.AiOrderAnalysisService;
import by.gdev.common.model.HeaderName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

@Slf4j
@RestController
@RequestMapping("/api/ai/test")
@RequiredArgsConstructor
@Tag(name = "AI Тестирование автоответов", description = "Тестирование конфигурации автоответов без реальных заказов")
public class AiTestController {

    private final AiOrderAnalysisService analysisService;
    private final AiPromptRepository promptRepository;
    private final AiReplyTemplateRepository templateRepository;

    @PostMapping("/autoreply")
    @Operation(summary = "Тестирование автоответа", description = "Проверяет, как текущий шаблон + промт отработают на тестовом заказе")
    public ResponseEntity<TestAutoreplyResponse> testAutoreply(
            @Parameter(hidden = true)
            @RequestHeader(HeaderName.UUID_USER_HEADER) String uuid,
            @Valid @RequestBody TestAutoreplyRequest request) {

        log.info("АВТООТВЕТ: TEST -> получен запрос на тестирование, uuid={}, templateId={}, promptId={}",
                uuid, request.getTemplateId(), request.getPromptId());

        // ПРОВЕРКА СУЩЕСТВОВАНИЯ ПРОМТА
        AiPrompt prompt = promptRepository.findById(request.getPromptId())
                .orElse(null);

        if (prompt == null) {
            log.warn("АВТООТВЕТ: TEST -> ПРОМТ С ID {} НЕ НАЙДЕН", request.getPromptId());
            return ResponseEntity
                    .badRequest()
                    .body(TestAutoreplyResponse.builder()
                            .success(false)
                            .error("Промт с ID " + request.getPromptId() + " не найден")
                            .build());
        }

        // ПРОВЕРКА СУЩЕСТВОВАНИЯ ШАБЛОНА
        AiReplyTemplate template = templateRepository.findById(request.getTemplateId())
                .orElse(null);

        if (template == null) {
            log.warn("АВТООТВЕТ: TEST -> ШАБЛОН С ID {} НЕ НАЙДЕН", request.getTemplateId());
            return ResponseEntity
                    .badRequest()
                    .body(TestAutoreplyResponse.builder()
                            .success(false)
                            .error("Шаблон с ID " + request.getTemplateId() + " не найден")
                            .build());
        }

        log.info("АВТООТВЕТ: TEST -> промт и шаблон найдены, промт={}, шаблон={}",
                prompt.getId(), template.getId());

        try {
            // Формируем тестовый заказ
            OrderDTO testOrder = buildTestOrder(request);

            // Вызываем анализ
            AiDecision decision = analysisService.analyze(
                    testOrder,
                    request.getTemplateId(),
                    request.getPromptId(),
                    uuid,
                    null
            );

            TestAutoreplyResponse response = TestAutoreplyResponse.builder()
                    .success(true)
                    .prefix(decision.getPrefix())
                    .reply(decision.getReply())
                    .testOrder(testOrder)
                    .build();

            log.info("АВТООТВЕТ: TEST -> тест завершён успешно, reply={}", decision.getReply());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("АВТООТВЕТ: TEST -> ОШИБКА тестирования: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(
                    TestAutoreplyResponse.builder()
                            .success(false)
                            .error(e.getMessage())
                            .build()
            );
        }
    }

    /**
     * Строит тестовый заказ из запроса.
     */
    private OrderDTO buildTestOrder(TestAutoreplyRequest request) {
        OrderDTO order = new OrderDTO();
        order.setLink("test://test.by");
        order.setTitle("Тестовый заказ");
        order.setMessage(request.getOrderDescription());
        order.setDateTime(new Date());
        return order;
    }
}