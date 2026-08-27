package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

public abstract class AiImplementation {

    protected final AiTypeEnum type;
    protected final AiProcessEventListener listener;
    /**
     * UI-side hook for locating a missing CLI executable (shows a file chooser) without this class needing to know
     * about Swing/EDT. See {@link ExecutablePrompter}.
     */
    protected final ExecutablePrompter prompter;
    /**
     * The session model object; set via {@link #setCurrentSession}.
     */
    protected volatile AiSession currentSession;

    protected AiImplementation(AiTypeEnum type, AiProcessEventListener listener, ExecutablePrompter prompter) {
        this.listener = listener;
        this.type = type;
        this.prompter = prompter;
    }

    /**
     * The backend process manager this implementation delegates to.
     */
    protected abstract AiProcessManager delegate();

    public void start(String executableOrConfig, String modelOrConfig) {
        delegate().start(executableOrConfig, modelOrConfig);
        afterStart();
    }

    public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        delegate().sendPrompt(text, workingDir, projectDirs);
    }

    public void cancel() {
        delegate().interrupt(InterruptTypeEnum.Cancel);
    }

    /**
     * Graceful interrupt that aborts the in-flight turn. Backends that support a graceful interrupt keep partial
     * output; others terminate the process.
     */
    public void interrupt(InterruptTypeEnum type) {
        delegate().interrupt(type);
    }

    public void stop() {
        delegate().stop();
    }

    public String getSessionId() {
        return delegate().getSessionId();
    }

    public File getSessionWorkingDir() {
        return delegate().getSessionWorkingDir();
    }

    public abstract void setModel(String model);

    /**
     * Pushes session settings into the running backend. Called on every OK of the session config dialog, whether or not
     * anything about the model was touched.
     *
     * <p>
     * Only forwards a model that actually differs from the one the backend is already on. {@code setModel} is not a
     * plain setter for every backend — Claude and Copilot recycle the CLI session from it — so calling it with an
     * unchanged value discarded a warm session on every config save. Worse for Claude, whose recycle carried no
     * in-flight-turn guard: saving the dialog mid-turn killed the session while the turn stayed marked in flight, and
     * from there Stop was a silent no-op and the chat input never re-enabled. The guard in
     * ClaudeAiProcessManager.recycleForModelChange() now covers that too; this comparison stops the pointless recycle
     * happening at all, for every backend at once.
     */
    public void applySessionSettings(AiSessionSettings settings) {
        if (settings instanceof AiModelSessionSettings modelSettings
                && modelSettings.model() != null && !modelSettings.model().isBlank()
                && !Objects.equals(modelSettings.model(), delegate().getModel())) {
            setModel(modelSettings.model());
        }
    }

    public void setPendingDiff(boolean pending) {
        delegate().setPendingDiff(pending);
    }

    public boolean isRunning() {
        return delegate().isRunning();
    }

    public boolean isProcessing() {
        return delegate().isProcessing();
    }

    public boolean isPendingDiff() {
        return delegate().isPendingDiff();
    }

    public Object getMcpServer() {
        return delegate().getMcpServer();
    }

    public boolean isMcpActive() {
        return delegate().isMcpActive();
    }

    public void updatePinnedContext(String identity, String baseline, String instructions) {
        delegate().updatePinnedContext(identity, baseline, instructions);
    }

    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        return null;
    }

    /**
     * Type-wide services that should start when the first session of this AI type is created. The registry owns this
     * object rather than retaining this UI-bound implementation instance until plugin shutdown.
     */
    public AiTypeLifecycle typeLifecycle() {
        return AiTypeLifecycle.NO_OP;
    }

    public void registerLifecycleListeners(SessionLifecycleSource source) {
    }

    public void onTabActivated() {
    }

    public void resumeSession(String sessionId) {
        delegate().resumeSession(sessionId);
    }

    public boolean isStoredSessionValid(String sessionId) {
        return true;
    }

    public void startWithDiscovery(String model) {
        start(null, model);
    }

    public void setCurrentSession(AiSession session) {
        this.currentSession = session;
        delegate().setCurrentSession(session);
    }

    public Path getSessionConfigPath() {
        String sid = getSessionId();
        if (sid == null || sid.isBlank()) {
            return null;
        }
        try {
            return PluginUtil.getPluginAiSessionConfigDir(type, sid);
        }
        catch (IOException e) {
            return null;
        }
    }

    public abstract void onStarted(AiSessionHost session);

    protected abstract void afterStart();
}
