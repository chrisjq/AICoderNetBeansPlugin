package kiwi.ingenuity.netbeans.plugin.aicoder.events;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Bus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;

public final class GlobalPropertyBus implements Bus {

    private static final Logger LOG = Logger.getLogger(GlobalPropertyBus.class.getName());
    private static volatile GlobalPropertyBus instance;

    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GlobalPropertyBus-Dispatcher");
        t.setDaemon(true);
        return t;
    });

    public static GlobalPropertyBus getInstance() {
        GlobalPropertyBus lInstance = GlobalPropertyBus.instance;
        if (lInstance == null) {
            synchronized (GlobalPropertyBus.class) {
                lInstance = GlobalPropertyBus.instance;
                if (lInstance == null) {
                    GlobalPropertyBus.instance = lInstance = new GlobalPropertyBus();
                }
            }
        }
        return lInstance;
    }

    private final CopyOnWriteArrayList<AiPropertyListener> listeners = new CopyOnWriteArrayList<>();

    private GlobalPropertyBus() {
    }

    /**
     * Registers a listener for all events on this bus.
     *
     * <p>
     * <strong>IMPORTANT — session filtering is the listener's
     * responsibility.</strong>
     * This bus broadcasts every event to every registered listener with no
     * per-session filtering. Listeners that are session-scoped (e.g.
     * {@code AiTopComponent}) MUST check the event's session ID before acting
     * on it to avoid cross-session contamination.
     *
     * <p>
     * <strong>IMPORTANT — listeners MUST NOT block.</strong> Events are
     * dispatched on a single background thread
     * ({@code GlobalPropertyBus-Dispatcher}). A blocking listener (network
     * call, file I/O, long computation) stalls delivery for all subsequent
     * events. Offload any slow work to a separate thread from within the
     * listener.
     */
    public void addListener(AiPropertyListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AiPropertyListener listener) {
        listeners.remove(listener);
    }

    public void fire(AiPropertyEvent event) {
        if (listeners.isEmpty()) {
            return;
        }
        EXECUTOR.execute(() -> {
            for (AiPropertyListener l : listeners) {
                try {
                    l.onPropertyChanged(event);
                }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Listener error in GlobalPropertyBus", e);
                }
            }
        });
    }
}
