package by.gdev.common.service.playwright.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически спрашивает лаунчер и убирает «зависшие» Camoufox (без heartbeat / мёртвый процесс).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CamoufoxReaperScheduler {

    private final CamoufoxLauncherClient launcherClient;

    @Value("${camoufox.reaper.enabled:true}")
    private boolean reaperEnabled;

    @Value("${camoufox.reaper.log-instances:false}")
    private boolean logInstances;

    @Scheduled(fixedDelayString = "${camoufox.reaper.interval-ms:120000}")
    public void runCleanup() {
        if (!reaperEnabled) {
            return;
        }
        try {
            JsonObject status = launcherClient.listInstances();
            if (logInstances) {
                logInstanceSummary(status);
            }
            JsonObject cleanup = launcherClient.cleanup(false);
            JsonArray reaped = cleanup.getAsJsonArray("reaped");
            if (reaped != null && !reaped.isEmpty()) {
                log.info("Camoufox reaper: убрано неиспользуемых инстансов: {}", reaped.size());
                for (JsonElement el : reaped) {
                    JsonObject row = el.getAsJsonObject();
                    log.info(
                            "  port={} state={} site={} refs={}",
                            row.has("port") ? row.get("port") : "?",
                            row.has("state") ? row.get("state").getAsString() : "?",
                            row.has("site") ? row.get("site").getAsString() : "?",
                            row.has("refs") ? row.get("refs") : "?"
                    );
                }
            }
        } catch (Exception e) {
            log.warn("Camoufox reaper: {}", e.getMessage());
        }
    }

    private void logInstanceSummary(JsonObject status) {
        int count = status.has("count") ? status.get("count").getAsInt() : 0;
        int active = status.has("active") ? status.get("active").getAsInt() : 0;
        log.info("Camoufox launcher: всего={}, в работе={}, простаивают={}", count, active, count - active);
        if (!status.has("instances")) {
            return;
        }
        for (JsonElement el : status.getAsJsonArray("instances")) {
            JsonObject row = el.getAsJsonObject();
            log.info(
                    "  port={} in_use={} state={} refs={} site={} user={}",
                    row.has("port") ? row.get("port") : "?",
                    row.has("in_use") && row.get("in_use").getAsBoolean(),
                    row.has("state") ? row.get("state").getAsString() : "?",
                    row.has("refs") ? row.get("refs") : "?",
                    row.has("site") ? row.get("site").getAsString() : "?",
                    row.has("user_email") ? row.get("user_email").getAsString() : "?"
            );
        }
    }
}
