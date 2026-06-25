package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.File;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Stub for future Grok / xAI backend.
 *
 * The goal is that a full GrokBackend would be able to drive the same
 * AiTopComponent chat UI and (re)use the shared MCP tool server for IDE
 * introspection, edits, builds, git, etc.
 *
 * Implementation can be API-based (similar to Anthropic client) or use a local
 * grok CLI if one becomes available, plus register the MCP endpoint.
 */
public class GrokAiImplementation extends AiImplementation {

    public GrokAiImplementation(AiProcessEventListener listener) {
        super(AiTypeEnum.GROK, listener);
        // TODO: initialize Grok client / process / MCP registration
    }

    @Override
    protected AiProcessManager delegate() {
        // No process manager yet — the Grok backend is not implemented.
        return null;
    }

    @Override
    public void interrupt(InterruptTypeEnum type) {
        // TODO
    }

    @Override
    public void setCurrentSession(AiSession session) {
        // TODO
    }

    @Override
    public void resumeSession(String sessionId) {
        // TODO
    }

    @Override
    public void start(String executableOrConfig, String modelOrConfig) {
        // listener.onEvent(new StatusEvent("Grok backend not yet implemented"));
    }

    @Override
    public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        // TODO
    }

    @Override
    public void cancel() {
        // TODO
    }

    @Override
    public void stop() {
        // TODO
    }

    @Override
    public String getSessionId() {
        return null;
    }

    @Override
    public File getSessionWorkingDir() {
        return null;
    }

    @Override
    public void setModel(String model) {
        // TODO
    }

    @Override
    public void setPendingDiff(boolean pending) {
        // TODO
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public boolean isProcessing() {
        return false;
    }

    @Override
    public boolean isPendingDiff() {
        return false;
    }

    @Override
    public Object getMcpServer() {
        return null;
    }

    @Override
    public boolean isMcpActive() {
        return false;
    }

    @Override
    public void onStarted(AiSessionHost session) {
    }

    @Override
    protected void afterStart() {
    }
}
