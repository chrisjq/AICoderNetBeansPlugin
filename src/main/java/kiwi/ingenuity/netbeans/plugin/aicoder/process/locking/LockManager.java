package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FileUtils;

public class LockManager {

    private static final Logger LOG = Logger.getLogger(LockManager.class.getName());
    private static volatile LockManager instance;
    // ---- Test seam (package-private; production keeps the defaults) ----
    /**
     * When non-null, used instead of {@link LockTypeEnum#getWaitTimeoutMillis()} as every lock type's
     * acquisition wait, so a test can assert a contended-acquire-fails property in milliseconds rather than
     * waiting out a real timeout (e.g. {@code BUILD_LOCK}'s deliberate 120s). Same pattern as
     * {@code TempFileRegistry.maxAgeMillis}. Null in production.
     */
    static volatile Long waitTimeoutOverrideMillisForTests = null;

    /**
     * Routine acquire/release chatter is gated by the same setting as MCP tool-use logging
     * ({@link PluginSettings#isLogToolUse()}), so the NetBeans log stays quiet during normal operation.
     * Warnings for contention/expiry stay always-on.
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
     * Contention message for a failed per-file lock acquisition, shared by every caller (ApplyEditTool,
     * WriteFileTool and the native edit/write hook) so the advice cannot drift between them. A per-file lock
     * is held only around the write itself — never across a diff approval or confirmation prompt — so a
     * contender is normally blocked only briefly, by another write, rather than by a user decision.
     */
    public static String fileLockedMessage(String holder) {
        return "File is locked by " + (holder != null ? "session " + holder : "another in-progress write")
               + " — a per-file lock is held only for the moment of the write itself (never across a diff"
               + " approval or confirmation prompt), so this is normally brief. Retrying shortly should"
               + " succeed; if it keeps failing, work on other files meanwhile or report this to the user.";
    }

    private final Map<LockTypeEnum, ResourceLock> globalLocks = new ConcurrentHashMap<>();
    private final Map<String, ResourceLock> fileLocks = new ConcurrentHashMap<>();
    /**
     * Concurrent SHARED (read) holders per path — disjoint from {@link #fileLocks}, which holds at most one
     * EXCLUSIVE (write, or directory) holder per path. Multiple reads of the same path may coexist; a write
     * may not coexist with any read, or any other write, of the same path.
     */
    private final Map<String, Set<ResourceLock>> fileReadLocks = new ConcurrentHashMap<>();
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

    /**
     * Canonical file keys for every file-operation lock. Existing paths resolve symlinks; planned
     * destinations fall back to an absolute normalised path. TreeSet gives every multi-file operation the
     * same A→B acquisition order.
     */
    public Set<String> normalisePaths(Collection<String> filePaths) {
        return FileUtils.normaliseLockPaths(filePaths);
    }

    public boolean acquireFileLock(String sessionId, String filePath) {
        return acquireFileLocks(sessionId, Set.of(filePath));
    }

    public boolean acquireFileLocks(String sessionId, Set<String> filePaths) {
        return acquireFileLocks(sessionId, filePaths, null);
    }

    /**
     * Acquires an EXCLUSIVE (write) lock on every path, waiting out contention as usual. On failure,
     * {@code contendedPathOut} — if given — is set to the specific path that could not be acquired on the
     * final attempt, so the caller can report who actually holds it rather than an arbitrary path from the
     * request.
     */
    public boolean acquireFileLocks(String sessionId, Set<String> filePaths, AtomicReference<String> contendedPathOut) {
        Set<String> normalised = normalisePaths(filePaths);
        return acquireWithWait(LockTypeEnum.FILE_WRITE_LOCK,
                () -> tryAcquireFileLocks(sessionId, normalised, contendedPathOut));
    }

    private synchronized boolean tryAcquireFileLocks(String sessionId, Set<String> filePaths,
                                                     AtomicReference<String> contendedPathOut) {
        for (String filePath : filePaths) {
            // Check exact file lock. Reentrancy requires the SAME THREAD, not merely the same
            // session: two PARALLEL calls from one session (an AI issuing concurrent tool calls)
            // are genuine contention, not a nested re-acquire, and must serialise like any other
            // contender — see the regression test this guards, sameSessionParallelWritesSerialise.
            if (fileLocks.containsKey(filePath)) {
                ResourceLock existing = fileLocks.get(filePath);
                boolean sameCallReentrant = existing.getSessionId().equals(sessionId)
                                            && existing.getOwningThread() == Thread.currentThread();
                if (!sameCallReentrant) {
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
                        if (contendedPathOut != null) {
                            contendedPathOut.set(filePath);
                        }
                        return false;
                    }
                }
            }
            // Check if any directory lock covers this file path
            for (Map.Entry<String, ResourceLock> entry : fileLocks.entrySet()) {
                ResourceLock existing = entry.getValue();
                boolean sameCallReentrant = existing.getSessionId().equals(sessionId)
                                            && existing.getOwningThread() == Thread.currentThread();
                if (existing.getScope() == ResourceLock.LockScope.DIRECTORY
                    && !sameCallReentrant
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
                        // The DIRECTORY path, not filePath: filePath is never a key in fileLocks
                        // here (only entry.getKey(), the directory, is), so getFileLockHolder
                        // would otherwise look up a path it holds no lock for and return null.
                        if (contendedPathOut != null) {
                            contendedPathOut.set(entry.getKey());
                        }
                        return false;
                    }
                }
            }
            // A write must wait for every active READ of this path too, same-session included:
            // an in-flight read is a separate, not-yet-finished call, so running the write
            // concurrently with it is exactly the race this check exists to prevent (N4).
            //
            // Not reentrant by thread, unlike the writer check above: a write nested inside a
            // read held by the SAME thread on the SAME path would wait on itself here. Nor is a
            // nested same-thread withFileMutation reference-counted — its inner release would
            // drop the lock while the outer call still believes it holds it. Neither shape
            // occurs anywhere in this codebase today; this is a documented assumption, not a
            // guard.
            Set<ResourceLock> readers = fileReadLocks.get(filePath);
            if (readers != null) {
                readers.removeIf(ResourceLock::isExpired);
                if (readers.isEmpty()) {
                    fileReadLocks.remove(filePath, readers);
                }
                else {
                    LOG.log(Level.FINE, "Cannot acquire file lock for {0} - active read(s) in progress", filePath);
                    if (contendedPathOut != null) {
                        contendedPathOut.set(filePath);
                    }
                    return false;
                }
            }
        }

        long timeoutMillis = LockTypeEnum.FILE_WRITE_LOCK.getLifetimeMillis();
        ResourceLock lock = new ResourceLock(LockTypeEnum.FILE_WRITE_LOCK, sessionId, timeoutMillis,
                ResourceLock.LockScope.FILE, filePaths, true);
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
     * Acquires a SHARED (read) lock on every path — coexists with other reads of the same path, never with a
     * write. Returns the {@link ResourceLock} created for this call on success — release it with
     * {@link #releaseFileReadLock(String, ResourceLock)} — or null on failure, in which case
     * {@code contendedPathOut}, if given, names the path an in-flight write (or directory lock) holds.
     */
    public ResourceLock acquireFileReadLocks(String sessionId, Collection<String> filePaths,
                                             AtomicReference<String> contendedPathOut) {
        Set<String> normalised = normalisePaths(filePaths);
        AtomicReference<ResourceLock> acquired = new AtomicReference<>();
        boolean ok = acquireWithWait(LockTypeEnum.FILE_WRITE_LOCK,
                () -> tryAcquireFileReadLocks(sessionId, normalised, contendedPathOut, acquired));
        return ok ? acquired.get() : null;
    }

    private synchronized boolean tryAcquireFileReadLocks(String sessionId, Set<String> filePaths,
                                                         AtomicReference<String> contendedPathOut, AtomicReference<ResourceLock> acquiredOut) {
        for (String filePath : filePaths) {
            ResourceLock writer = fileLocks.get(filePath);
            if (writer != null) {
                boolean sameCallReentrant = writer.getSessionId().equals(sessionId)
                                            && writer.getOwningThread() == Thread.currentThread();
                if (!sameCallReentrant) {
                    if (writer.isExpired()) {
                        LOG.log(Level.WARNING, "Force-releasing expired file lock: {0}", writer);
                        fileLocks.remove(filePath);
                        Set<ResourceLock> sl = sessionLocks.get(writer.getSessionId());
                        if (sl != null && sl.remove(writer) && sl.isEmpty()) {
                            sessionLocks.remove(writer.getSessionId());
                        }
                    }
                    else {
                        LOG.log(Level.FINE, "Cannot acquire read lock for {0} - write held by {1}", new Object[]{filePath, writer.getSessionId()});
                        if (contendedPathOut != null) {
                            contendedPathOut.set(filePath);
                        }
                        return false;
                    }
                }
            }
            // Check if any directory lock covers this file path — the same scan the write path
            // runs, so a read is blocked by an in-progress directory mutation just as a write is.
            for (Map.Entry<String, ResourceLock> entry : fileLocks.entrySet()) {
                ResourceLock existing = entry.getValue();
                boolean sameCallReentrant = existing.getSessionId().equals(sessionId)
                                            && existing.getOwningThread() == Thread.currentThread();
                if (existing.getScope() == ResourceLock.LockScope.DIRECTORY
                    && !sameCallReentrant
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
                        LOG.log(Level.FINE, "Cannot acquire read lock for {0} - covered by directory lock held by {1}", new Object[]{filePath, existing.getSessionId()});
                        if (contendedPathOut != null) {
                            contendedPathOut.set(entry.getKey());
                        }
                        return false;
                    }
                }
            }
        }
        long timeoutMillis = LockTypeEnum.FILE_WRITE_LOCK.getLifetimeMillis();
        ResourceLock lock = new ResourceLock(LockTypeEnum.FILE_WRITE_LOCK, sessionId, timeoutMillis,
                ResourceLock.LockScope.FILE, filePaths, false);
        for (String filePath : filePaths) {
            fileReadLocks.computeIfAbsent(filePath, k -> ConcurrentHashMap.newKeySet()).add(lock);
        }
        sessionLocks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(lock);
        logLockLifecycle("Acquired read locks for {0} files by session {1}", filePaths.size(), sessionId);
        if (acquiredOut != null) {
            acquiredOut.set(lock);
        }
        return true;
    }

    /**
     * Releases a lock acquired by {@link #acquireFileReadLocks}. Takes the exact {@link ResourceLock} object
     * returned at acquire time, not a path: several reads of the same path may be active at once, so only the
     * token for THIS call identifies which one to remove.
     */
    public synchronized void releaseFileReadLock(String sessionId, ResourceLock lock) {
        if (lock == null) {
            return;
        }
        if (!lock.getSessionId().equals(sessionId)) {
            LOG.log(Level.WARNING, "Session {0} attempted to release a read lock held by {1}", new Object[]{sessionId, lock.getSessionId()});
            return;
        }
        for (String path : lock.getLockedPaths()) {
            Set<ResourceLock> set = fileReadLocks.get(path);
            if (set != null && set.remove(lock) && set.isEmpty()) {
                fileReadLocks.remove(path, set);
            }
        }
        Set<ResourceLock> sl = sessionLocks.get(sessionId);
        if (sl != null && sl.remove(lock) && sl.isEmpty()) {
            sessionLocks.remove(sessionId, sl);
        }
        logLockLifecycle("Released read lock for session {0}", sessionId);
    }

    /**
     * Removes a same-session {@code ResourceLock} from the session's tracking set once none of its paths map
     * to it anymore. A multi-path lock that still guards at least one untouched path must stay tracked until
     * its last path is released or superseded. Caller must hold the manager monitor.
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
        // Normalised here, like acquireFileLocks does, so a caller cannot forget it: symlink aliases and
        // case-insensitive-volume spellings of the same directory must collide regardless of caller.
        String normalised = normalisePaths(Set.of(dirPath)).iterator().next();
        return acquireWithWait(LockTypeEnum.FILE_WRITE_LOCK,
                () -> tryAcquireDirectoryLock(sessionId, normalised));
    }

    private synchronized boolean tryAcquireDirectoryLock(String sessionId, String dirPath) {
        List<Map.Entry<String, ResourceLock>> toEvict = new ArrayList<>();
        for (Map.Entry<String, ResourceLock> entry : fileLocks.entrySet()) {
            ResourceLock existing = entry.getValue();
            boolean sameCallReentrant = existing.getSessionId().equals(sessionId)
                                        && existing.getOwningThread() == Thread.currentThread();
            if (sameCallReentrant) {
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
        // Any active read under (or at) the requested directory is contention too — the write
        // side already waits for readers of the same exact path (N4); a directory mutation must
        // wait for readers anywhere inside the tree it is about to touch.
        for (Map.Entry<String, Set<ResourceLock>> entry : fileReadLocks.entrySet()) {
            String readPath = entry.getKey();
            boolean underRequested = readPath.equals(dirPath) || readPath.startsWith(dirPath + java.io.File.separator);
            if (!underRequested) {
                continue;
            }
            for (ResourceLock reader : new ArrayList<>(entry.getValue())) {
                boolean sameCallReentrant = reader.getSessionId().equals(sessionId)
                                            && reader.getOwningThread() == Thread.currentThread();
                if (sameCallReentrant) {
                    continue;
                }
                if (reader.isExpired()) {
                    LOG.log(Level.WARNING, "Force-releasing expired read lock: {0}", reader);
                    entry.getValue().remove(reader);
                    Set<ResourceLock> sl = sessionLocks.get(reader.getSessionId());
                    if (sl != null && sl.remove(reader) && sl.isEmpty()) {
                        sessionLocks.remove(reader.getSessionId());
                    }
                }
                else {
                    LOG.log(Level.FINE, "Cannot acquire directory lock for {0} - active read at {1}", new Object[]{dirPath, readPath});
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

        long timeoutMillis = LockTypeEnum.FILE_WRITE_LOCK.getLifetimeMillis();
        ResourceLock lock = new ResourceLock(LockTypeEnum.FILE_WRITE_LOCK, sessionId, timeoutMillis,
                ResourceLock.LockScope.DIRECTORY, Set.of(dirPath), true);
        fileLocks.put(dirPath, lock);
        sessionLocks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(lock);
        logLockLifecycle("Acquired directory lock for {0} by session {1}", dirPath, sessionId);
        return true;
    }

    /**
     * Polls outside the manager monitor so a holder can always release while a contender waits. Each retry
     * runs the normal stale-lock eviction logic.
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

    /**
     * Releases by the EXACT key an earlier {@link #acquireFileLocks} returned true for — never re-normalises
     * {@code filePath} itself. Re-deriving it here would mean a delete or move completed inside the lock has
     * already changed what that path resolves to (the real-path walk follows what exists on disk RIGHT NOW,
     * not what existed at acquire time), so a second normalisation pass can silently compute a different key
     * than the one actually held — leaking the real lock until it expires — on top of doing filesystem I/O
     * inside a synchronized method for no reason.
     */
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
            // Identity check, not containsKey: a path this lock no longer guards may have been
            // re-mapped to a DIFFERENT (superseding) lock already, and containsKey would still
            // see a mapping there and wrongly conclude this lock still guards something.
            boolean allPathsReleased = lock.getLockedPaths().stream().noneMatch(p -> fileLocks.get(p) == lock);
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
            else if (lock.isExclusive()) {
                for (String path : lock.getLockedPaths()) {
                    // Identity-checked removal: a stale lock object in this session's set must
                    // never evict a DIFFERENT session's newer live mapping for the same path.
                    fileLocks.remove(path, lock);
                }
            }
            else {
                for (String path : lock.getLockedPaths()) {
                    Set<ResourceLock> set = fileReadLocks.get(path);
                    if (set != null && set.remove(lock) && set.isEmpty()) {
                        fileReadLocks.remove(path, set);
                    }
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

    /**
     * Looks up by the EXACT key an earlier acquire already normalised — see {@link #releaseFileLock} for why
     * this must not re-normalise {@code filePath} itself. Falls back to an active READER's session when there
     * is no writer, so a write refused because a read is in progress still names someone.
     */
    public synchronized String getFileLockHolder(String filePath) {
        ResourceLock lock = fileLocks.get(filePath);
        if (lock != null && !lock.isExpired()) {
            return lock.getSessionId();
        }
        Set<ResourceLock> readers = fileReadLocks.get(filePath);
        if (readers != null) {
            for (ResourceLock reader : readers) {
                if (!reader.isExpired()) {
                    return reader.getSessionId();
                }
            }
        }
        return null;
    }

    /**
     * True when {@code filePath} is blocked only because reads of it are in progress — no writer and no
     * directory lock holds it. Lets a caller word a write's refusal accurately ("reads … in progress", not
     * "another in-progress write") without changing who wins the contention: a write being starved by a
     * steady stream of readers is a known, accepted trade-off, not a bug this checks for.
     */
    public synchronized boolean isPathBlockedByReadersOnly(String filePath) {
        ResourceLock writer = fileLocks.get(filePath);
        if (writer != null && !writer.isExpired()) {
            return false;
        }
        Set<ResourceLock> readers = fileReadLocks.get(filePath);
        return readers != null && readers.stream().anyMatch(r -> !r.isExpired());
    }

    /**
     * Directory-lock counterpart of {@link #isPathBlockedByReadersOnly}: true when any active read is under
     * (or at) {@code dirPath}. A directory mutation's own refusal has no single "the" holder to name — the
     * blocking reader is typically at a child path, not dirPath itself — so this lets the caller word the
     * refusal honestly instead of falling back to a misleading "another in-progress write" with no holder.
     */
    public synchronized boolean isDirectoryBlockedByReadersOnly(String dirPath) {
        for (Map.Entry<String, Set<ResourceLock>> entry : fileReadLocks.entrySet()) {
            String readPath = entry.getKey();
            boolean underRequested = readPath.equals(dirPath) || readPath.startsWith(dirPath + java.io.File.separator);
            if (underRequested && entry.getValue().stream().anyMatch(r -> !r.isExpired())) {
                return true;
            }
        }
        return false;
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
        fileReadLocks.values().forEach(active::addAll);
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

        fileReadLocks.entrySet().removeIf(entry -> {
            Set<ResourceLock> readers = entry.getValue();
            readers.removeIf(reader -> {
                if (!reader.isExpired()) {
                    return false;
                }
                LOG.log(Level.WARNING, "Auto-releasing expired read lock: {0}", reader);
                Set<ResourceLock> sl = sessionLocks.get(reader.getSessionId());
                if (sl != null && sl.remove(reader) && sl.isEmpty()) {
                    sessionLocks.remove(reader.getSessionId());
                }
                return true;
            });
            return readers.isEmpty();
        });
    }
}
