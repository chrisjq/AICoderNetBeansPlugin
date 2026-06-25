package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ResourceLock {

    public enum LockScope {
        GLOBAL,
        FILE,
        DIRECTORY
    }

    private final LockTypeEnum lockType;
    private final String sessionId;
    private final Instant acquiredAt;
    private final long timeoutMillis;
    private final LockScope scope;
    private final Set<String> lockedPaths;

    public ResourceLock(LockTypeEnum lockType, String sessionId, long timeoutMillis) {
        this(lockType, sessionId, timeoutMillis, LockScope.GLOBAL, Collections.emptySet());
    }

    public ResourceLock(LockTypeEnum lockType, String sessionId, long timeoutMillis, String filePath) {
        this(lockType, sessionId, timeoutMillis, LockScope.FILE, Set.of(filePath));
    }

    public ResourceLock(LockTypeEnum lockType, String sessionId, long timeoutMillis,
            LockScope scope, Set<String> lockedPaths) {
        this.lockType = lockType;
        this.sessionId = sessionId;
        this.acquiredAt = Instant.now();
        this.timeoutMillis = timeoutMillis;
        this.scope = scope;
        this.lockedPaths = new HashSet<>(lockedPaths);
    }

    public LockTypeEnum getLockType() {
        return lockType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public LockScope getScope() {
        return scope;
    }

    public Set<String> getLockedPaths() {
        return Collections.unmodifiableSet(lockedPaths);
    }

    public boolean isExpired() {
        long elapsedMillis = System.currentTimeMillis() - acquiredAt.toEpochMilli();
        return elapsedMillis > timeoutMillis;
    }

    public long getElapsedSeconds() {
        long elapsedMillis = System.currentTimeMillis() - acquiredAt.toEpochMilli();
        return elapsedMillis / 1000;
    }

    public long getRemainingSeconds() {
        long remaining = (timeoutMillis / 1000) - getElapsedSeconds();
        return Math.max(0, remaining);
    }

    public boolean holdsFile(String filePath) {
        if (scope == LockScope.FILE) {
            return lockedPaths.contains(filePath);
        }
        else if (scope == LockScope.DIRECTORY) {
            return lockedPaths.stream()
                    .anyMatch(dir -> filePath.equals(dir)
                    || filePath.startsWith(dir + java.io.File.separator));
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("Lock[%s by %s, age=%ds, remains=%ds, scope=%s, paths=%s]",
                lockType, sessionId, getElapsedSeconds(), getRemainingSeconds(), scope, lockedPaths);
    }
}
