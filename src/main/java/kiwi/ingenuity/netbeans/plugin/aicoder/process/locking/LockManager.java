package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

public class LockManager {

    private static final Logger LOG = Logger.getLogger(LockManager.class.getName());
    private static volatile LockManager instance;
    // ---- Test seam (package-private; production keeps the defaults) ----
    /**
     * When non-null, used instead of {@link LockTypeEnum#getWaitTimeoutMillis()} as every lock type's acquisition wait,
     * so a test can assert a contended-acquire-fails property in milliseconds rather than waiting out a real timeout
     * (e.g. {@code BUILD_LOCK}'s deliberate 120s). Same pattern as {@code TempFileRegistry.maxAgeMillis}. Null in
     * production.
     */
    static volatile Long waitTimeoutOverrideMillisForTests = null;

    /**
     * Routine acquire/release chatter is gated by the same setting as MCP tool-use logging
     * ({@link PluginSettings#isLogToolUse()}), so the NetBeans log stays quiet during normal operation. Warnings for
     * contention/expiry stay always-on.
     */
    private static void logLockLifecycle(String message, Object... params) {
        if (PluginSettings.isLogToolUse()) {
            LOG.log(Level.INFO, message, params);
        }
    }

    public static LockManager getInstance() {
        LockManager lInstance = LockManager.instance;
        if (lInstance == null) {
            synchronized (LockManager.class) {
                lInstance = LockManager.instance;
                if (lInstance == null) {
                    LockManager.instance = lInstance = new LockManager();
                }
            }
        }
        return lInstance;
    }

    /**
     * Contention message for a failed per-file lock acquisition, shared by every caller (ApplyEditTool, WriteFileTool
     * and the native edit/write hook) so the advice cannot drift between them. A file lock is held from just before a
     * diff is shown until the write completes, so the holder is usually blocked on a USER reviewing that diff — up to
     * {@link TimeoutEnum#USER_APPROVAL_WAIT_MILLIS}. The wording therefore points at the human decision rather than
     * suggesting a retry: retrying achieves nothing while the panel is open, and "try again shortly" has been observed
     * to send AI sessions into sleep-and-retry loops.
     */
    public static String fileLockedMessage(String holder) {
        return "File is locked by " + (holder != null ? "session " + holder : "another in-progress edit")
                + " — that edit is waiting for the USER to review a diff, which can take up to "
                + TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis() / 1000 + "s ("
                + TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.name() + ")."
                + " Retrying cannot succeed until the user decides, so do not sleep and retry in a loop:"
                + " work on other files meanwhile, or report this to the user.";
    }

    private final Map<LockTypeEnum, ResourceLock> globalLocks = new ConcurrentHashMap<>();
    private final Map<String, ResourceLock> fileLocks = new ConcurrentHashMap<>();
    private final Map<String, Set<ResourceLock>> sessionLocks = new ConcurrentHashMap<>();
    private Thread cleanupThread;

    private LockManager() {
        startCleanupThread();
    }

    public void shutdown() {
        Thread t = cleanupThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean acquireLock(String sessionId, LockTypeEnum lockType) {
        return acquireWithWait(lockType, () -> tryAcquireLock(sessionId, lockType));
    }

    private synchronized boolean tryAcquireLock(String sessionId, LockTypeEnum lockType) {
        if (globalLocks.containsKey(lockType)) {
            ResourceLock existing = globalLocks.get(lockType);
            if (existing.getSessionId().equals(sessionId)) {
                if (!existing.isExpired()) {
                    // Nested re-acquire of the caller's own global lock: succeed without touching
                    // either map (no duplicate object is created here), so one outer release later
                    // still ends up holding exactly one mapping. The warning documents the nested
                    // call deliberately; inner/outer releases are NOT refcounted by design.
                    LOG.log(Level.WARNING, "Session {0} re-acquired lock {1} — possible nested tool call", new Object[]{sessionId, lockType});
                    return true;
                }
                // Own lock expired — remove and re-acquire below
                globalLocks.remove(lockType);
                Set<ResourceLock> ownSl = sessionLocks.get(sessionId);
                if (ownSl != null && ownSl.remove(existing) && ownSl.isEmpty()) {
                    sessionLocks.remove(sessionId);
                }
                LOG.log(Level.WARNING, "Own lock {0} expired for session {1}, re-acquiring", new Object[]{lockType, sessionId});
            }
            else if (existing.isExpired()) {
                LOG.log(Level.WARNING, "Force-releasing expired {0}: {1}", new Object[]{lockType, existing});
                globalLocks.remove(lockType);
            }
            else {
                LOG.log(Level.FINE, "Cannot acquire {0} - held by {1}", new Object[]{lockType, existing.getSessionId()});
                return false;
            }
        }

        long timeoutMillis = lockType.getLifetimeMillis();
        ResourceLock lock = new ResourceLock(lockType, sessionId, timeoutMillis);
        globalLocks.put(lockType, lock);
        sessionLocks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(lock);
        logLockLifecycle("Acquired {0} for session {1}", lockType, sessionId);
        return true;
    }

    public boolean acquireFileLock(String sessionId, String filePath) {
        return acquireFileLocks(sessionId, Set.of(filePath));
    }

    public boolean acquireFileLocks(String sessionId, Set<String> filePaths) {
        return acquireWithWait(LockTypeEnum.FILE_WRITE_LOCK,
                () -> tryAcquireFileLocks(sessionId, filePaths));
    }

    private synchronized boolean tryAcquireFileLocks(String sessionId, Set<String> filePaths) {
        for (String filePath : filePaths) {
            // Check exact file lock
            if (fileLocks.containsKey(filePath)) {
                ResourceLock existing = fileLocks.get(filePath);
                if (existing.getSessionId().equals(sessionId)) {
                    continue;
                }
                if (existing.isExpired()) {
                    LOG.log(Level.WARNING, "Force-releasing expired file lock: {0}", existing);
                    fileLocks.remove(filePath);
                    Set<ResourceLock> sl = sessionLocks.get(existing.getSessionId());
                    if (sl != null && sl.remove(existing) && sl.isEmpty()) {
                        sessionLocks.remove(existing.getSessionId());
                    }
                }
                else {
                    LOG.log(Level.FINE, "Cannot acquire file lock for {0} - held by {1}", new Object[]{filePath, existing.getSessionId()});
                    return false;
                }
            }
            // Check if any directory lock covers this file path
            for (Map.Entry<String, ResourceLock> entry : fileLocks.entrySet()) {
                ResourceLock existing = entry.getValue();
                if (existing.getScope() == ResourceLock.LockScope.DIRECTORY
                        && !existing.getSessionId().equals(sessionId)
                        && filePath.startsWith(entry.getKey() + java.io.File.separator)) {
                    if (existing.isExpired()) {
                        LOG.log(Level.WARNING, "Force-releasing expired directory lock: {0}", existing);
                        fileLocks.remove(entry.getKey());
                        Set<ResourceLock> sl = sessionLocks.get(existing.getSessionId());
                        if (sl != null && sl.remove(existing) && sl.isEmpty()) {
                            sessionLocks.remove(existing.getSessionId());
                        }
                    }
                    else {
                        LOG.log(Level.FINE, "Cannot acquire file lock for {0} - covered by directory lock held by {1}", new Object[]{filePath, existing.getSessionId()});
                        return false;
                    }
                }
            }
        }

        long timeoutMillis = LockTypeEnum.FILE_WRITE_LOCK.getLifetimeMillis();
        ResourceLock lock = new ResourceLock(LockTypeEnum.FILE_WRITE_LOCK, sessionId, timeoutMillis,
                ResourceLock.LockScope.FILE, filePaths);
        for (String filePath : filePaths) {
            ResourceLock previous = fileLocks.put(filePath, lock);
            // Re-acquiring a path this session already holds (nested tool call on the same
            // file) used to silently orphan the superseded ResourceLock in sessionLocks
            // forever, poisoning releaseAllLocks bookkeeping. Retire the old object as soon
            // as it no longer guards any remaining path.
            if (previous != null && previous != lock && sessionId.equals(previous.getSessionId())) {
                dropIfFullySuperseded(sessionId, previous);
            }
        }
        sessionLocks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(lock);
        logLockLifecycle("Acquired file locks for {0} files by session {1}", filePaths.size(), sessionId);
        return true;
    }

    /**
     * Removes a same-session {@code ResourceLock} from the session's tracking set once none of its paths map to it
     * anymore. A multi-path lock that still guards at least one untouched path must stay tracked until its last path is
     * released or superseded. Caller must hold the manager monitor.
     */
    private void dropIfFullySuperseded(String sessionId, ResourceLock superseded) {
        boolean stillGuardsAPath = superseded.getLockedPaths().stream()
                .anyMatch(p -> fileLocks.get(p) == superseded);
        if (stillGuardsAPath) {
            return;
        }
        Set<ResourceLock> sl = sessionLocks.get(sessionId);
        if (sl != null) {
            sl.remove(superseded);
            if (sl.isEmpty()) {
                sessionLocks.remove(sessionId, sl);
            }
        }
    }

    public boolean acquireDirectoryLock(String sessionId, String dirPath) {
        return acquireWithWait(LockTypeEnum.REFACTOR_LOCK,
                () -> tryAcquireDirectoryLock(sessionId, dirPath));
    }

    private synchronized boolean tryAcquireDirectoryLock(String sessionId, String dirPath) {
        List<Map.Entry<String, ResourceLock>> toEvict = new ArrayList<>();
        for (Map.Entry<String, ResourceLock> entry : fileLocks.entrySet()) {
            ResourceLock existing = entry.getValue();
            if (existing.getSessionId().equals(sessionId)) {
                continue;
            }
            // Check files/dirs under dirPath (require separator to avoid "/foo/bar" matching "/foo/bar_tmp")
            boolean underRequested = entry.getKey().equals(dirPath)
                    || entry.getKey().startsWith(dirPath + java.io.File.separator);
            // Check if dirPath falls under an existing directory lock
            boolean requestedUnderExisting = existing.getScope() == ResourceLock.LockScope.DIRECTORY
                    && dirPath.startsWith(entry.getKey() + java.io.File.separator);
            if (underRequested || requestedUnderExisting) {
                if (existing.isExpired()) {
                    toEvict.add(entry);
                }
                else {
                    LOG.log(Level.FINE, "Cannot acquire directory lock for {0}", dirPath);
                    return false;
                }
            }
        }
        for (Map.Entry<String, ResourceLock> entry : toEvict) {
            LOG.log(Level.WARNING, "Force-releasing expired lock: {0}", entry.getValue());
            fileLocks.remove(entry.getKey());
            Set<ResourceLock> sl = sessionLocks.get(entry.getValue().getSessionId());
            if (sl != null && sl.remove(entry.getValue()) && sl.isEmpty()) {
                sessionLocks.remove(entry.getValue().getSessionId());
            }
        }

        long timeoutMillis = LockTypeEnum.REFACTOR_LOCK.getLifetimeMillis();
        ResourceLock lock = new ResourceLock(LockTypeEnum.REFACTOR_LOCK, sessionId, timeoutMillis,
                ResourceLock.LockScope.DIRECTORY, Set.of(dirPath));
        fileLocks.put(dirPath, lock);
        sessionLocks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(lock);
        logLockLifecycle("Acquired directory lock for {0} by session {1}", dirPath, sessionId);
        return true;
    }

    /**
     * Polls outside the manager monitor so a holder can always release while a contender waits. Each retry runs the
     * normal stale-lock eviction logic.
     */
    private boolean acquireWithWait(LockTypeEnum lockType, BooleanSupplier tryAcquire) {
        if (tryAcquire.getAsBoolean()) {
            return true;
        }
        Long override = waitTimeoutOverrideMillisForTests;
        long waitMillis = override != null ? override : lockType.getWaitTimeoutMillis();
        if (waitMillis <= 0) {
            return false;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            LOG.log(Level.WARNING, "Refusing to wait for {0} on the EDT", lockType);
            return false;
        }
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                long remainingMillis = Math.max(1,
                        (deadline - System.nanoTime() + 999_999L) / 1_000_000L);
                Thread.sleep(Math.min(TimeoutEnum.LOCK_WAIT_POLL_MILLIS.millis(), remainingMillis));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (tryAcquire.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    public synchronized void releaseLock(String sessionId, LockTypeEnum lockType) {
        ResourceLock lock = globalLocks.get(lockType);
        if (lock != null && !lock.getSessionId().equals(sessionId)) {
            LOG.log(Level.WARNING, "Session {0} attempted to release {1} held by {2}", new Object[]{sessionId, lockType, lock.getSessionId()});
        }
        if (lock != null && lock.getSessionId().equals(sessionId)) {
            globalLocks.remove(lockType);
            Set<ResourceLock> sl = sessionLocks.get(sessionId);
            if (sl != null && sl.remove(lock) && sl.isEmpty()) {
                sessionLocks.remove(sessionId);
            }
            logLockLifecycle("Released {0} by session {1}", lockType, sessionId);
        }
    }

    public synchronized void releaseFileLock(String sessionId, String filePath) {
        ResourceLock lock = fileLocks.get(filePath);
        if (lock != null && !lock.getSessionId().equals(sessionId)) {
            LOG.log(Level.WARNING, "Session {0} attempted to release file lock for {1} held by {2}", new Object[]{sessionId, filePath, lock.getSessionId()});
        }
        if (lock != null && lock.getSessionId().equals(sessionId)) {
            // Two-arg remove: if a newer mapping for this path exists (it should not while we
            // hold the monitor, but this makes stale-object handling structurally safe), only
            // ever retire the object the caller actually holds.
            fileLocks.remove(filePath, lock);
            boolean allPathsReleased = lock.getLockedPaths().stream().noneMatch(fileLocks::containsKey);
            if (allPathsReleased) {
                Set<ResourceLock> sl = sessionLocks.get(sessionId);
                if (sl != null && sl.remove(lock) && sl.isEmpty()) {
                    sessionLocks.remove(sessionId);
                }
            }
            logLockLifecycle("Released file lock for {0} by session {1}", filePath, sessionId);
        }
    }

    public synchronized void releaseAllLocks(String sessionId) {
        Set<ResourceLock> locks = sessionLocks.remove(sessionId);
        if (locks == null) {
            return;
        }
        for (ResourceLock lock : locks) {
            if (lock.getScope() == ResourceLock.LockScope.GLOBAL) {
                globalLocks.remove(lock.getLockType(), lock);
            }
            else {
                for (String path : lock.getLockedPaths()) {
                    // Identity-checked removal: a stale lock object in this session's set must
                    // never evict a DIFFERENT session's newer live mapping for the same path.
                    fileLocks.remove(path, lock);
                }
            }
        }
        logLockLifecycle("Released all locks for session {0}", sessionId);
    }

    public synchronized void releaseOrphanedLocks(Set<String> activeSessionIds) {
        Set<String> orphaned = new HashSet<>(sessionLocks.keySet());
        orphaned.removeAll(activeSessionIds);
        for (String sessionId : orphaned) {
            releaseAllLocks(sessionId);
            LOG.log(Level.WARNING, "Released orphaned locks for defunct session {0}", sessionId);
        }
    }

    public synchronized boolean isLocked(LockTypeEnum lockType) {
        ResourceLock lock = globalLocks.get(lockType);
        if (lock == null) {
            return false;
        }
        if (lock.isExpired()) {
            globalLocks.remove(lockType, lock);
            Set<ResourceLock> sl = sessionLocks.get(lock.getSessionId());
            if (sl != null && sl.remove(lock) && sl.isEmpty()) {
                sessionLocks.remove(lock.getSessionId());
            }
            return false;
        }
        return true;
    }

    public synchronized String getLockHolder(LockTypeEnum lockType) {
        ResourceLock lock = globalLocks.get(lockType);
        if (lock != null && !lock.isExpired()) {
            return lock.getSessionId();
        }
        return null;
    }

    public synchronized String getFileLockHolder(String filePath) {
        ResourceLock lock = fileLocks.get(filePath);
        if (lock != null && !lock.isExpired()) {
            return lock.getSessionId();
        }
        return null;
    }

    public synchronized boolean canModifyFile(String sessionId, String filePath) {
        String holder = getFileLockHolder(filePath);
        return holder == null || holder.equals(sessionId);
    }

    public synchronized ResourceLock getLock(LockTypeEnum lockType) {
        ResourceLock lock = globalLocks.get(lockType);
        if (lock != null && !lock.isExpired()) {
            return lock;
        }
        return null;
    }

    public synchronized Collection<ResourceLock> getAllActiveLocks() {
        Set<ResourceLock> active = new HashSet<>();
        active.addAll(globalLocks.values());
        active.addAll(fileLocks.values());
        active.removeIf(ResourceLock::isExpired);
        return active;
    }

    private void startCleanupThread() {
        cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(TimeoutEnum.LOCK_CLEANUP_INTERVAL_MILLIS.millis());
                    cleanupExpiredLocks();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "LockManager-Cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private synchronized void cleanupExpiredLocks() {
        globalLocks.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                LOG.log(Level.WARNING, "Auto-releasing expired lock: {0}", entry.getValue());
                Set<ResourceLock> sl = sessionLocks.get(entry.getValue().getSessionId());
                if (sl != null && sl.remove(entry.getValue()) && sl.isEmpty()) {
                    sessionLocks.remove(entry.getValue().getSessionId());
                }
                return true;
            }
            return false;
        });

        fileLocks.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                LOG.log(Level.WARNING, "Auto-releasing expired lock: {0}", entry.getValue());
                Set<ResourceLock> sl = sessionLocks.get(entry.getValue().getSessionId());
                if (sl != null && sl.remove(entry.getValue()) && sl.isEmpty()) {
                    sessionLocks.remove(entry.getValue().getSessionId());
                }
                return true;
            }
            return false;
        });
    }
}
