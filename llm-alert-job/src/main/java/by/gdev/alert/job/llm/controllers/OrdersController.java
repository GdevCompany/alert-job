package by.gdev.alert.job.llm.controllers;

import by.gdev.alert.job.llm.domain.dto.order.AiOrderRequest;
import by.gdev.alert.job.llm.domain.dto.order.AiOrderRequestTest;
import by.gdev.alert.job.llm.service.aiautoreply.AutoReplyPipeline;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ai/api/orders")
@Slf4j
@Tag(name = "Orders", description = "Управление заказами для AI-обработки")
public class OrdersController {

    @Autowired
    AutoReplyPipeline autoReplyPipeline;


    @Operation(
            summary = "Получить заказы с дополнительной информацией (пользователь, модуль, шаблон и т.д.)",
            description = """
                    Принимает расширенный объект AiOrderRequest, содержащий:
                    • список заказов,
                    • данные пользователя,
                    • данные модуля.
                    
                    Используется для более точной обработки заказов с учётом контекста.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "AiOrderRequest успешно принят",
            content = @Content(schema = @Schema(implementation = Map.class))
    )
    @ApiResponse(
            responseCode = "400",
            description = "Ошибка обработки запроса"
    )
    @PostMapping(value = "/context", produces = "application/json")
    public ResponseEntity<Map<String, String>> receiveAiOrderRequest(
            @RequestBody AiOrderRequest request) {
        log.info(
                "AI получил {} заказов от пользователя {} (модуль: {})",
                request.getOrders().size(),
                request.getUser().getEmail(),
                request.getModule().getName()
        );
        autoReplyPipeline.process(request);
        return ResponseEntity.ok(Map.of(
                "message", "AiOrderRequest принят",
                "count", String.valueOf(request.getOrders().size())
        ));
    }

    @Operation(
            summary = "Тест автоответа без вызова LLM",
            description = """
                    Принимает AiOrderRequestTest (контекст как у /context, но один заказ в поле order)
                    и query-параметр llmReply — готовый текст ответа для проверки (как в логе FINAL TEXT /
                    полный вывод ИИ), без повторной подстановки в DEFAULT_TEMPLATE.
                    
                    Применяются ReplyTextNormalizer и ReplyLanguageGuard, отправка в notification как у /context.
                    LLM не вызывается.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Тестовый запрос принят",
            content = @Content(schema = @Schema(implementation = Map.class))
    )
    @ApiResponse(responseCode = "400", description = "Ошибка обработки запроса")
    @PostMapping(value = "/context/test", produces = "application/json")
    public ResponseEntity<Map<String, String>> testReceiveAiOrderRequest(
            @RequestBody AiOrderRequestTest request,
            @Parameter(
                    description = "Полный текст автоответа для регрессионного теста (старый вывод ИИ / FINAL TEXT)",
                    required = true,
                    example = "Реализуем задачу.以及形状参数如方差、协方差。"
            )
            @RequestParam String llmReply) {
        /*log.info(
                "AI TEST: заказ {} от пользователя {} (модуль: {})",
                request.getOrder().getLink(),
                request.getUser().getEmail(),
                request.getModule().getName()
        );*/
        autoReplyPipeline.process(request, llmReply);
        return ResponseEntity.ok(Map.of(
                "message", "AiOrderRequestTest принят",
                "count", "1"
        ));
    }

}