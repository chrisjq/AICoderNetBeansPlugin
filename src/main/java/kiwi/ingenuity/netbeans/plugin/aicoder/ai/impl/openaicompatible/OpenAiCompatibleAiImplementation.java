package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeLifecycle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;

/**
 * Gated placeholder implementation until OpenAI-compatible chat arrives in Plan 3.
 */
public final class OpenAiCompatibleAiImplementation extends AiImplementation {

    private final OpenAiCompatibleAiProcessManager processManager;
    private volatile AiSessionHost sessionHost;

    public OpenAiCompatibleAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.OPENAI_COMPATIBLE, listener, prompter);
        processManager = new OpenAiCompatibleAiProcessManager(listener);
    }

    @Override
    protected AiProcessManager delegate() {
        return processManager;
    }

    @Override
    public void setModel(String model) {
        delegate().setModel(model);
        if (currentSession != null && currentSession.settings() instanceof OpenAiCompatibleSessionSettings settings) {
            settings.setModel(model);
        }
    }

    private static final AiTypeLifecycle TYPE_LIFECYCLE = new AiTypeLifecycle() {
        @Override
        public void start() {
            SessionSecrets.cleanupOrphansAsync(OpenAiCompatibleAiImplementation::storedSessionExistence);
        }
    };

    /**
     * Whether a session still exists: it is running, it is in the persisted session list, or its config folder is
     * there. Returns null when the list or the folder cannot be read, so the cleanup then deletes nothing.
     */
    private static SessionSecrets.SessionExistence storedSessionExistence() {
        Set<String> known = new HashSet<>();
        SessionRegistry.allSessions().forEach(session -> known.add(session.getAiSession().id()));
        try {
            for (AiSession saved : new SessionPersistenceManager().loadAll()) {
                known.add(saved.id());
            }
            Path typeDir = PluginUtil.getPluginAiSessionConfigDir(AiTypeEnum.OPENAI_COMPATIBLE);
            return sessionId -> known.contains(sessionId) || Files.isDirectory(typeDir.resolve(sessionId));
        }
        catch (IOException ex) {
            return null;
        }
    }

    @Override
    public AiTypeLifecycle typeLifecycle() {
        return TYPE_LIFECYCLE;
    }

    @Override
    public OpenAiCompatibleInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        OpenAiCompatibleInfoBarExtension bar = new OpenAiCompatibleInfoBarExtension();
        if (session.settings() instanceof OpenAiCompatibleSessionSettings settings) {
            bar.onSessionSettingsChanged(settings);
        }
        bar.setModelChangeListener(model -> {
            if (host.getSessionSettings() instanceof OpenAiCompatibleSessionSettings settings) {
                settings.setModel(model);
                host.updateSessionSettings(settings);
                processManager.onSessionSettingsChanged(settings);
            }
        });
        bar.setEffortChangeListener(effort -> {
            if (host.getSessionSettings() instanceof OpenAiCompatibleSessionSettings settings) {
                settings.setReasoningEffort(effort);
                host.updateSessionSettings(settings);
                processManager.onSessionSettingsChanged(settings);
            }
        });
        processManager.attachInfoBar(bar);
        return bar;
    }

    @Override
    public void applySessionSettings(AiSessionSettings settings) {
        super.applySessionSettings(settings);
        if (settings instanceof OpenAiCompatibleSessionSettings compatible) {
            processManager.onSessionSettingsChanged(compatible);
        }
    }

    @Override
    public void onStarted(AiSessionHost session) {
        sessionHost = session;
        processManager.setSettingsPersister(settings -> {
            AiSessionHost host = sessionHost;
            if (host != null) {
                host.updateSessionSettings(settings);
            }
        });
    }

    @Override
    protected void afterStart() {
    }
}
