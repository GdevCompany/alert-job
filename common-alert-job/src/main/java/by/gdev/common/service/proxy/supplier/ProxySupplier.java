package by.gdev.common.service.proxy.supplier;

import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.model.proxy.ProxyState;
import by.gdev.common.service.proxy.ProxySource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ProxySupplier {

    @Value("${parser.proxy.mode:file}")
    private String mode;

    @Value("${parser.proxy.file.path}")
    private String proxyFile;

    @Value("${parser.proxy.url}")
    private String proxyUrl;

    @Autowired
    private FileReader fileReader;

    private final List<ProxyCredentials> proxies = new ArrayList<>(); // общий список
    private final ProxyParser proxyParser = new ProxyParser();
    private int index = 0;

    // ЗАГРУЗКА И ПАРСИНГ
    private synchronized List<String> downloadProxyLines() throws IOException {
        URL url = new URL(proxyUrl);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
            return reader.lines().toList();
        }
    }

    private void parseAndAdd(List<String> lines, ProxySource source) {
        for (String line : lines) {
            ProxyCredentials pc = proxyParser.parse(line);
            if (pc != null) {
                pc.setSource(source); // принудительно ставим нужный источник
                proxies.add(pc);
            }
        }
    }

    @PostConstruct
    public void init() {
        if ("url".equalsIgnoreCase(mode)) {
            downloadProxies();
            log.debug("Прокси загружены по URL: {}", proxyUrl);
        } else {
            readProxies();
            log.debug("Прокси загружены из файла: {}", proxyFile);
        }
    }

    public synchronized List<ProxyCredentials> downloadProxies() {
        try {
            if (proxies.isEmpty()) {
                List<String> lines = downloadProxyLines();
                parseAndAdd(lines, ProxySource.SUPPLIER);
            }
        } catch (IOException e) {
            log.error("Ошибка загрузки прокси по URL {}: {}", proxyUrl, e.getMessage());
            return List.of();
        }
        return proxies;
    }

    public synchronized List<ProxyCredentials> readProxies() {
        if (proxies.isEmpty()) {
            List<String> lines = fileReader.read(proxyFile);
            parseAndAdd(lines, ProxySource.SUPPLIER);
        }
        return proxies;
    }

    // Замена ТОЛЬКО прокси от SUPPLIER
    public synchronized void replaceSupplierProxies(List<ProxyCredentials> newSupplierProxies) {
        if (newSupplierProxies == null || newSupplierProxies.isEmpty()) {
            log.warn("Попытка заменить SUPPLIER-прокси на пустой список – отменено.");
            return;
        }
        // Удаляем все прокси с источником SUPPLIER
        proxies.removeIf(p -> p.getSource() == ProxySource.SUPPLIER);
        // Добавляем новые, убеждаемся, что у них стоит правильный источник
        for (ProxyCredentials p : newSupplierProxies) {
            p.setSource(ProxySource.SUPPLIER);
            proxies.add(p);
        }
        index = 0;
        log.debug("SUPPLIER-прокси заменены. Всего прокси: {}", proxies.size());
    }

    // Добавление/обновление прокси от API
    public synchronized void addApiProxies(List<ProxyCredentials> newApiProxies) {
        if (newApiProxies == null || newApiProxies.isEmpty()) {
            return;
        }
        // Удаляем все старые API-прокси (чтобы заменить на свежие)
        proxies.removeIf(p -> p.getSource() == ProxySource.API);
        // Добавляем новые
        for (ProxyCredentials p : newApiProxies) {
            p.setSource(ProxySource.API);
            proxies.add(p);
        }
        log.debug("API-прокси обновлены. Всего прокси: {}", proxies.size());
    }

    // Получение всего списка
    public synchronized List<ProxyCredentials> getProxies() {
        return new ArrayList<>(proxies);
    }

    /**
     * Прокси, прошедшие проверку (для браузера и назначения).
     * {@link ProxyState#NEW} не включаем — иначе после refresh SUPPLIER в пул попадают непроверенные WebShare.
     */
    public synchronized List<ProxyCredentials> getWorkingProxies() {
        return proxies.stream()
                .filter(p -> p.getState() == ProxyState.ACTIVE
                        || p.getState() == ProxyState.WARMING_UP)
                .collect(Collectors.toList());
    }

    //Загрузка свежих прокси из основного источника (для обновления) ----
    public synchronized List<ProxyCredentials> loadFreshProxies() {
        List<String> lines;
        try {
            if (proxyUrl != null && !proxyUrl.isBlank()) {
                log.debug("Обновление прокси: загрузка из URL {}", proxyUrl);
                lines = downloadProxyLines();
            } else {
                log.debug("Обновление прокси: читаем файл {}", proxyFile);
                lines = fileReader.read(proxyFile);
            }
        } catch (Exception e) {
            log.error("Ошибка загрузки прокси при обновлении: {}", e.getMessage());
            return List.of();
        }

        List<ProxyCredentials> list = new ArrayList<>();
        for (String line : lines) {
            ProxyCredentials pc = proxyParser.parse(line);
            if (pc != null) {
                pc.setSource(ProxySource.SUPPLIER);
                list.add(pc);
            }
        }
        log.debug("Распарсено {} прокси при обновлении", list.size());
        if (list.isEmpty()) {
            log.warn("ПРЕДУПРЕЖДЕНИЕ: Загруженный список прокси пустой!");
        }
        return list;
    }

    // ---- Вспомогательные методы ----
    public synchronized ProxyCredentials get() {
        if (proxies.isEmpty()) {
            readProxies();
        }
        return getNextProxyCredentials();
    }

    private ProxyCredentials getNextProxyCredentials() {
        if (index >= proxies.size() - 1) {
            index = 0;
        }
        ProxyCredentials pc = proxies.get(index);
        index++;
        return pc;
    }
}