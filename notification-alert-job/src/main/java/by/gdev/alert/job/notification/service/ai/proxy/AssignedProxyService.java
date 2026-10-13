package by.gdev.alert.job.notification.service.ai.proxy;

import by.gdev.alert.job.notification.client.CoreUnifiedClient;
import by.gdev.alert.job.notification.model.dto.ModuleSiteDto;
import by.gdev.common.model.SiteName;
import by.gdev.common.model.proxy.ProxyCredentials;
import by.gdev.common.model.proxy.ProxyState;
import by.gdev.common.service.IpGeoService;
import by.gdev.common.service.proxy.supplier.ProxySupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssignedProxyService {

    private final ProxySupplier proxySupplier;
    private final CoreUnifiedClient coreClient;
    private final IpGeoService ipGeoService;

    // userUuid -> (moduleId -> proxy)
    private final Map<String, Map<Long, ProxyCredentials>> userModuleProxyMap = new ConcurrentHashMap<>();
    private final Map<SiteName, String> siteCountryCache = new ConcurrentHashMap<>();

    /**
     * Перераспределяет прокси для всех пользователей и их модулей.
     * Для каждого модуля определяется страна сайта и подбирается прокси.
     */
    public Mono<Void> reassignProxies() {
        return Mono.fromCallable(() -> {
                    log.debug("Перераспределение прокси для пользователей и модулей");

                    List<String> users = coreClient.getUsersWithAutoReplyEnabled().block();
                    if (users == null || users.isEmpty()) {
                        userModuleProxyMap.clear();
                        log.info("Нет пользователей с автоответом");
                        return null;
                    }

                    Map<String, List<ModuleSiteDto>> userModulesMap = new HashMap<>();
                    for (String userUuid : users) {
                        List<ModuleSiteDto> modules = coreClient.getAutoReplyEnabledModules(userUuid);
                        if (!modules.isEmpty()) {
                            userModulesMap.put(userUuid, modules);
                        }
                    }

                    if (userModulesMap.isEmpty()) {
                        userModuleProxyMap.clear();
                        log.info("У пользователей нет активных модулей с автоответом");
                        return null;
                    }

                    List<ProxyCredentials> workingProxies = proxySupplier.getWorkingProxies();
                    if (workingProxies.isEmpty()) {
                        log.warn("Нет доступных РАБОЧИХ прокси для назначения!");
                        userModuleProxyMap.clear();
                        return null;
                    }

                    Map<String, Map<Long, ProxyCredentials>> newMap = new HashMap<>();

                    for (String userUuid : users) {
                        Map<Long, ProxyCredentials> oldUserMap = userModuleProxyMap.get(userUuid);
                        if (oldUserMap != null) {
                            Map<Long, ProxyCredentials> newUserMap = new HashMap<>();
                            for (Map.Entry<Long, ProxyCredentials> entry : oldUserMap.entrySet()) {
                                ProxyCredentials proxy = entry.getValue();
                                if (proxy.getState() == ProxyState.ACTIVE || proxy.getState() == ProxyState.WARMING_UP) {
                                    newUserMap.put(entry.getKey(), proxy);
                                    workingProxies.remove(proxy);
                                }
                            }
                            if (!newUserMap.isEmpty()) {
                                newMap.put(userUuid, newUserMap);
                            }
                        }
                    }

                    for (String userUuid : users) {
                        List<ModuleSiteDto> modules = userModulesMap.get(userUuid);
                        if (modules == null) continue;

                        Map<Long, ProxyCredentials> userMap = newMap.computeIfAbsent(userUuid, k -> new HashMap<>());

                        for (ModuleSiteDto dto : modules) {
                            Long moduleId = dto.getModuleId();
                            if (userMap.containsKey(moduleId)) continue;

                            SiteName site = SiteName.fromId(dto.getSiteId());
                            String targetCountry = getSiteCountry(site);
                            ProxyCredentials assignedProxy = null;

                            if (targetCountry != null) {
                                List<ProxyCredentials> candidates = workingProxies.stream()
                                        .filter(p -> targetCountry.equalsIgnoreCase(p.getCountry()))
                                        .toList();
                                if (!candidates.isEmpty()) {
                                    assignedProxy = candidates.get(0);
                                    workingProxies.remove(assignedProxy);
                                }
                            }

                            if (assignedProxy == null && !workingProxies.isEmpty()) {
                                assignedProxy = workingProxies.remove(0);
                            }

                            if (assignedProxy == null) {
                                log.warn("Недостаточно прокси для пользователя {} модуля {}", userUuid, moduleId);
                                continue;
                            }

                            userMap.put(moduleId, assignedProxy);
                            log.info("Пользователю {} для модуля {} (сайт {}) назначен прокси {}:{}, страна {}",
                                    userUuid, moduleId, site, assignedProxy.getHost(), assignedProxy.getPort(), assignedProxy.getCountry());
                        }
                    }

                    userModuleProxyMap.clear();
                    userModuleProxyMap.putAll(newMap);
                    log.info("Перераспределение завершено. Назначено прокси для {} пользователей", userModuleProxyMap.size());
                    return null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Возвращает прокси для конкретного пользователя и модуля.
     */
    public ProxyCredentials getProxyForUserAndModule(String userUuid, Long moduleId) {
        ProxyCredentials proxy = lookupActiveProxy(userUuid, moduleId);
        if (proxy != null) {
            return proxy;
        }
        log.warn("Прокси для пользователя {} модуля {} не найден или нерабочий, выполняем перераспределение",
                userUuid, moduleId);
        try {
            reassignProxies().block(Duration.ofMinutes(5));
        } catch (Exception e) {
            log.warn("Перераспределение прокси не завершено: {}", e.getMessage());
        }
        return lookupActiveProxy(userUuid, moduleId);
    }

    /**
     * Закреплённый прокси по сайту (если в задаче другой moduleId, чем в автоответе — см. логи module 1 vs 2).
     */
    public ProxyCredentials getProxyForUserAndSite(String userUuid, SiteName site) {
        Map<Long, ProxyCredentials> userMap = userModuleProxyMap.get(userUuid);
        if (userMap == null || userMap.isEmpty() || site == null) {
            return null;
        }
        try {
            List<ModuleSiteDto> modules = coreClient.getAutoReplyEnabledModules(userUuid);
            if (modules == null) {
                return null;
            }
            for (ModuleSiteDto dto : modules) {
                if (!site.equals(SiteName.fromId(dto.getSiteId()))) {
                    continue;
                }
                ProxyCredentials proxy = lookupActiveProxy(userUuid, dto.getModuleId());
                if (proxy != null) {
                    log.info("Прокси для {} по сайту {} (moduleId {} вместо запрошенного id модуля в payload)",
                            userUuid, site, dto.getModuleId());
                    return proxy;
                }
            }
        } catch (Exception e) {
            log.warn("Не удалось подобрать прокси по сайту {} для {}: {}", site, userUuid, e.getMessage());
        }
        return null;
    }

    private ProxyCredentials lookupActiveProxy(String userUuid, Long moduleId) {
        Map<Long, ProxyCredentials> userMap = userModuleProxyMap.get(userUuid);
        if (userMap == null || moduleId == null) {
            return null;
        }
        ProxyCredentials proxy = userMap.get(moduleId);
        if (proxy != null && isActiveProxyState(proxy.getState())) {
            return proxy;
        }
        return null;
    }

    private static boolean isActiveProxyState(ProxyState state) {
        return state == ProxyState.ACTIVE || state == ProxyState.WARMING_UP;
    }

    /**
     * Подбирает другой рабочий прокси для модуля (текущий исключается).
     * Обновляет закрепление в {@link #userModuleProxyMap}.
     */
    public ProxyCredentials rotateProxyForUserModule(String userUuid, Long moduleId, SiteName site,
                                                     ProxyCredentials current,
                                                     Collection<String> excludedHostPorts) {
        Set<String> excluded = new HashSet<>();
        if (excludedHostPorts != null) {
            excluded.addAll(excludedHostPorts);
        }
        if (current != null) {
            excluded.add(hostPortKey(current));
        }

        List<ProxyCredentials> working = proxySupplier.getWorkingProxies().stream()
                .filter(p -> !excluded.contains(hostPortKey(p)))
                .toList();
        if (working.isEmpty()) {
            log.warn("Нет другого рабочего прокси для пользователя {} модуля {}", userUuid, moduleId);
            return null;
        }

        String targetCountry = getSiteCountry(site);
        ProxyCredentials next = working.stream()
                .filter(p -> targetCountry != null && targetCountry.equalsIgnoreCase(p.getCountry()))
                .findFirst()
                .orElse(working.get(0));

        userModuleProxyMap.computeIfAbsent(userUuid, k -> new ConcurrentHashMap<>()).put(moduleId, next);
        log.info("Прокси для пользователя {} модуля {} (сайт {}) сменён на {}:{}, страна {}",
                userUuid, moduleId, site, next.getHost(), next.getPort(), next.getCountry());
        return next;
    }

    /**
     * Случайный рабочий прокси, отличный от уже пробованных (host:port).
     */
    public ProxyCredentials pickWorkingProxyExcluding(SiteName site, Collection<String> excludedHostPorts) {
        Set<String> excluded = excludedHostPorts != null ? new HashSet<>(excludedHostPorts) : Set.of();
        List<ProxyCredentials> working = proxySupplier.getWorkingProxies().stream()
                .filter(p -> !excluded.contains(hostPortKey(p)))
                .toList();
        if (working.isEmpty()) {
            return null;
        }
        String targetCountry = getSiteCountry(site);
        return working.stream()
                .filter(p -> targetCountry != null && targetCountry.equalsIgnoreCase(p.getCountry()))
                .findFirst()
                .orElse(working.get(0));
    }

    public static String hostPortKey(ProxyCredentials proxy) {
        return proxy.getHost() + ":" + proxy.getPort();
    }

    /**
     * Старый метод для обратной совместимости (использует первый модуль).
     * @deprecated используйте {@link #getProxyForUserAndModule(String, Long)}
     */
    @Deprecated
    public ProxyCredentials getProxyForUser(String userUuid) {
        Map<Long, ProxyCredentials> userMap = userModuleProxyMap.get(userUuid);
        if (userMap != null && !userMap.isEmpty()) {
            return userMap.values().iterator().next();
        }
        return null;
    }

    /**
     * Возвращает страну сайта по его домену (с кэшированием).
     */
    private String getSiteCountry(SiteName site) {
        return siteCountryCache.computeIfAbsent(site, s -> {
            try {
                String domain = s.getDomain();
                InetAddress address = InetAddress.getByName(domain);
                String ip = address.getHostAddress();
                return ipGeoService.getCountryByIp(ip);
            } catch (Exception e) {
                log.warn("Ошибка определения страны для сайта {}: {}", s, e.getMessage());
                return null;
            }
        });
    }
}