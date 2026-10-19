package by.gdev.alert.job.llm.domain.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = """
        Тестовый запрос для POST /ai/api/orders/context/test:
        тот же контекст, что у AiOrderRequest, но один заказ в поле order.
        Текст ответа LLM передаётся отдельным query-параметром llmReply.
        """)
public class AiOrderRequestTest extends AiOrderRequestBase {

    @Schema(
            description = "Один заказ для тестовой обработки (аналог одного элемента из orders)",
            implementation = OrderDTO.class
    )
    private OrderDTO order;
}
