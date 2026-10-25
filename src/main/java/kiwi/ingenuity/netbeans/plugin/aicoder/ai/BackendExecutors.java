package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plugin-wide executors shared by backends (spec §3.8). The scheduler only schedules and never runs blocking work;
 * blocking probe I/O runs on the bounded probe pool. Both are recreated lazily after {@link #shutdown()}.
 * <p>
 * The probe pool rejects work when its queue is full ({@code RejectedExecutionException}); submitters must treat a
 * rejection as a failed probe for the current cycle so a poller never waits on work that never started.
 */
public final class BackendExecutors {

    private static final int SCHEDULER_THREADS = 2;
    private static final int PROBE_THREADS = 4;
    private static final int PROBE_QUEUE = 64;

    private static ScheduledThreadPoolExecutor scheduler;
    private static ThreadPoolExecutor probePool;

    private BackendExecutors() {
    }

    public static synchronized ScheduledExecutorService scheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = new ScheduledThreadPoolExecutor(SCHEDULER_THREADS, daemonFactory("aicoder-scheduler-"));
            scheduler.setRemoveOnCancelPolicy(true);
        }
        return scheduler;
    }

    public static synchronized ExecutorService probePool() {
        if (probePool == null || probePool.isShutdown()) {
            probePool = new ThreadPoolExecutor(PROBE_THREADS, PROBE_THREADS, 30, TimeUnit.SECONDS,
                                               new LinkedBlockingQueue<>(PROBE_QUEUE), daemonFactory("aicoder-probe-"),
                                               new ThreadPoolExecutor.AbortPolicy());
            probePool.allowCoreThreadTimeOut(true);
        }
        return probePool;
    }

    /**
     * Stops both executors without waiting; later calls to the getters create new ones.
     */
    public static synchronized void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (probePool != null) {
            probePool.shutdownNow();
        }
    }

    public static synchronized boolean isShutdown() {
        return (scheduler == null || scheduler.isShutdown())
                && (probePool == null || probePool.isShutdown());
    }

    static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
