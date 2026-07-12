package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Bus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;

public final class AiTypePropertyBus implements Bus {

    private static final Logger LOG = Logger.getLogger(AiTypePropertyBus.class.getName());
    private static volatile AiTypePropertyBus instance;

    // Bounded so a burst of events can never grow unbounded memory the way an
    // Executors.newSingleThreadExecutor()'s unbounded work queue could. The
    // dispatcher thread is only alive while at least one listener is
    // registered (started when the total listener count goes from 0 to 1,
    // stopped when it drops back to 0) — fire() silently drops the event if
    // no dispatcher is running to consume it, since there are by definition
    // no listeners to notify in that case anyway.
    private static final int QUEUE_CAPACITY = 100;

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

    private final Object dispatcherLock = new Object();
    private BlockingQueue<Runnable> queue;
    private Thread dispatcher;

    private AiTypePropertyBus() {
    }

    public void addListener(AiTypeEnum type, AiPropertyListener listener) {
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);

        if (!listeners.isEmpty()) {
            synchronized (dispatcherLock) {
                startDispatcherIfNeeded();
            }
        }
    }

    public void removeListener(AiTypeEnum type, AiPropertyListener listener) {
        listeners.computeIfPresent(type, (k, list) -> {
            list.remove(listener);
            return list.isEmpty() ? null : list;
        });

        // The emptiness check must happen inside the lock, not before it: if it
        // were checked outside, a concurrent addListener() could re-populate the
        // map between the check and stopDispatcher() below, leaving a
        // registered listener with a dead dispatcher and a null queue — fire()
        // would then silently drop every event until the next add/remove.
        synchronized (dispatcherLock) {
            if (listeners.isEmpty()) {
                stopDispatcher();
            }
        }
    }

    public void fire(AiTypeEnum type, AiPropertyEvent event) {
        List<AiPropertyListener> list = listeners.get(type);
        if (list == null || list.isEmpty()) {
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
            for (AiPropertyListener l : list) {
                try {
                    l.onPropertyChanged(event);
                }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Listener error in AiTypePropertyBus", e);
                }
            }
        });
        if (!accepted) {
            LOG.log(Level.WARNING, "AiTypePropertyBus queue full (capacity {0}); dropping event for {1}",
                    new Object[]{QUEUE_CAPACITY, type});
        }
    }

    // Must be called while holding dispatcherLock.
    private void startDispatcherIfNeeded() {
        if (dispatcher != null) {
            return;
        }
        BlockingQueue<Runnable> newQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        Thread t = new Thread(() -> runDispatchLoop(newQueue), "AiTypePropertyBus-Dispatcher");
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
