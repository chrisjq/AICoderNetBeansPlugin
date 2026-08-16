package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;

/**
 * Shared state and trivial accessors for the per-AI process managers
 * ({@code ClaudeAiProcessManager}, {@code GithubCopilotProcessManager}).
 *
 * <p>
 * Holds the common per-session/turn state and its synchronized accessors. The
 * turn <em>lifecycle</em> (start, sendPrompt, cancel, interrupt, stop,
 * resumeSession) stays backend-specific because the two CLIs behave differently
 * — e.g. Claude uses {@code --resume} plus a graceful stdin interrupt and keeps
 * {@code firstMessage}/{@code cachedContextWindow}, while Copilot uses
 * {@code --session-id}, a hard kill, and {@code sessionCorrupted}/
 * {@code copilotSessionId} recovery. Those remain as abstract methods declared
 * here and implemented by each subclass.
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

    public void updatePinnedContext(String identity, String baseline, String instructions) {
    }

    // ---- Backend-specific turn lifecycle ----
    public abstract void start(String executableOrConfig, String modelOrConfig);

    public abstract void sendPrompt(String text, File workingDir, List<File> projectDirs);

    /**
     * Abort the in-flight turn. Backends that support a graceful interrupt
     * (keeping partial output / context) do so; others terminate the process.
     */
    public abstract void interrupt(InterruptTypeEnum type);

    public abstract void stop();

    public abstract void resumeSession(String existingSessionId);

    /**
     * @return true while the MCP registrar for this session is registered.
     */
    public abstract boolean isMcpActive();
}
