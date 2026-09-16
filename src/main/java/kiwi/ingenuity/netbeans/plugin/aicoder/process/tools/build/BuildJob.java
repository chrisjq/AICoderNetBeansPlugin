package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.DurationTimeLogger;

/**
 * One build in the {@link BuildQueue}: the request, its status and timings, and its result once finished. State changes
 * are made only by the queue; everything a reader sees is safe to read from any thread.
 */
public final class BuildJob {

    private final String id;
    private final BuildRequest request;
    private final BuildControl control;
    private final Instant queuedAt;
    private final Set<String> listeners = new CopyOnWriteArraySet<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CompletableFuture<BuildJob> finished = new CompletableFuture<>();
    private volatile BuildStatusEnum status = BuildStatusEnum.QUEUED;
    private volatile DurationTimeLogger timer;
    private volatile Instant startedAt;
    private volatile Instant endedAt;
    private volatile Duration duration;
    private volatile String result;
    private volatile BuildCancelReasonEnum cancelReason;
    private volatile boolean requesterGone;

    BuildJob(String id, BuildRequest request, Instant queuedAt) {
        this.id = id;
        this.request = request;
        this.control = new BuildControl(request.timeoutMillis());
        this.queuedAt = queuedAt;
    }

    /**
     * The other AI sessions waiting on this build because they asked for exactly the same one while it was queued or
     * running. They receive the same completion message as the requester, but only the requester
     * ({@link BuildRequest#sessionId()}) may stop it.
     */
    public Set<String> listeners() {
        return Set.copyOf(listeners);
    }

    /**
     * @return false when {@code sessionId} is the requester or already listening, so a caller can tell a genuine join
     * from a repeat
     */
    boolean addListener(String sessionId) {
        return !request.sessionId().equals(sessionId) && listeners.add(sessionId);
    }

    boolean removeListener(String sessionId) {
        return listeners.remove(sessionId);
    }

    /**
     * Whether the AI that requested this build has closed its session. Its listeners keep the build alive — they asked
     * for exactly this build — so it is only abandoned once the last of them has gone too.
     */
    boolean isRequesterGone() {
        return requesterGone;
    }

    void markRequesterGone() {
        requesterGone = true;
    }

    public String id() {
        return id;
    }

    public BuildRequest request() {
        return request;
    }

    public BuildStatusEnum status() {
        return status;
    }

    public Instant queuedAt() {
        return queuedAt;
    }

    /**
     * When the build started running, or null if it never did.
     */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * When the build finished, or null while it is queued or running.
     */
    public Instant endedAt() {
        return endedAt;
    }

    /**
     * How long the build ran, or null if it never started or has not finished.
     */
    public Duration duration() {
        return duration;
    }

    /**
     * The text the calling AI receives, or null until the build finishes.
     */
    public String result() {
        return result;
    }

    /**
     * Why the build was cancelled, or null unless its status is {@link BuildStatusEnum#CANCELLED}.
     */
    public BuildCancelReasonEnum cancelReason() {
        return cancelReason;
    }

    BuildControl control() {
        return control;
    }

    CountDownLatch started() {
        return started;
    }

    CompletableFuture<BuildJob> finished() {
        return finished;
    }

    void markRunning(Clock clock) {
        timer = new DurationTimeLogger(clock);
        startedAt = timer.getStartTime();
        status = BuildStatusEnum.RUNNING;
    }

    void requestCancel(BuildCancelReasonEnum reason) {
        cancelReason = reason;
        control.cancel();
    }

    void markFinished(BuildStatusEnum finalStatus, String finalResult, BuildCancelReasonEnum reason, Instant now) {
        if (timer != null) {
            duration = timer.getDuration();
        }
        if (reason != null) {
            cancelReason = reason;
        }
        result = finalResult;
        endedAt = now;
        status = finalStatus;
    }
}
