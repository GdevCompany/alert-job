package by.gdev.common.service.proxy;


import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.model.proxy.ProxyState;
import by.gdev.common.service.IpGeoService;
import by.gdev.common.service.proxy.supplier.ProxySupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProxyCheckerService {

    /** После любой проверки — не запускать вторую полную сразу (обновление SUPPLIER при старте). */
    private static volatile long lastCheckEndedAtMillis;

    private final ProxySupplier proxySupplier;
    private final IpGeoService ipGeoService;

    @Value("${proxy.checker.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${proxy.checker.attempts:3}")
    private int connectAttempts;

    @Value("${proxy.checker.delay-between-attempts-ms:2000}")
    private int delayBetweenAttemptsMs;

    /** Проверка HTTPS-туннеля (CONNECT), как в браузере — ловит ERR_TUNNEL_CONNECTION_FAILED. */
    @Value("${proxy.checker.tunnel.enabled:true}")
    private boolean tunnelCheckEnabled;

    /** Любой стабильный HTTPS-хост; не привязан к конкретному сайту парсера. */
    @Value("${proxy.checker.tunnel.host:www.google.com}")
    private String tunnelHost;

    @Value("${proxy.checker.tunnel.port:443}")
    private int tunnelPort;

    @Value("${proxy.checker.tunnel-read-timeout-ms:10000}")
    private int tunnelReadTimeoutMs;

    /**
     * Проверка одного прокси. Если {@code countryFilter} = false — страна не определяется.
     */
    public void checkAndUpdateProxy(ProxyCredentials proxy, boolean countryFilter) {
        checkAndUpdateProxy(proxy, countryFilter, false);
    }

    public void checkAndUpdateProxy(ProxyCredentials proxy, boolean countryFilter, boolean verbose) {
        if (countryFilter) {
            updateProxyCountry(proxy);
        }
        boolean available = isProxyAvailable(proxy, verbose);
        switch (proxy.getState()) {
            case NEW -> proxy.setState(available ? ProxyState.WARMING_UP : ProxyState.FAILED);
            case ACTIVE, WARMING_UP -> proxy.setState(available ? ProxyState.ACTIVE : ProxyState.QUARANTINE);
            case QUARANTINE -> proxy.setState(available ? ProxyState.ACTIVE : ProxyState.FAILED);
            case INACTIVE, BANNED, FAILED -> {
                // не трогаем
            }
        }
    }

    private void updateProxyCountry(ProxyCredentials proxy) {
        synchronized (this) {
            String country = ipGeoService.getCountryByIp(proxy.getHost());
            proxy.setCountry(country);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Задержка прервана при определении страны для прокси {}:{}", proxy.getHost(), proxy.getPort());
            }
        }
    }

    private boolean isProxyAvailable(ProxyCredentials proxy, boolean verbose) {
        String lastError = null;
        for (int i = 0; i < connectAttempts; i++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), connectTimeoutMs);
                if (!tunnelCheckEnabled) {
                    return true;
                }
                socket.setSoTimeout(tunnelReadTimeoutMs);
                if (verifyHttpsTunnel(socket, proxy, verbose)) {
                    return true;
                }
                lastError = "HTTPS CONNECT ответ не 200";
                if (verbose) {
                    log.warn(
                            "Попытка {} HTTPS CONNECT через прокси {}:{} к {}:{} не удалась (ответ не 200)",
                            i + 1, proxy.getHost(), proxy.getPort(), tunnelHost, tunnelPort);
                }
            } catch (Exception e) {
                String phase = tunnelCheckEnabled ? "TCP/CONNECT" : "TCP";
                lastError = e.getMessage();
                if (verbose) {
                    log.warn("Попытка {} {} к прокси {}:{} не удалась: {}",
                            i + 1, phase, proxy.getHost(), proxy.getPort(), e.getMessage());
                }
            }
            sleepBetweenAttempts();
        }
        return false;
    }

    private void sleepBetweenAttempts() {
        try {
            Thread.sleep(delayBetweenAttemptsMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** HTTP CONNECT с Proxy-Authorization — проверка, что прокси реально проксирует HTTPS. */
    private boolean verifyHttpsTunnel(Socket socket, ProxyCredentials proxy, boolean verbose) throws IOException {
        String target = tunnelHost + ":" + tunnelPort;
        StringBuilder request = new StringBuilder(256);
        request.append("CONNECT ").append(target).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(target).append("\r\n");
        if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
            String password = proxy.getPassword() != null ? proxy.getPassword() : "";
            String token = Base64.getEncoder().encodeToString(
                    (proxy.getUsername() + ":" + password).getBytes(StandardCharsets.UTF_8));
            request.append("Proxy-Authorization: Basic ").append(token).append("\r\n");
        }
        request.append("User-Agent: gdev-proxy-checker/1.0\r\n");
        request.append("Proxy-Connection: Keep-Alive\r\n");
        request.append("\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String statusLine = readHttpStatusLine(socket.getInputStream());
        if (statusLine == null) {
            return false;
        }
        boolean ok = statusLine.contains(" 200");
        if (!ok && verbose) {
            log.debug("Прокси {}:{} CONNECT {} → {}", proxy.getHost(), proxy.getPort(), target, statusLine.trim());
        }
        return ok;
    }

    private static String readHttpStatusLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.append((char) c);
            }
            if (line.length() > 512) {
                break;
            }
        }
        return line.isEmpty() ? null : line.toString();
    }

    public ProxyCheckSummary checkAllProxies(boolean countryFilterEnabled) {
        return checkProxies(proxySupplier.getProxies(), countryFilterEnabled, false);
    }

    public static boolean isWithinCheckGracePeriod(long graceMs) {
        long ended = lastCheckEndedAtMillis;
        return ended > 0 && System.currentTimeMillis() - ended < graceMs;
    }

    private static void markCheckEnded() {
        lastCheckEndedAtMillis = System.currentTimeMillis();
    }

    public record ProxyCheckSummary(int total, int working, int notWorking) {}

    /**
     * Проверяет переданный список прокси, обновляя их состояние.
     *
     * @param proxies список прокси для проверки
     */
    public ProxyCheckSummary checkProxies(List<ProxyCredentials> proxies, boolean countryFilter) {
        return checkProxies(proxies, countryFilter, false);
    }

    /**
     * @param verbose только первый прогон при старте — WARN/DEBUG по каждому прокси; иначе без построчного вывода
     */
    public ProxyCheckSummary checkProxies(List<ProxyCredentials> proxies, boolean countryFilter, boolean verbose) {
        int total = proxies.size();
        int working = 0;
        int notWorking = 0;
        int processed = 0;
        int logInterval = verbose
                ? Math.max(1, Math.min(20, total / 10))
                : total;

        if (verbose && tunnelCheckEnabled) {
            log.info("Проверка прокси: TCP + HTTPS CONNECT к {}:{}", tunnelHost, tunnelPort);
        } else if (!verbose) {
            log.debug("Проверка прокси (сводка): {} шт., tunnel={} → {}:{}",
                    total, tunnelCheckEnabled, tunnelHost, tunnelPort);
        }

        for (ProxyCredentials proxy : proxies) {
            checkAndUpdateProxy(proxy, countryFilter, verbose);
            switch (proxy.getState()) {
                case ACTIVE, WARMING_UP -> working++;
                default -> notWorking++;
            }
            processed++;
            if (processed % logInterval == 0 || processed == total) {
                if (verbose) {
                    log.info("Прогресс проверки: {}/{} прокси (рабочих: {}, нерабочих: {})",
                            processed, total, working, notWorking);
                }
            }
        }
        if (verbose) {
            log.info("Проверка списка прокси завершена. Статистика: РАБОЧИЕ = {}, НЕ РАБОЧИЕ = {}",
                    working, notWorking);
        }
        markCheckEnded();
        return new ProxyCheckSummary(total, working, notWorking);
    }
}
