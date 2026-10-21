package by.gdev.alert.job.notification.service.ai.credential;

import by.gdev.alert.job.notification.model.AutoreplyMode;
import by.gdev.alert.job.notification.model.dto.AiNotificationPayload;
import by.gdev.alert.job.notification.model.dto.AiOrderModulesDTO;
import by.gdev.alert.job.notification.model.dto.DecryptedCredential;
import by.gdev.alert.job.notification.model.dto.AiAppUserDTO;
import by.gdev.alert.job.notification.model.dto.credential.CredentialValidationResult;
import by.gdev.alert.job.notification.service.ai.parser.AutoreplyParserFactory;
import by.gdev.alert.job.notification.service.ai.parser.AutoreplyPlaywrightParser;
import by.gdev.alert.job.notification.service.ai.queue.step.dto.StepResult;
import by.gdev.alert.job.notification.service.ai.recovery.AutoreplyFailureAction;
import by.gdev.alert.job.notification.service.ai.recovery.AutoreplyFailurePolicy;
import by.gdev.common.model.SiteName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialValidationService {

    private static final ConcurrentHashMap<String, Object> VALIDATION_LOCKS = new ConcurrentHashMap<>();

    private final AutoreplyParserFactory parserFactory;
    private final UserCredentialService userCredentialService;

    public CredentialValidationResult validate(String uuid, Long siteId, String login, String password,
                                               String userEmail) {
        String lockKey = uuid + ":" + siteId + ":" + (login != null ? login.trim().toLowerCase() : "");
        Object lock = VALIDATION_LOCKS.computeIfAbsent(lockKey, k -> new Object());
        synchronized (lock) {
            try {
                return validateInternal(uuid, siteId, login, password, userEmail);
            } finally {
                VALIDATION_LOCKS.remove(lockKey, lock);
            }
        }
    }

    private CredentialValidationResult validateInternal(String uuid, Long siteId, String login, String password,
                                                        String userEmail) {
        SiteName siteName;
        try {
            siteName = SiteName.fromId(siteId);
        } catch (IllegalArgumentException e) {
            log.warn("Неизвестный siteId: {}", siteId);
            return CredentialValidationResult.fail("Неизвестный siteId: " + siteId);
        }

        AutoreplyPlaywrightParser parser;
        try {
            parser = parserFactory.getParser(siteName);
        } catch (IllegalArgumentException e) {
            log.warn("Парсер для сайта {} не найден", siteName);
            return CredentialValidationResult.fail("Парсер для сайта " + siteName + " не найден");
        }

        // Создаём минимальный payload для логина
        AiNotificationPayload payload = new AiNotificationPayload();
        //модуль заглушка
        AiOrderModulesDTO module = new AiOrderModulesDTO();
        module.setId(1L);
        payload.setModule(module);

        //Пользователь, который сделал запрос
        AiAppUserDTO user = new AiAppUserDTO();
        user.setUuid(uuid);
        if (userEmail != null && !userEmail.isBlank()) {
            user.setEmail(userEmail.trim());
        }
        payload.setUser(user);

        DecryptedCredential decryptedCred =  userCredentialService.getUserCredentials(login, password);
        StepResult<Void> result = parser.sendAutoreply(decryptedCred, payload, AutoreplyMode.LOGIN_ONLY);
        if (result.success()) {
            log.info("CREDENTIAL_VALIDATE: OK uuid={} site={} login={}", uuid, siteName, login);
            return CredentialValidationResult.success();
        }
        String code = result.getErrorCode();
        String message = result.getErrorMessage();
        AutoreplyFailureAction action = AutoreplyFailurePolicy.resolve(code, message);
        log.warn("CREDENTIAL_VALIDATE: FAIL uuid={} site={} login={} errorCode={} action={} message={}",
                uuid, siteName, login, code, action, message);
        if (code != null && !code.isBlank()) {
            return CredentialValidationResult.fail(code, message, action.name());
        }
        return CredentialValidationResult.fail(null, message, action.name());
    }
}
