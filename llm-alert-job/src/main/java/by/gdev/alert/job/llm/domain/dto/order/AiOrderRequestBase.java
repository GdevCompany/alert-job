package by.gdev.alert.job.llm.domain.dto.order;

import by.gdev.common.model.NotificationTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Общий контекст AI-запроса: пользователь, модуль, шаблон, промт (без заказа)")
public class AiOrderRequestBase {

    @Schema(
            description = "Информация о пользователе, от имени которого отправлены заказы",
            implementation = AiAppUserDTO.class
    )
    private AiAppUserDTO user;

    @Schema(
            description = "Информация о модуле, который инициировал отправку заказов",
            implementation = AiOrderModulesDTO.class
    )
    private AiOrderModulesDTO module;

    @Schema(
            description = "ID учётных данных пользователя (credential), связанных с модулем",
            example = "101"
    )
    private Long credentialId;

    @Schema(
            description = "ID шаблона, который должен использоваться для генерации ответа",
            example = "55"
    )
    private Long templateId;

    @Schema(
            description = "ID промта, который должен использоваться для генерации ответа",
            example = "55"
    )
    private Long promtId;

    @Schema(description = "Способ уведомления (EMAIL, TELEGRAM и т.д.)")
    private NotificationTypeEnum notificationType;
}
