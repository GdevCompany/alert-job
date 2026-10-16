package by.gdev.alert.job.notification.service.ai.parser.impl.flru;

import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import lombok.Getter;

/** Прерывание сценария FL.ru с готовым {@link StepResult} (капча, validate-captcha и т.д.). */
@Getter
public class FlRuStepFailureException extends RuntimeException {

    private final StepResult<Void> stepResult;

    public FlRuStepFailureException(StepResult<Void> stepResult) {
        super(stepResult != null ? stepResult.getErrorMessage() : null);
        this.stepResult = stepResult;
    }
}
