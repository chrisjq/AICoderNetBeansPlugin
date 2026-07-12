package kiwi.ingenuity.netbeans.plugin.aicoder.events;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Bus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;

public final class GlobalPropertyBus implements Bus {

    private static final Logger LOG = Logger.getLogger(GlobalPropertyBus.class.getName());
    private static volatile GlobalPropertyBus instance;

    // Bounded so a burst of events can never grow unbounded memory the way an
    // Executors.newSingleThreadExecutor()'s unbounded work queue could. The
    // dispatcher thread is only alive while at least one listener is
    // registered (started when the listener count goes from 0 to 1, stopped
    // when it drops back to 0) — fire() silently drops the event if no
    // dispatcher is running to consume it, since there are by definition no
    // listeners to notify in that case anyway.
    private static final int QUEUE_CAPACITY = 100;

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

    private final Object dispatcherLock = new Object();
    private BlockingQueue<Runnable> queue;
    private Thread dispatcher;

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

        if (!listeners.isEmpty()) {
            synchronized (dispatcherLock) {
                startDispatcherIfNeeded();
            }
        }
    }

    public void removeListener(AiPropertyListener listener) {
        listeners.remove(listener);

        // The emptiness check must happen inside the lock, not before it: if it
        // were checked outside, a concurrent addListener() could re-populate the
        // list between the check and stopDispatcher() below, leaving a
        // registered listener with a dead dispatcher and a null queue — fire()
        // would then silently drop every event until the next add/remove.
        synchronized (dispatcherLock) {
            if (listeners.isEmpty()) {
                stopDispatcher();
            }
        }
    }

    public void fire(AiPropertyEvent event) {
        if (listeners.isEmpty()) {
            return;
        }
        BlockingQueue<Runnable> currentQueue;
        synchronized (dispatcherLock) {
            currentQueue = queue;
        }
        if (currentQueue == null) {
            // No dispatcher running (no listeners registered) — nothing to
            // deliver the event to, so drop it.
            return;
        }
        boolean accepted = currentQueue.offer(() -> {
            for (AiPropertyListener l : listeners) {
                try {
                    l.onPropertyChanged(event);
                }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Listener error in GlobalPropertyBus", e);
                }
            }
        });
        if (!accepted) {
            LOG.log(Level.WARNING, "GlobalPropertyBus queue full (capacity {0}); dropping event", QUEUE_CAPACITY);
        }
    }

    // Must be called while holding dispatcherLock.
    private void startDispatcherIfNeeded() {
        if (dispatcher != null) {
            return;
        }
        BlockingQueue<Runnable> newQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        Thread t = new Thread(() -> runDispatchLoop(newQueue), "GlobalPropertyBus-Dispatcher");
        t.setDaemon(true);
        queue = newQueue;
        dispatcher = t;
        t.start();
    }

    // Must be called while holding dispatcherLock.
    private void stopDispatcher() {
        if (dispatcher == null) {
            return;
        }
        dispatcher.interrupt();
        dispatcher = null;
        queue = null;
    }

    private void runDispatchLoop(BlockingQueue<Runnable> ownQueue) {
        try {
            while (true) {
                ownQueue.take().run();
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
