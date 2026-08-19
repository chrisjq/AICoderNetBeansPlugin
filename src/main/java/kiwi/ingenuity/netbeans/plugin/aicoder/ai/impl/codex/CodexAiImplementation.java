package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexRateLimitEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui.CodexAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

public class CodexAiImplementation extends AiImplementation {

    private static final Logger LOG = Logger.getLogger(CodexAiImplementation.class.getName());
    private static final AiModelCatalog modelCatalog = new AiModelCatalog();
    private static volatile CodexRateLimitEvent cachedRateLimitEvent;

    public static AiModelCatalog modelCatalog() {
        return modelCatalog;
    }

    public static void publishRateLimit(CodexRateLimitEvent event) {
        cachedRateLimitEvent = event;
        AiTypePropertyBus.getInstance().fire(AiTypeEnum.CODEX, event);
    }

    private final CodexAiProcessManager delegate;
    private volatile AiSessionHost sessionHost;

    public CodexAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.CODEX, listener, prompter);
        this.delegate = new CodexAiProcessManager(listener);
    }

    @Override
    protected CodexAiProcessManager delegate() {
        return delegate;
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = resolveStartupModel(model);
        String execPath = CodexExecutableLocator.locate();
        if (execPath != null) {
            start(execPath, effectiveModel);
            return;
        }
        String chosen;
        try {
            chosen = prompter.promptForExecutable("Locate codex executable", "codex").get();
        }
        catch (Exception ex) {
            chosen = null;
        }
        if (chosen != null) {
            CodexPluginSettings.setExecutable(chosen);
            start(chosen, effectiveModel);
        }
        else {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatExecutableNotFound(null)));
        }
    }

    /**
     * Resolves the model to start a session with: an explicit argument first,
     * then the session's own choice, then the global default. Mirrors
     * OpenCodeAiImplementation.resolveStartupModel — that method exists because
     * an earlier version fell straight to the global default here, silently
     * running every session on it regardless of what the user picked per
     * session. AiTopComponent always calls {@code startWithDiscovery(null)}, so
     * session settings must be checked before the global default from the
     * start, not discovered as a bug later.
     */
    String resolveStartupModel(String model) {
        String sessionModel = currentSession != null
                && currentSession.settings() instanceof CodexSessionSettings s
                && s.model() != null && !s.model().isBlank()
                ? s.model() : null;
        String effectiveModel = (model != null && !model.isBlank())
                ? model
                : sessionModel != null ? sessionModel : CodexPluginSettings.getModel();
        if (PluginSettings.isDebugJson()) {
            String source = (model != null && !model.isBlank()) ? "explicit argument"
                    : sessionModel != null ? "session setting" : "global default";
            LOG.log(Level.INFO, "Codex requested model=\"{0}\" at session start (source: {1})",
                    new Object[]{effectiveModel, source});
        }
        return effectiveModel;
    }

    @Override
    public void setModel(String model) {
        // Session-scoped change — deliberately does not write the global default.
        // The global default (Tools → Options) is owned solely by the settings panel.
        delegate().setModel(model);
        if (currentSession != null && currentSession.settings() instanceof CodexSessionSettings settings) {
            settings.setModel(model);
        }
        // Unlike OpenCode, there is no live config-option call to also push this
        // into an already-running thread — Codex's model is only settable at
        // thread/start or thread/resume time (design doc: ThreadStartParams.model).
        // A model change while a thread is already live takes effect on the next
        // spawn, matching effort/reasoning-level, which are also turn/thread
        // scoped, not settable mid-thread via any RPC this slice found.
    }

    @Override
    public void resumeSession(String sessionId) {
        // AiTopComponent.loadHistory() passes the PLUGIN session id, which is
        // meaningless to Codex — the agent mints its own thread id, persisted in
        // CodexSessionSettings.threadId(). Ignore the argument and always resume
        // from the stored thread id (or not at all).
        String stored = currentSession != null
                && currentSession.settings() instanceof CodexSessionSettings
                ? ((CodexSessionSettings) currentSession.settings()).threadId()
                : null;
        if (stored != null && !stored.isBlank()) {
            delegate().resumeSession(stored);
        }
        // else: do nothing — a fresh thread/start is correct
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        // thread/resume is attempted first with fallback to thread/start, so a
        // stored thread is always safe to load — a stale id never blocks the user.
        return true;
    }

    @Override
    protected void afterStart() {
        Path configPath = getSessionConfigPath();
        if (configPath != null) {
            delegate.setSessionConfigDir(configPath);
        }
        if (currentSession != null && currentSession.settings() instanceof CodexSessionSettings) {
            String storedThreadId = ((CodexSessionSettings) currentSession.settings()).threadId();
            if (storedThreadId != null) {
                delegate.resumeSession(storedThreadId);
            }
        }
    }

    /**
     * Seeds the combo from the SESSION's own model (not
     * {@code CodexPluginSettings.getModel()}'s global default) — the trap this
     * project already hit once for OpenCode's info bar. Model changes flow back
     * through {@code setModel()} (session-scoped, per its own doc) and
     * {@code host.updateSessionSettings()}, never {@code AiTypePropertyBus},
     * which is keyed by AI type and would reset every other Codex session's
     * dropdown (see {@link CodexAiInfoBarExtension}'s own javadoc on this).
     */
    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        String initialModel = session != null && session.settings() instanceof CodexSessionSettings s
                ? s.model() : null;
        CodexAiInfoBarExtension ext = new CodexAiInfoBarExtension(initialModel);
        ext.addModelChangeListener(e -> {
            String selected = ext.getSelectedModel();
            setModel(selected);
            if (host != null && currentSession != null) {
                host.updateSessionSettings(currentSession.settings());
            }
        });
        CodexRateLimitEvent cached = cachedRateLimitEvent;
        if (cached != null) {
            ext.onPropertyEvent(cached);
        }
        return ext;
    }

    @Override
    public void onStarted(AiSessionHost host) {
        this.sessionHost = host;
        delegate.setOnSessionEstablished(() -> {
            AiSessionHost h = sessionHost;
            AiSession s = currentSession;
            if (h != null && s != null) {
                h.updateSessionSettings(s.settings());
            }
        });
    }
}
