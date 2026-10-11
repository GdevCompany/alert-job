package by.gdev.alert.job.notification.controller;

import by.gdev.alert.job.notification.model.dto.*;
import by.gdev.alert.job.notification.model.dto.credential.CredentialValidationRequest;
import by.gdev.alert.job.notification.model.dto.credential.CredentialValidationResult;
import by.gdev.alert.job.notification.service.ai.credential.CredentialValidationService;
import by.gdev.alert.job.notification.service.ai.queue.AiDecisionDedupRegistry;
import by.gdev.alert.job.notification.service.ai.queue.UserQueueManager;
import by.gdev.common.model.HeaderName;
import by.gdev.common.model.SiteName;
import by.gdev.common.util.ReplyTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/notification/api/ai")
@Slf4j
@RequiredArgsConstructor
public class AiNotificationController {
    private final UserQueueManager userQueueManager;
    private final CredentialValidationService credentialValidationService;
    private final AiDecisionDedupRegistry dedupRegistry;

    @PostMapping("/decision")
    public Mono<ResponseEntity<?>> receiveAiDecision(@RequestBody AiNotificationPayload payload) {
        normalizeDecisionReply(payload);
        String key = payload.getOrder() != null ? payload.getOrder().getLink() : null;
        if (!dedupRegistry.tryAcquire(key)) {
            log.warn("DUPLICATE DROPPED at NotificationController: {}", key);
            return Mono.just(ResponseEntity.ok(Map.of("status", "duplicate")));
        }
        log.info("QUEUE: accepted AI decision {}", key);
        //кладем пайлоад в очередь обработки пользователя
        userQueueManager.submit(payload);
        // получаем размер очереди для пользователя
        int userQueueSize = userQueueManager.size(payload.getUser().getUuid());
        return Mono.just(ResponseEntity.accepted().body(Map.of("status", "queued", "queueSize", userQueueSize)));
    }

    @GetMapping("/queue-details")
    public ResponseEntity<?> getQueueDetails(@RequestHeader(HeaderName.UUID_USER_HEADER) String uuid) {
        BlockingQueue<AiNotificationPayload> queue = userQueueManager.getQueue(uuid);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userUuid", uuid != null ? uuid : "unknown");
        response.put("queueSize", queue != null ? queue.size() : 0);

        if (queue == null || queue.isEmpty()) {
            response.put("items", List.of());
            return ResponseEntity.ok(response);
        }

        List<Map<String, Object>> items = queue.stream()
                .map(payload -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    if (payload.getOrder() != null) {
                        item.put("orderLink", payload.getOrder().getLink());
                        String siteName = "unknown";
                        if (payload.getOrder().getSourceSite() != null) {
                            Long sourceId = payload.getOrder().getSourceSite().getSource();
                            if (sourceId != null) {
                                try {
                                    siteName = SiteName.fromId(sourceId).name();
                                } catch (IllegalArgumentException e) {
                                    siteName = "unknown";
                                }
                            }
                        }
                        item.put("site", siteName);
                    }
                    item.put("module", payload.getModule() != null ?
                            payload.getModule().getName() : "unknown");
                    return item;
                })
                .collect(Collectors.toList());

        response.put("items", items);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/queue-size")
    public ResponseEntity<Integer> getQueueSize(@RequestHeader(HeaderName.UUID_USER_HEADER) String uuid) {
        int size = userQueueManager.size(uuid);
        log.info("Queue size for user {}: {}", uuid, size);
        return ResponseEntity.ok(size);
    }

    /**
     * Вызывается из core (Feign) при фоновой проверке учётных данных.
     * boundedElastic — чтобы Playwright не блокировал event-loop WebFlux.
     */
    @PostMapping("/credentials/validate")
    public Mono<CredentialValidationResult> validateCredentials(
            @RequestHeader(HeaderName.UUID_USER_HEADER) String uuid,
            @RequestBody CredentialValidationRequest request) {

        return Mono.fromCallable(() -> credentialValidationService.validate(
                        uuid,
                        request.getSiteId(),
                        request.getLogin(),
                        request.getPassword(),
                        request.getUserEmail()
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void normalizeDecisionReply(AiNotificationPayload payload) {
        if (payload == null || payload.getDecision() == null) {
            return;
        }
        String reply = payload.getDecision().reply();
        if (reply == null) {
            return;
        }
        String normalized = ReplyTextNormalizer.normalize(reply);
        if (!normalized.equals(reply)) {
            payload.setDecision(new AiDecision(normalized));
        }
    }
}
