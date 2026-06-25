package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LockManager {

    private static final Logger LOG = Logger.getLogger(LockManager.class.getName());
    private static volatile LockManager instance;

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

    public synchronized boolean acquireLock(String sessionId, LockTypeEnum lockType) {
        if (globalLocks.containsKey(lockType)) {
            ResourceLock existing = globalLocks.get(lockType);
            if (existing.getSessionId().equals(sessionId)) {
                if (!existing.isExpired()) {
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

        long timeoutMillis = lockType.getTimeoutMinutes() * 60 * 1000;
        ResourceLock lock = new ResourceLock(lockType, sessionId, timeoutMillis);
        globalLocks.put(lockType, lock);
        sessionLocks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(lock);
        LOG.log(Level.INFO, "Acquired {0} for session {1}", new Object[]{lockType, sessionId});
        return true;
    }

    public synchronized boolean acquireFileLock(String sessionId, String filePath) {
        return acquireFileLocks(sessionId, Set.of(filePath));
    }

    public synchronized boolean acquireFileLocks(String sessionId, Set<String> filePaths) {
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

        long timeoutMillis = LockTypeEnum.FILE_WRITE_LOCK.getTimeoutMinutes() * 60 * 1000;
        ResourceLock lock = new ResourceLock(LockTypeEnum.FILE_WRITE_LOCK, sessionId, timeoutMillis,
                ResourceLock.LockScope.FILE, filePaths);
        for (String filePath : filePaths) {
            fileLocks.put(filePath, lock);
        }
        sessionLocks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(lock);
        LOG.log(Level.INFO, "Acquired file locks for {0} files by session {1}", new Object[]{filePaths.size(), sessionId});
        return true;
    }

    public synchronized boolean acquireDirectoryLock(String sessionId, String dirPath) {
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

        long timeoutMillis = LockTypeEnum.REFACTOR_LOCK.getTimeoutMinutes() * 60 * 1000;
        ResourceLock lock = new ResourceLock(LockTypeEnum.REFACTOR_LOCK, sessionId, timeoutMillis,
                ResourceLock.LockScope.DIRECTORY, Set.of(dirPath));
        fileLocks.put(dirPath, lock);
        sessionLocks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(lock);
        LOG.log(Level.INFO, "Acquired directory lock for {0} by session {1}", new Object[]{dirPath, sessionId});
        return true;
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
            LOG.log(Level.INFO, "Released {0} by session {1}", new Object[]{lockType, sessionId});
        }
    }

    public synchronized void releaseFileLock(String sessionId, String filePath) {
        ResourceLock lock = fileLocks.get(filePath);
        if (lock != null && !lock.getSessionId().equals(sessionId)) {
            LOG.log(Level.WARNING, "Session {0} attempted to release file lock for {1} held by {2}", new Object[]{sessionId, filePath, lock.getSessionId()});
        }
        if (lock != null && lock.getSessionId().equals(sessionId)) {
            fileLocks.remove(filePath);
            boolean allPathsReleased = lock.getLockedPaths().stream().noneMatch(fileLocks::containsKey);
            if (allPathsReleased) {
                Set<ResourceLock> sl = sessionLocks.get(sessionId);
                if (sl != null && sl.remove(lock) && sl.isEmpty()) {
                    sessionLocks.remove(sessionId);
                }
            }
            LOG.log(Level.INFO, "Released file lock for {0} by session {1}", new Object[]{filePath, sessionId});
        }
    }

    public synchronized void releaseAllLocks(String sessionId) {
        Set<ResourceLock> locks = sessionLocks.remove(sessionId);
        if (locks == null) {
            return;
        }
        for (ResourceLock lock : locks) {
            if (lock.getScope() == ResourceLock.LockScope.GLOBAL) {
                globalLocks.remove(lock.getLockType());
            }
            else {
                for (String path : lock.getLockedPaths()) {
                    fileLocks.remove(path);
                }
            }
        }
        LOG.log(Level.INFO, "Released all locks for session {0}", sessionId);
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
                    Thread.sleep(30000);
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
