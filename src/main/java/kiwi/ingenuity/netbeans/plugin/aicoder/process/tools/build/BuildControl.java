package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * Handed to a running build: its time limit, and a cancel switch that also kills the build's process.
 */
public final class BuildControl {

    private final long timeoutMillis;
    private volatile boolean cancelled;
    private Process process;

    public BuildControl(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Registers the build's process so a cancel can kill it. A process attached after a cancel is killed at once.
     */
    public synchronized void attach(Process process) {
        this.process = process;
        if (cancelled && process != null) {
            process.destroyForcibly();
        }
    }

    /**
     * Cancels the build and kills its process. The {@link BuildQueue} is the only production caller; it is public so
     * the process runner's cancellation can be tested directly.
     */
    public synchronized void cancel() {
        cancelled = true;
        if (process != null) {
            process.destroyForcibly();
        }
    }
}
