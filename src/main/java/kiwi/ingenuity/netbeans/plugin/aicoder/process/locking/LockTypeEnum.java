package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

public enum LockTypeEnum {
    GIT_LOCK("Git Operations", TimeoutEnum.GIT_LOCK_LIFETIME_MILLIS.millis(), TimeoutEnum.GIT_LOCK_WAIT_MILLIS),
    BUILD_LOCK("Build Operations", TimeoutEnum.BUILD_LOCK_LIFETIME_MILLIS.millis(), TimeoutEnum.BUILD_LOCK_WAIT_MILLIS),
    REFACTOR_LOCK("Refactoring", TimeoutEnum.REFACTOR_LOCK_LIFETIME_MILLIS.millis(), TimeoutEnum.REFACTOR_LOCK_WAIT_MILLIS),
    FILE_WRITE_LOCK("File I/O", TimeoutEnum.FILE_WRITE_LOCK_LIFETIME_MILLIS.millis(), TimeoutEnum.FILE_WRITE_LOCK_WAIT_MILLIS),
    SESSION_LOCK("Session Management", TimeoutEnum.SESSION_LOCK_LIFETIME_MILLIS.millis(), TimeoutEnum.SESSION_LOCK_WAIT_MILLIS),
    PROJECT_STRUCTURE_LOCK("Project Structure", TimeoutEnum.PROJECT_STRUCTURE_LOCK_LIFETIME_MILLIS.millis(), TimeoutEnum.PROJECT_STRUCTURE_LOCK_WAIT_MILLIS);

    private final String description;
    private final long lifetimeMillis;
    private final TimeoutEnum waitTimeout;

    LockTypeEnum(String description, long lifetimeMillis, TimeoutEnum waitTimeout) {
        this.description = description;
        this.lifetimeMillis = lifetimeMillis;
        this.waitTimeout = waitTimeout;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Maximum age of a held lock before it is treated as stale and released. This is not an acquisition wait timeout.
     */
    public long getLifetimeMillis() {
        return lifetimeMillis;
    }

    /**
     * Maximum time a caller waits for a live lock holder before acquisition fails.
     */
    public long getWaitTimeoutMillis() {
        return waitTimeout.millis();
    }

    /**
     * The TimeoutEnum constant this lock's acquisition wait comes from. Lock contention messages report the waited
     * duration from this constant so retuning it can never leave the message stale.
     */
    public TimeoutEnum getWaitTimeout() {
        return waitTimeout;
    }
}
