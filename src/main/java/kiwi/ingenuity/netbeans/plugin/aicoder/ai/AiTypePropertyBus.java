package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Bus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;

public final class AiTypePropertyBus implements Bus {

    private static final Logger LOG = Logger.getLogger(AiTypePropertyBus.class.getName());
    private static volatile AiTypePropertyBus instance;

    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AiTypePropertyBus-Dispatcher");
        t.setDaemon(true);
        return t;
    });

    public static AiTypePropertyBus getInstance() {
        AiTypePropertyBus lInstance = AiTypePropertyBus.instance;
        if (lInstance == null) {
            synchronized (AiTypePropertyBus.class) {
                lInstance = AiTypePropertyBus.instance;
                if (lInstance == null) {
                    AiTypePropertyBus.instance = lInstance = new AiTypePropertyBus();
                }
            }
        }
        return lInstance;
    }

    private final ConcurrentHashMap<AiTypeEnum, CopyOnWriteArrayList<AiPropertyListener>> listeners
            = new ConcurrentHashMap<>();

    private AiTypePropertyBus() {
    }

    public void addListener(AiTypeEnum type, AiPropertyListener listener) {
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void removeListener(AiTypeEnum type, AiPropertyListener listener) {
        listeners.computeIfPresent(type, (k, list) -> {
            list.remove(listener);
            return list.isEmpty() ? null : list;
        });
    }

    public void fire(AiTypeEnum type, AiPropertyEvent event) {
        List<AiPropertyListener> list = listeners.get(type);
        if (list == null || list.isEmpty()) {
            return;
        }
        EXECUTOR.execute(() -> {
            for (AiPropertyListener l : list) {
                try {
                    l.onPropertyChanged(event);
                }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Listener error in AiTypePropertyBus", e);
                }
            }
        });
    }
}
