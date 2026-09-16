package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * Where a queued build is: waiting, running, or finished with one of six results.
 */
public enum BuildStatusEnum {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    COMPLETED,
    UNKNOWN;

    public boolean isFinished() {
        return this != QUEUED && this != RUNNING;
    }
}
