package by.gdev.alert.job.llm.domain.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Расширенный запрос для AI, содержащий пользователя, модуль и список заказов")
public class AiOrderRequest extends AiOrderRequestBase {

    @Schema(
            description = "Список заказов, которые необходимо обработать",
            implementation = OrderDTO.class
    )
    private List<OrderDTO> orders;
}
