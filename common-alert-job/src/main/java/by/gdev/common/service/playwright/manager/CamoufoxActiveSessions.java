package by.gdev.common.service.playwright.manager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ключи Camoufox, у которых в этом JVM сейчас открыт Playwright-connect.
 * Периодический heartbeat сообщает лаунчеру, что инстанс реально используется.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CamoufoxActiveSessions {

    private final CamoufoxLauncherClient launcherClient;

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    @Value("${camoufox.heartbeat.enabled:true}")
    private boolean heartbeatEnabled;

    public void register(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        keys.add(key);
        if (heartbeatEnabled) {
            launcherClient.heartbeat(key);
        }
    }

    public void unregister(String key) {
        if (key == null) {
            return;
        }
        keys.remove(key);
    }

    @Scheduled(fixedDelayString = "${camoufox.heartbeat.interval-ms:45000}")
    public void sendHeartbeats() {
        if (!heartbeatEnabled || keys.isEmpty()) {
            return;
        }
        for (String key : Set.copyOf(keys)) {
            try {
                launcherClient.heartbeat(key);
            } catch (Exception e) {
                log.debug("Camoufox heartbeat failed for key={}: {}", key, e.getMessage());
            }
        }
    }
}
