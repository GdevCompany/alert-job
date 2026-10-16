package by.gdev.common.service.playwright.manager;

import by.gdev.common.model.SiteName;
import by.gdev.common.model.proxy.ProxyCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class CamoufoxLauncherClient {

    @Value("${camoufox.launcher.url:http://127.0.0.1:8888}")
    private String launcherUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Gson gson = new Gson();

    public LaunchResult launch(ProxyCredentials proxy, SiteName site, boolean headless, String requestUserEmail) {
        Map<String, Object> body = new HashMap<>();
        body.put("site", site.name());
        body.put("headless", headless);
        if (requestUserEmail != null && !requestUserEmail.isBlank()) {
            body.put("userEmail", requestUserEmail.trim());
        }

        if (proxy != null) {
            Map<String, String> p = new HashMap<>();
            p.put("server", "http://" + proxy.getHost() + ":" + proxy.getPort());
            if (proxy.getUsername() != null) p.put("username", proxy.getUsername());
            if (proxy.getPassword() != null) p.put("password", proxy.getPassword());
            body.put("proxy", p);
            body.put("country", proxy.getCountry());
        }

        String json = gson.toJson(body);
        log.info("Запрос к Camoufox-лаунчеру: site={} headless={} proxy={} country={}", site, headless,
                proxy != null ? proxy.getHost() + ":" + proxy.getPort() : "no-proxy",
                proxy != null ? proxy.getCountry() : "-");

        JsonObject r = post("/launch", json);
        if (r.has("error")) {
            throw new RuntimeException("Launcher: " + r.get("error").getAsString());
        }
        return new LaunchResult(r.get("endpoint").getAsString(), r.get("key").getAsString());
    }

    public void release(String key) {
        if (key == null) return;
        try {
            post("/release", gson.toJson(Map.of("key", key)));
            log.info("Camoufox released: {}", key);
        } catch (Exception e) {
            log.warn("Не удалось освободить Camoufox: {}", e.getMessage());
        }
    }

    /** Сообщает лаунчеру, что JVM всё ещё использует этот инстанс. */
    public void heartbeat(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        post("/heartbeat", gson.toJson(Map.of("key", key)));
    }

    /** Список инстансов: state active/starting/stale/dead/idle, флаг in_use. */
    public JsonObject listInstances() {
        return get("/instances");
    }

    /** Убирает неиспользуемые (без heartbeat, мёртвые процессы, refs=0). */
    public JsonObject cleanup(boolean dryRun) {
        return post("/cleanup", gson.toJson(Map.of("dry_run", dryRun)));
    }

    private JsonObject get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(launcherUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Launcher " + resp.statusCode() + ": " + resp.body());
            }
            return gson.fromJson(resp.body(), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException("Launcher error: " + e.getMessage(), e);
        }
    }

    private JsonObject post(String path, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(launcherUrl + path))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Launcher " + resp.statusCode() + ": " + resp.body());
            }
            return gson.fromJson(resp.body(), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException("Launcher error: " + e.getMessage(), e);
        }
    }

    public record LaunchResult(String endpoint, String key) {}
}