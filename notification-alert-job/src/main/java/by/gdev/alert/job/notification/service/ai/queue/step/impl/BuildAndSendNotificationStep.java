package by.gdev.alert.job.notification.service.ai.queue.step.impl;

import by.gdev.alert.job.notification.model.dto.AiAppUserDTO;
import by.gdev.alert.job.notification.model.dto.AiNotificationPayload;
import by.gdev.alert.job.notification.service.MailService;
import by.gdev.alert.job.notification.service.ai.queue.step.AiStep;
import by.gdev.alert.job.notification.service.ai.queue.step.RetrySupport;
import by.gdev.alert.job.notification.service.ai.metrics.errors.AutoreplyErrorTypes;
import by.gdev.alert.job.notification.service.ai.recovery.AutoreplyProxyFailures;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepType;
import by.gdev.common.model.NotificationType;
import by.gdev.common.model.NotificationTypeEnum;
import by.gdev.common.model.UserNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class BuildAndSendNotificationStep implements AiStep<AiNotificationPayload, StepResult<Void>> {

    private final MailService mailService;
    private final RetrySupport retrySupport;

    @Override
    public StepType type() {
        return StepType.SEND_NOTIFICATION;
    }

    @Override
    public StepResult<Void> execute(AiNotificationPayload payload) {
        log.info("АВТООТВЕТ: {} -> НАЧАЛО ОТПРАВКИ УВЕДОМЛЕНИЯ, пользователь: {}, тип уведомления: {}",
                payload.getModule().getName(), payload.getUser().getEmail(), payload.getNotificationType());

        // Исправленный вызов retry – добавлен StepType.SEND_NOTIFICATION
        return retrySupport.retry(StepType.SEND_NOTIFICATION, 3, 1500, () -> {
            try {
                AiAppUserDTO user = payload.getUser();
                NotificationTypeEnum type = payload.getNotificationType();

                if (type == null || type == NotificationTypeEnum.NONE) {
                    log.info("АВТООТВЕТ: {} -> тип уведомления NONE, пропускаем отправку", payload.getModule().getName());
                    return StepResult.ok(StepType.SEND_NOTIFICATION, null);
                }

                StepResult<?> autoReplyResult = payload.getStepResult();
                boolean hasError = autoReplyResult != null && autoReplyResult.failed();

                if (hasError) {
                    log.warn("АВТООТВЕТ: {} -> отправка письма об ошибке, пользователь: {}", payload.getModule().getName(), user.getEmail());
                    sendErrorNotification(payload, user, autoReplyResult);
                } else {
                    sendSuccessNotification(payload, user);
                }

                log.info("АВТООТВЕТ: {} -> УВЕДОМЛЕНИЕ УСПЕШНО ОТПРАВЛЕНО", payload.getModule().getName());
                return StepResult.ok(StepType.SEND_NOTIFICATION, null);

            } catch (Exception e) {
                log.warn("АВТООТВЕТ: {} -> ОШИБКА ОТПРАВКИ УВЕДОМЛЕНИЯ: {}", payload.getModule().getName(), e.getMessage(), e);
                return StepResult.fail(StepType.SEND_NOTIFICATION, "Ошибка отправки уведомления: " + e.getMessage());
            }
        });
    }

    private void sendSuccessNotification(AiNotificationPayload payload, AiAppUserDTO user) {
        UserNotification n = new UserNotification();
        n.setType(NotificationType.AUTO_REPLY);

        if (NotificationTypeEnum.EMAIL.equals(payload.getNotificationType())) {
            log.info("АВТООТВЕТ: {} -> подготовка EMAIL для: {}", payload.getModule().getName(), user.getEmail());
            String html = buildSuccessEmailTemplate(payload);
            n.setMessage(html);
            n.setToMail(user.getEmail());
            String attachmentContent = buildAttachmentContent(payload);
            mailService.sendMessageWithAttachment(n, "response_ai.txt", attachmentContent.getBytes(StandardCharsets.UTF_8))
                    .subscribe();
            log.info("АВТООТВЕТ: {} -> EMAIL отправлен на {}", payload.getModule().getName(), user.getEmail());

        } else {
            String telegramText = payload.getDecision().reply();
            if (payload.isOtpUsed()) {
                telegramText += "\n\nOTP для входа: " + payload.getOtpValue();
            }
            log.info("АВТООТВЕТ: {} -> TELEGRAM ТЕКСТ: {}", payload.getModule().getName(), telegramText);
            n.setMessage(telegramText);
            n.setToMail(user.getTelegram().toString());
            mailService.sendMessageToTelegram(n);
            log.info("АВТООТВЕТ: {} -> TELEGRAM отправлен на {}", payload.getModule().getName(), user.getTelegram());
        }
    }

    private void sendErrorNotification(AiNotificationPayload payload, AiAppUserDTO user, StepResult<?> errorResult) {
        String moduleName = payload.getModule().getName();
        String stepName = errorResult.getStepDisplayName();
        String errorMessage = errorResult.getErrorMessage();
        String orderLink = payload.getOrder().getLink();
        String orderTitle = payload.getOrder().getTitle();

        String otpInfoHtml = "";
        String otpInfoText = "";
        if (payload.isOtpUsed() && payload.getOtpValue() != null) {
            otpInfoHtml = "<p><strong>Ожидался OTP:</strong> " + payload.getOtpValue() + "</p>";
            otpInfoText = "Ожидался OTP: " + payload.getOtpValue() + "\n";
        }

        String proxyHintHtml = "";
        String proxyHintText = "";
        if (AutoreplyErrorTypes.PROXY_FAILED.equals(errorResult.getErrorCode())
                || AutoreplyProxyFailures.isProxyRelated(errorResult)) {
            proxyHintHtml = "<p><strong>Вероятная причина:</strong> проблема с прокси "
                    + "(таймаут, обрыв соединения, блок IP на бирже).</p>";
            proxyHintText = "Вероятная причина: проблема с прокси.\n";
        }

        UserNotification n = new UserNotification();
        n.setType(NotificationType.AUTO_REPLY_ERROR);
        String subject = "Ошибка при отправке автоответа на шаге \"" + stepName + "\"";

        // HTML-версия письма
        String htmlBody = String.format("""
    <p>Уважаемый пользователь!</p>
    <p>Не удалось отправить автоответ по причине:<br>
    <strong style="color: red; font-weight: bold;">%s</strong></p>
    <p>Ошибка произошла на шаге: <strong>%s</strong></p>
    <p><strong>Модуль:</strong> %s</p>
    %s
    %s
    <p><strong>Заказ:</strong> %s</p>
    <p><strong>Ссылка:</strong> <a href="%s">%s</a></p>
    <p>Если в письме есть вложение, проверьте скриншот для диагностики.</p>
    <p>С уважением,<br>Система автоответов</p>
    """, errorMessage, stepName, moduleName, otpInfoHtml, proxyHintHtml, orderTitle, orderLink, orderLink);

        if (NotificationTypeEnum.EMAIL.equals(payload.getNotificationType())) {
            log.info("АВТООТВЕТ: {} -> отправка EMAIL об ошибке на: {}", payload.getModule().getName(), user.getEmail());
            n.setMessage(htmlBody);
            n.setToMail(user.getEmail());

            byte[] screenshot = errorResult.getScreenshot();
            if (screenshot != null && screenshot.length > 0) {
                mailService.sendMessageWithAttachment(n, "error_screenshot.png", screenshot).block();
            } else {
                mailService.sendMessage(n).block();
            }
            log.info("АВТООТВЕТ: {} -> EMAIL об ошибке отправлен на {}", payload.getModule().getName(), user.getEmail());

        } else {
            String telegramText = "Модуль: " + moduleName + "\n" +
                    "Ошибка автоответа: " + errorMessage + " (шаг: " + stepName + ")\n" +
                    otpInfoText +
                    proxyHintText +
                    "Заказ: " + orderTitle + "\n" +
                    "Ссылка: " + orderLink;
            log.info("АВТООТВЕТ: {} -> TELEGRAM об ошибке: {}", payload.getModule().getName(), telegramText);
            n.setMessage(telegramText);
            n.setToMail(user.getTelegram().toString());
            mailService.sendMessageToTelegram(n);
            log.info("АВТООТВЕТ: {} -> TELEGRAM об ошибке отправлен на {}", payload.getModule().getName(), user.getTelegram());
        }
    }

    private String buildSuccessEmailTemplate(AiNotificationPayload payload) {
        String replyHtml = payload.getDecision().reply()
                .replace("\n", "<br>");

        String otpBlock = "";
        if (payload.isOtpUsed()) {
            otpBlock = "<p><strong>OTP для входа:</strong> " + payload.getOtpValue() + "</p>";
        }

        return String.format("""
            <div style="font-family: Arial, sans-serif; padding: 12px; border: 1px solid #e5e5e5; border-radius: 8px; background: #fafafa; margin-bottom: 12px;">
                <h3 style="margin: 0 0 10px 0; color: #333;">Автоответ от AI</h3>
                <p style="margin: 4px 0;"><strong>Модуль:</strong> %s</p>
                <p style="margin: 4px 0;"><strong>Название заказа:</strong> %s</p>
                <p style="margin: 4px 0;"><strong>Ссылка:</strong> <a href="%s" style="color: #1a73e8;">%s</a></p>
                %s
                <hr style="margin: 12px 0; border: none; border-top: 1px solid #ddd;">
                <p style="margin: 4px 0;"><strong>Ответ AI:</strong></p>
                <div style="padding: 10px; background: #fff; border: 1px solid #ddd; border-radius: 6px;">%s</div>
                <p style="margin-top: 12px; color: #666; font-size: 12px;">📎 Полный ответ приложен к письму как файл response_ai.txt</p>
            </div>
            """,
                payload.getModule().getName(),
                payload.getOrder().getTitle(),
                payload.getOrder().getLink(),
                payload.getOrder().getLink(),
                otpBlock,
                replyHtml
        );
    }

    private String buildAttachmentContent(AiNotificationPayload payload) {
        return String.format("""
                ======================================
                ОТВЕТ AI НА ЗАКАЗ
                ======================================

                Модуль: %s
                Название заказа: %s
                Ссылка: %s
                Дата: %s

                ======================================
                ТЕКСТ ОТВЕТА
                ======================================

                %s

                ======================================
                КОНЕЦ СООБЩЕНИЯ
                ======================================
                """,
                payload.getModule().getName(),
                payload.getOrder().getTitle(),
                payload.getOrder().getLink(),
                java.time.LocalDateTime.now(),
                payload.getDecision().reply()
        );
    }
}