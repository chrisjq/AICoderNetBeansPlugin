package kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

/**
 * The single store of armed idle watchers and the scheduler that fires them.
 * <p>
 * Idle = the target session is between turns. The clock for a target starts when it becomes idle and restarts each time
 * it finishes a turn; whenever it starts a turn all its clocks stop. When a watcher's clock has run for its full
 * {@link IdleWatcher#timeout} it is delivered an {@link IdleWatchEventEnum#IDLE} notice and — for a one-shot watcher —
 * removed; a recurring watcher fires at most once per idle period and re-arms on the next idle transition.
 * <p>
 * All state is guarded by a single lock. Delivery is NEVER called while the lock is held: due watchers are collected
 * under the lock, delivered outside it, then re-locked to apply the result.
 * <p>
 * Test seams: the constructor is package-private and takes a {@link Clock}, the delivery, the probe, and whether a real
 * scheduler thread may be used. With {@code useSchedulerThread == false} no thread is ever created and
 * {@link #checkNow} must be driven by the test directly.
 * <p>
 * Shutdown is explicit and final: {@link #shutdown()} cancels the pending check, stops the scheduler thread, clears
 * every watcher and delivers nothing; afterwards {@link #create} throws {@link IllegalStateException} and the scheduler
 * is never recreated — a safeguard for a disabled module's classloader.
 */
public final class IdleWatcherRegistry {

    public static final Duration MIN_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration MIN_CHECK_INTERVAL = Duration.ofSeconds(10);

    private static final Logger LOG = Logger.getLogger(IdleWatcherRegistry.class.getName());
    private static final String SCHEDULER_THREAD_NAME = "AiCoder-IdleWatcher";
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private static final IdleWatcherRegistry INSTANCE
            = new IdleWatcherRegistry(Clock.systemDefaultZone(), new SessionIdleWatchNotifier(), sessionRegistryProbe(), true);

    private final Object lock = new Object();
    private final Clock clock;
    private final IdleWatchDelivery delivery;
    private final IdleSessionProbe probe;
    private final boolean useSchedulerThread;
    /**
     * All watchers, id → status, in creation order.
     */
    private final Map<String, WatchStatus> watchers = new LinkedHashMap<>();
    /**
     * The recorded idle-since per target session, so a watcher armed against an already-idle target keeps the target's
     * real idle-since rather than resetting it to the arming instant. Cleared when the target goes busy.
     */
    private final Map<String, Instant> idleSinceByTarget = new LinkedHashMap<>();

    private Instant lastCheckAt;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pendingCheck;
    /**
     * Set by {@link #shutdown()}: the registry is permanently closed. Guarded by {@code lock}.
     */
    private boolean shutdown;

    public static IdleWatcherRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Permanently closes the registry. Under the lock: cancels the pending check, shuts the scheduler down
     * ({@code shutdownNow}) and nulls it, clears every watcher and the recorded idle-since map, and sets the shutdown
     * flag. Delivers NO notices — callers must have already resolved watchers (e.g. delivered a TARGET_CLOSED for each
     * open tab) before invoking this.
     * <p>
     * After {@code shutdown()}: {@link #create} throws {@link IllegalStateException}, the remaining public APIs are
     * no-ops, and the scheduler is never recreated even if a late {@code onSessionIdle}/{@code create} arrives — a
     * safeguard so a disabled module's classloader is not pinned by a lingering thread. Idempotent.
     */
    public void shutdown() {
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            cancelPendingCheck();
            shutdownScheduler();
            watchers.clear();
            idleSinceByTarget.clear();
            lastCheckAt = null;
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Idle watcher registry shut down");
            }
        }
    }

    IdleWatcherRegistry(Clock clock, IdleWatchDelivery delivery, IdleSessionProbe probe, boolean useSchedulerThread) {
        this.clock = clock;
        this.delivery = delivery;
        this.probe = probe;
        this.useSchedulerThread = useSchedulerThread;
    }

    /**
     * The production probe, backed by the live {@link SessionRegistry}.
     */
    private static IdleSessionProbe sessionRegistryProbe() {
        return new IdleSessionProbe() {
            @Override
            public boolean isOpen(String sessionId) {
                return sessionId != null && SessionRegistry.get(sessionId) != null;
            }

            @Override
            public boolean isRunning(String sessionId) {
                AbstractAiSession session = SessionRegistry.get(sessionId);
                return session != null && session.getAiSession().isRunning();
            }

            @Override
            public String displayName(String sessionId) {
                AbstractAiSession session = SessionRegistry.get(sessionId);
                if (session == null) {
                    return sessionId;
                }
                String name = session.getSessionName();
                return name != null && !name.isBlank() ? name : sessionId;
            }
        };
    }

    /**
     * Arms a new watcher.
     * <p>
     * Arming while the target is mid-turn leaves the clock stopped until the turn ends. Arming while the target is
     * already idle always waits the FULL timeout from the arming time: {@code idleSince} is kept only for reporting,
     * and {@code dueAt} is computed from {@code max(idleSince, createdAt)}, so a target that has been idle for longer
     * than the timeout does not fire early. Re-arming on {@code onSessionIdle} after a turn is unaffected — that
     * idleSince is later than {@code createdAt}, so the {@code max()} picks it.
     *
     * @throws IllegalArgumentException for a null or blank id, watcher == target, a null or too-short timeout, or a
     * target that is not open
     * @throws IllegalStateException if the registry has been {@link #shutdown()}
     */
    public IdleWatcher create(String watcherSessionId, String targetSessionId, Duration timeout, boolean recurring,
                              boolean interrupt, String note) {
        if (watcherSessionId == null || watcherSessionId.isBlank()) {
            throw new IllegalArgumentException("Watcher session id must not be blank.");
        }
        if (targetSessionId == null || targetSessionId.isBlank()) {
            throw new IllegalArgumentException("Target session id must not be blank.");
        }
        if (watcherSessionId.equals(targetSessionId)) {
            throw new IllegalArgumentException("A session cannot watch itself: " + watcherSessionId);
        }
        if (timeout == null) {
            throw new IllegalArgumentException("Timeout must not be null.");
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0) {
            throw new IllegalArgumentException("Timeout must be at least " + MIN_TIMEOUT.toMinutes() + " minutes.");
        }
        if (!probe.isOpen(targetSessionId)) {
            throw new IllegalArgumentException("Target session is not open: " + targetSessionId);
        }
        IdleWatcher watcher = new IdleWatcher("idle-watch-" + NEXT_ID.getAndIncrement(), watcherSessionId,
                                              targetSessionId, timeout, recurring, interrupt, note, clock.instant());
        synchronized (lock) {
            if (shutdown) {
                throw new IllegalStateException("Idle watcher registry is shut down");
            }
            WatchStatus status = new WatchStatus(watcher, probe.displayName(targetSessionId));
            if (!probe.isRunning(targetSessionId)) {
                Instant idleSince = idleSinceByTarget.get(targetSessionId);
                if (idleSince == null) {
                    idleSince = clock.instant();
                    idleSinceByTarget.put(targetSessionId, idleSince);
                }
                status.idleSince = idleSince;
                // Always wait the full timeout from arming: an already-idle target must not fire early, so dueAt is
                // based on max(idleSince, createdAt) — createdAt for an already-idle target, idleSince (which is later)
                // when arming after a turn.
                Instant clockStart = idleSince.isAfter(watcher.createdAt()) ? idleSince : watcher.createdAt();
                status.dueAt = clockStart.plus(timeout);
            }
            watchers.put(watcher.id(), status);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Idle watcher armed: {0} watches {1} idling for {2} (recurring={3})",
                        new Object[]{watcher.id(), targetSessionId, timeout, recurring});
            }
            reschedule();
        }
        return watcher;
    }

    /**
     * Cancels one watcher. True only when the watcher existed AND was armed by {@code watcherSessionId}.
     */
    public boolean cancel(String watcherSessionId, String watcherId) {
        synchronized (lock) {
            WatchStatus status = watchers.get(watcherId);
            if (status == null || !status.watcher.watcherSessionId().equals(watcherSessionId)) {
                return false;
            }
            watchers.remove(watcherId);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Idle watcher cancelled: {0}", watcherId);
            }
            reschedule();
            return true;
        }
    }

    /**
     * All watchers armed by {@code watcherSessionId}, in creation order.
     */
    public List<IdleWatcherStatus> list(String watcherSessionId) {
        synchronized (lock) {
            List<IdleWatcherStatus> result = new ArrayList<>();
            for (WatchStatus status : watchers.values()) {
                if (status.watcher.watcherSessionId().equals(watcherSessionId)) {
                    result.add(new IdleWatcherStatus(status.watcher, status.idleSince, status.dueAt,
                                                     status.firedThisIdlePeriod));
                }
            }
            return List.copyOf(result);
        }
    }

    /**
     * The target started a turn: its recorded idle-since is forgotten and all clocks of watchers on it stop, and any
     * fired-this-idle-period flag is cleared so the watcher can re-arm after the turn. Idempotent — the AI's turn
     * lifecycle may report busy several times.
     */
    public void onSessionBusy(String sessionId) {
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            idleSinceByTarget.remove(sessionId);
            for (WatchStatus status : watchers.values()) {
                if (status.watcher.targetSessionId().equals(sessionId)) {
                    stopClock(status);
                }
            }
            reschedule();
        }
    }

    /**
     * The target went idle: idle-since is recorded only if not already recorded (this is called repeatedly while idle),
     * and the clocks of this target's watchers are armed unless they are already running or have already fired this
     * idle period. Idempotent.
     */
    public void onSessionIdle(String sessionId) {
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            Instant idleSince = idleSinceByTarget.get(sessionId);
            if (idleSince == null) {
                idleSince = clock.instant();
                idleSinceByTarget.put(sessionId, idleSince);
            }
            for (WatchStatus status : watchers.values()) {
                if (status.watcher.targetSessionId().equals(sessionId)
                        && status.dueAt == null && !status.firedThisIdlePeriod) {
                    status.idleSince = idleSince;
                    status.dueAt = idleSince.plus(status.watcher.timeout());
                }
            }
            reschedule();
        }
    }

    /**
     * A session closed. Watchers it armed are removed silently — there is nowhere to deliver to. Watchers that TARGET
     * it each get a {@link IdleWatchEventEnum#TARGET_CLOSED} notice (which must never read as an idle notice) and are
     * removed. Its recorded idle-since is forgotten.
     */
    public void onSessionClosed(String sessionId) {
        List<WatchStatus> targetClosed = new ArrayList<>();
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            watchers.values().removeIf(status -> {
                if (status.watcher.watcherSessionId().equals(sessionId)) {
                    return true;
                }
                if (status.watcher.targetSessionId().equals(sessionId)) {
                    targetClosed.add(status);
                    return true;
                }
                return false;
            });
            idleSinceByTarget.remove(sessionId);
            reschedule();
        }
        for (WatchStatus status : targetClosed) {
            try {
                delivery.deliver(status.watcher, IdleWatchEventEnum.TARGET_CLOSED, status.idleSince, status.targetName);
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Idle watcher TARGET_CLOSED delivery failed for " + status.watcher.id(), e);
            }
        }
    }

    /**
     * Fires every watcher whose clock has run out. Delivery happens with no lock held; the result is applied under the
     * lock afterwards: a one-shot fires once and is removed, a recurring watcher keeps but its clock stops (it fires at
     * most once per idle period), and anything whose delivery reported failure is removed regardless.
     * <p>
     * The delivery is double-checked against the live state: between collecting the due set and delivering, the target
     * may have started a new turn (its clocks stop, {@code dueAt} becomes null). A notice must never claim idleness the
     * target has already left, so a watcher whose {@code dueAt} is no longer running is skipped, and a recurring
     * watcher whose clock was stopped mid-delivery is left exactly as {@link #onSessionBusy} left it — arming it again
     * next idle — rather than marked fired. Otherwise the busy transition that landed mid-delivery would permanently
     * disarm it (fired-flag set while the clock is stopped, so the next idle transition skips it).
     * <p>
     * Package-private as a test seam; the scheduler calls it.
     */
    void checkNow() {
        Instant now = clock.instant();
        List<DueWatch> due;
        synchronized (lock) {
            lastCheckAt = now;
            due = new ArrayList<>();
            for (WatchStatus status : watchers.values()) {
                if (status.dueAt != null && !status.dueAt.isAfter(now)) {
                    due.add(new DueWatch(status, status.idleSince));
                }
            }
        }
        for (DueWatch dueWatch : due) {
            WatchStatus status = dueWatch.status;
            boolean stillRunning;
            synchronized (lock) {
                stillRunning = watchers.get(status.watcher.id()) == status
                        && status.dueAt != null && !status.dueAt.isAfter(now);
            }
            if (!stillRunning) {
                continue;
            }
            boolean delivered;
            try {
                delivered = delivery.deliver(status.watcher, IdleWatchEventEnum.IDLE, dueWatch.idleSince,
                                             status.targetName);
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Idle watcher delivery failed for " + status.watcher.id(), e);
                delivered = false;
            }
            synchronized (lock) {
                WatchStatus current = watchers.get(status.watcher.id());
                if (current == status) {
                    if (!delivered || !status.watcher.recurring()) {
                        watchers.remove(status.watcher.id());
                    }
                    else if (status.dueAt != null) {
                        // Recurring and its clock is still running: it fired. Stop the clock and mark it fired so it
                        // does not fire again this idle period. If dueAt is null the target started a turn during this
                        // delivery — onSessionBusy already stopped the clock and cleared the fired flag, so this
                        // watcher will re-arm normally on the next idle transition; leave it exactly as it was left.
                        status.idleSince = null;
                        status.dueAt = null;
                        status.firedThisIdlePeriod = true;
                    }
                }
            }
        }
        synchronized (lock) {
            reschedule();
        }
    }

    /**
     * When the next check is due: null when no watcher's clock is running, else max(earliest dueAt, lastCheckAt +
     * MIN_CHECK_INTERVAL). Package-private as a test seam.
     */
    Instant nextCheckAt() {
        synchronized (lock) {
            Instant earliestDue = null;
            for (WatchStatus status : watchers.values()) {
                if (status.dueAt != null && (earliestDue == null || status.dueAt.isBefore(earliestDue))) {
                    earliestDue = status.dueAt;
                }
            }
            if (earliestDue == null) {
                return null;
            }
            if (lastCheckAt == null) {
                return earliestDue;
            }
            Instant minimumGap = lastCheckAt.plus(MIN_CHECK_INTERVAL);
            return earliestDue.isAfter(minimumGap) ? earliestDue : minimumGap;
        }
    }

    /**
     * Package-private test seam: whether the scheduler executor currently exists. False after {@link #shutdown()}.
     */
    boolean hasScheduler() {
        synchronized (lock) {
            return scheduler != null;
        }
    }

    /**
     * Reschedules the next check after any state change: cancels the pending wake-up, computes the next check time, and
     * either re-arms the scheduler or shuts it down when no clock is running. Caller holds the lock.
     */
    private void reschedule() {
        if (shutdown) {
            return;
        }
        if (!useSchedulerThread) {
            return;
        }
        cancelPendingCheck();
        Instant next = nextCheckAt();
        if (next == null) {
            shutdownScheduler();
            return;
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, SCHEDULER_THREAD_NAME);
                thread.setDaemon(true);
                return thread;
            });
        }
        long delayMillis = Math.max(0, Duration.between(clock.instant(), next).toMillis());
        try {
            pendingCheck = scheduler.schedule(this::runCheckSafely, delayMillis, TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException e) {
            LOG.log(Level.FINE, "Idle watcher scheduler already shut down", e);
        }
    }

    private void runCheckSafely() {
        try {
            checkNow();
        }
        catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Idle watcher check failed", e);
        }
    }

    private void cancelPendingCheck() {
        if (pendingCheck != null) {
            pendingCheck.cancel(false);
            pendingCheck = null;
        }
    }

    private void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static void stopClock(WatchStatus status) {
        status.idleSince = null;
        status.dueAt = null;
        status.firedThisIdlePeriod = false;
    }

    private static final class WatchStatus {

        final IdleWatcher watcher;
        /**
         * The target's display name at arming time. Captured once so a TARGET_CLOSED notice still reads the name even
         * after the target leaves the registry (and so IDLE notices are stable against renames).
         */
        final String targetName;
        Instant idleSince;
        Instant dueAt;
        boolean firedThisIdlePeriod;

        WatchStatus(IdleWatcher watcher, String targetName) {
            this.watcher = watcher;
            this.targetName = targetName;
        }
    }

    private static final class DueWatch {

        final WatchStatus status;
        final Instant idleSince;

        DueWatch(WatchStatus status, Instant idleSince) {
            this.status = status;
            this.idleSince = idleSince;
        }
    }
}
