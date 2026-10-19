package by.gdev.common.service.proxy;

import org.springframework.stereotype.Component;

/**
 * До завершения первой проверки прокси при старте приложения парсеры не должны брать прокси из пула.
 */
@Component
public class ProxyCheckGate {

    private volatile boolean initialCheckCompleted;

    /** Вызывается после первого (startup) прогона {@link ProxyCheckerService#checkProxies}. */
    public void completeInitialCheck() {
        initialCheckCompleted = true;
    }

    public boolean mayRunProxyConsumers() {
        return initialCheckCompleted;
    }

    public boolean isInitialCheckCompleted() {
        return initialCheckCompleted;
    }
}
