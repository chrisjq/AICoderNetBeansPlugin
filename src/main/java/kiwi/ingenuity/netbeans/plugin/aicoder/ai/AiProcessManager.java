package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;

/**
 * Shared state and trivial accessors for the per-AI process managers ({@code ClaudeAiProcessManager},
 * {@code GithubCopilotProcessManager}).
 *
 * <p>
 * Holds the common per-session/turn state and its synchronized accessors. The turn <em>lifecycle</em> (start,
 * sendPrompt, cancel, interrupt, stop, resumeSession) stays backend-specific because the two CLIs behave
 * differently — e.g. Claude uses {@code --resume} plus a graceful stdin interrupt and keeps
 * {@code firstMessage}/{@code cachedContextWindow}, while Copilot uses {@code --session-id}, a hard kill, and
 * {@code sessionCorrupted}/ {@code copilotSessionId} recovery. Those remain as abstract methods declared here
 * and implemented by each subclass.
 */
public abstract class AiProcessManager {

    protected final AiProcessEventListener listener;

    /**
     * Path/command of the backend CLI executable.
     */
    protected volatile String executablePath;
    /**
     * Currently selected model.
     */
    protected volatile String model;
    /**
     * Plugin session id (the {@link AiSession#id()} UUID).
     */
    protected volatile String sessionId = null;
    /**
     * Working directory pinned on the first send of the session.
     */
    protected volatile File sessionWorkingDir = null;
    /**
     * Per-session config dir ({@code ~/.ai-coder/{type}/{sessionId}/}).
     */
    protected volatile Path sessionConfigDir = null;
    /**
     * The shared session model object.
     */
    protected volatile AiSession currentSession = null;

    protected volatile boolean running = false;
    protected volatile boolean processing = false;
    protected volatile boolean pendingDiff = false;
    protected volatile boolean cancelledByUser = false;
    protected volatile Process currentProcess;

    protected AiProcessManager(AiProcessEventListener listener) {
        this.listener = listener;
    }

    // These accessors are called from the EDT (tab open, history save/load,
    // diff-panel callbacks) and each guards a single volatile field, so they
    // are deliberately NOT synchronized: the subclasses' synchronized start()
    // holds the manager monitor for seconds (CLI spawn + MCP registration),
    // and sharing that monitor here froze the whole NetBeans UI whenever a
    // session tab opened while a start was in flight.
    public void setCurrentSession(AiSession session) {
        this.currentSession = session;
    }

    public void setSessionConfigDir(Path configDir) {
        this.sessionConfigDir = configDir;
    }

    public String getSessionId() {
        return sessionId;
    }

    public File getSessionWorkingDir() {
        return sessionWorkingDir;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setPendingDiff(boolean pending) {
        this.pendingDiff = pending;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isProcessing() {
        return processing;
    }

    public boolean isPendingDiff() {
        return pendingDiff;
    }

    public McpHookServer getMcpServer() {
        return McpServerRegistry.getServer();
    }

    /**
     * Default ceiling for {@link #runWork} — a compaction that has not finished in five minutes is reported
     * as failed so the session is never left locked.
     */
    public static final long DEFAULT_WORK_TIMEOUT_MILLIS = 5 * 60_000L;

    /**
     * Identity of the piece of non-turn work in flight, or {@code null}. A fresh object per {@link #runWork}
     * call, so a late completion of earlier work can never close later work (compare-and-set against its own
     * token).
     */
    private final AtomicReference<Object> workInFlight = new AtomicReference<>();

    /**
     * True from the BUSY that {@link #runWork} reports until that work's single closing status.
     */
    public boolean isWorkInFlight() {
        return workInFlight.get() != null;
    }

    /**
     * True while the backend is doing anything the user must wait for: a turn ({@code processing}) or
     * non-turn work.
     */
    public boolean isBusy() {
        return processing || isWorkInFlight();
    }

    /**
     * Runs a piece of non-turn work the UI must wait for — a compaction — and guarantees the busy/ready
     * contract the UI relies on: exactly one {@code BUSY(busyStatus, cancellable)} when it starts, and
     * exactly one closing status when it ends — whatever {@code onSuccess}/{@code onFailure} map the outcome
     * to (READY for success or a harmless outcome, FAILED for a genuine error) — on every path: success,
     * failure, an exception thrown while starting, the timeout, or {@link #failWorkInFlight} from process
     * exit or stop.
     *
     * <p>
     * The work must not leak a {@code TurnCompleteEvent} to the UI, even when the backend runs it as a turn
     * (Claude's {@code /compact}, a Codex or OpenCode compaction turn): its closer is the status returned
     * here. A leaked TurnComplete would let the UI start the next queued turn, which this READY would then
     * unlock mid-turn.
     *
     * @return {@code false} if other non-turn work is already in flight — nothing is reported and nothing
     *         runs, so the caller should say so (e.g. "Compaction already in progress")
     */
    public final <T> boolean runWork(String busyStatus, boolean cancellable, long timeoutMillis,
                                     Supplier<CompletableFuture<T>> work,
                                     Function<T, StatusEvent> onSuccess,
                                     Function<Throwable, StatusEvent> onFailure) {
        Object token = new Object();
        if (!workInFlight.compareAndSet(null, token)) {
            return false;
        }
        listener.onAiProcessEvent(StatusEvent.busy(busyStatus, cancellable));
        CompletableFuture<T> started;
        try {
            started = work.get();
            if (started == null) {
                started = CompletableFuture.failedFuture(new IllegalStateException("the work did not start"));
            }
        }
        catch (RuntimeException e) {
            started = CompletableFuture.failedFuture(e);
        }
        started.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete((result, error) -> {
            StatusEvent closing;
            try {
                closing = error == null ? onSuccess.apply(result) : onFailure.apply(unwrap(error));
            }
            catch (RuntimeException e) {
                closing = new StatusEvent(StatusEventTypeEnum.FAILED, String.valueOf(e.getMessage()));
            }
            closeWork(token, closing);
        });
        return true;
    }

    /**
     * Closes whatever non-turn work is in flight as FAILED with {@code reason} — call from process-exit and
     * stop paths so a session whose process died mid-compaction is never left locked. Exactly-once: if the
     * work already closed, or closes later, only the first closing status is reported.
     */
    protected final boolean failWorkInFlight(String reason) {
        Object token = workInFlight.get();
        return token != null && closeWork(token, new StatusEvent(StatusEventTypeEnum.FAILED, reason));
    }

    private boolean closeWork(Object token, StatusEvent closing) {
        if (workInFlight.compareAndSet(token, null)) {
            listener.onAiProcessEvent(closing);
            return true;
        }
        return false;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public void updatePinnedContext(String identity, String baseline, String instructions) {
    }

    // ---- Backend-specific turn lifecycle ----
    public abstract void start(String executableOrConfig, String modelOrConfig);

    public abstract void sendPrompt(String text, File workingDir, List<File> projectDirs);

    /**
     * Abort the in-flight turn. Backends that support a graceful interrupt (keeping partial output / context)
     * do so; others terminate the process.
     */
    public abstract void interrupt(InterruptTypeEnum type);

    public abstract void stop();

    public abstract void resumeSession(String existingSessionId);

    /**
     * @return true while the MCP registrar for this session is registered.
     */
    public abstract boolean isMcpActive();
}
