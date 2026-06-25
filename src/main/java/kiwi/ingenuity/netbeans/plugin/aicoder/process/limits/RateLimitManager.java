package kiwi.ingenuity.netbeans.plugin.aicoder.process.limits;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RateLimitManager {

    private static final Logger LOG = Logger.getLogger(RateLimitManager.class.getName());
    private static final long RATE_LIMIT_OFFSET_MS = 10_000;
    private long rateLimitUntilMs = 0;
    private String rateLimitReason = null;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<RateLimitListener> listeners = new ArrayList<>();

    private static final long RESCHEDULE_BUFFER_MS = 250;
    private final ScheduledExecutorService scheduler
            = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rate-limit-scheduler");
                t.setDaemon(true);
                return t;
            });
    private final Set<String> pendingKeys = ConcurrentHashMap.newKeySet();

    public RateLimitManager() {
    }

    public boolean isRateLimited() {
        lock.readLock().lock();
        try {
            return System.currentTimeMillis() < rateLimitUntilMs;
        }
        finally {
            lock.readLock().unlock();
        }
    }

    public long getRetryAfterMs() {
        lock.readLock().lock();
        try {
            long now = System.currentTimeMillis();
            if (now < rateLimitUntilMs) {
                return rateLimitUntilMs - now;
            }
            return 0;
        }
        finally {
            lock.readLock().unlock();
        }
    }

    public String getRateLimitReason() {
        lock.readLock().lock();
        try {
            return rateLimitReason;
        }
        finally {
            lock.readLock().unlock();
        }
    }

    public void setRateLimit(long retryAfterMs) {
        List<RateLimitListener> toNotify;
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            long deadline = now + retryAfterMs + RATE_LIMIT_OFFSET_MS;
            rateLimitUntilMs = Math.max(rateLimitUntilMs, deadline);
            rateLimitReason = "API rate limit: retry after " + retryAfterMs + "ms";
            LOG.log(Level.WARNING, "Rate limit set until {0} ({1}ms from now)",
                    new Object[]{deadline, retryAfterMs});
            toNotify = new ArrayList<>(listeners);
        }
        finally {
            lock.writeLock().unlock();
        }
        for (RateLimitListener listener : toNotify) {
            try {
                listener.onRateLimited(retryAfterMs);
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Error notifying rate limit listener", e);
            }
        }
    }

    public void clearRateLimit() {
        List<RateLimitListener> toNotify = null;
        lock.writeLock().lock();
        try {
            boolean wasActive = rateLimitUntilMs > System.currentTimeMillis();
            rateLimitUntilMs = 0;
            rateLimitReason = null;
            if (wasActive) {
                LOG.info("Clearing rate limit");
                toNotify = new ArrayList<>(listeners);
            }
        }
        finally {
            lock.writeLock().unlock();
        }
        if (toNotify != null) {
            for (RateLimitListener listener : toNotify) {
                try {
                    listener.onRateLimitCleared();
                }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Error notifying rate limit listener", e);
                }
            }
        }
    }

    public void addListener(RateLimitListener listener) {
        lock.writeLock().lock();
        try {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    public void removeListener(RateLimitListener listener) {
        lock.writeLock().lock();
        try {
            listeners.remove(listener);
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Runs {@code task} now if not rate-limited, otherwise schedules it to run
     * once the current rate-limit window elapses. Coalesces by {@code key}: if
     * a task for this key is already pending or in flight, the new request is
     * dropped.
     *
     * @return true if the task was accepted (run or scheduled), false if it was
     * coalesced away or the scheduler has been shut down.
     */
    public boolean submitWhenClear(String key, Runnable task) {
        if (!pendingKeys.add(key)) {
            return false;
        }
        long delay = getRetryAfterMs();
        try {
            if (delay <= 0) {
                scheduler.execute(() -> runWhenClear(key, task));
            }
            else {
                scheduler.schedule(() -> runWhenClear(key, task), delay + RESCHEDULE_BUFFER_MS, TimeUnit.MILLISECONDS);
            }
            return true;
        }
        catch (RejectedExecutionException e) {
            pendingKeys.remove(key);
            return false;
        }
    }

    private void runWhenClear(String key, Runnable task) {
        long remaining = getRetryAfterMs();
        if (remaining > 0) {
            // Rate-limit window was extended after initial submit — reschedule
            try {
                scheduler.schedule(() -> runWhenClear(key, task), remaining + RESCHEDULE_BUFFER_MS, TimeUnit.MILLISECONDS);
            }
            catch (RejectedExecutionException e) {
                pendingKeys.remove(key);
            }
            return;
        }
        try {
            task.run();
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Deferred rate-limit task ''{0}'' failed: {1}",
                    new Object[]{key, e.getMessage()});
        }
        finally {
            pendingKeys.remove(key);
        }
    }

    /**
     * Shuts down the deferred-task scheduler. Called from
     * Installer.uninstalled().
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

}
