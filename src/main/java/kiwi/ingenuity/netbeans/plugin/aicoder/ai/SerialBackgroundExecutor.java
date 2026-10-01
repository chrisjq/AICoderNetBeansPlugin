package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs submitted tasks one at a time, in submission order, on daemon threads that are never the caller's. It
 * exists so work that must take a process manager's monitor (which {@code start()} holds for up to two
 * minutes) can be requested from the EDT without the EDT ever waiting for that monitor. Each instance
 * serialises only its own tasks; instances share a cached thread pool, so one blocked owner never delays
 * another.
 */
public final class SerialBackgroundExecutor implements Executor {

    private static final Logger LOG = Logger.getLogger(SerialBackgroundExecutor.class.getName());

    private static final ExecutorService SHARED_POOL = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "ai-session-control");
        thread.setDaemon(true);
        return thread;
    });

    private final Executor backing;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean running;

    public SerialBackgroundExecutor() {
        this(SHARED_POOL);
    }

    SerialBackgroundExecutor(Executor backing) {
        this.backing = backing;
    }

    @Override
    public synchronized void execute(Runnable task) {
        queue.add(task);
        if (!running) {
            running = true;
            try {
                backing.execute(this::drain);
            }
            catch (RuntimeException e) {
                running = false;
                queue.remove(task);
                throw e;
            }
        }
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (this) {
                next = queue.poll();
                if (next == null) {
                    running = false;
                    return;
                }
            }
            try {
                next.run();
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Session-control task failed", e);
            }
        }
    }
}
