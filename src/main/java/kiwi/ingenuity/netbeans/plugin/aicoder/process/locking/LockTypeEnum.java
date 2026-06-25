package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

public enum LockTypeEnum {
    GIT_LOCK("Git Operations", 5),
    BUILD_LOCK("Build Operations", 10),
    REFACTOR_LOCK("Refactoring", 3),
    FILE_WRITE_LOCK("File I/O", 2),
    SESSION_LOCK("Session Management", 1),
    PROJECT_STRUCTURE_LOCK("Project Structure", 5);

    private final String description;
    private final int timeoutMinutes;

    LockTypeEnum(String description, int timeoutMinutes) {
        this.description = description;
        this.timeoutMinutes = timeoutMinutes;
    }

    public String getDescription() {
        return description;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }
}
