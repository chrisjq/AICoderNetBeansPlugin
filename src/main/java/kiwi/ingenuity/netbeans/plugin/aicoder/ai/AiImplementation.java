package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

public abstract class AiImplementation {

    protected final AiTypeEnum type;
    protected final AiProcessEventListener listener;
    /**
     * The session model object; set via {@link #setCurrentSession}.
     */
    protected volatile AiSession currentSession;

    protected AiImplementation(AiTypeEnum type, AiProcessEventListener listener) {
        this.listener = listener;
        this.type = type;
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
        delegate().cancel();
    }

    /**
     * Graceful interrupt that aborts the in-flight turn. Backends that support
     * a graceful interrupt keep partial output; others terminate the process.
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

    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        return null;
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

    public void startWithDiscovery(String model, Component parent) {
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
