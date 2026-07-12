package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui.GrokAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Thin adapter so the generic multi-AI system (AiSession, AiTopComponent, etc.)
 * can use the Grok (xAI) implementation. Drives the {@code grok} CLI
 * (https://docs.x.ai/build/cli) in headless mode via
 * {@link GrokAiProcessManager}, reusing the shared MCP tool server for IDE
 * introspection, edits, builds, git, etc. — the same architecture as
 * {@code ClaudeAiImplementation}.
 */
public class GrokAiImplementation extends AiImplementation {

    // The discovered model list is shared across all Grok sessions for the IDE
    // run (like Claude/Copilot): discover once via `grok models`, cache, and
    // broadcast to every open session's dropdown via AiTypePropertyBus.
    private static final Object MODEL_LOCK = new Object();
    private static volatile List<String> cachedModels = null;
    private static volatile boolean modelsFetched = false;

    private final GrokAiProcessManager delegate;

    public GrokAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.GROK, listener, prompter);
        this.delegate = new GrokAiProcessManager(listener);
    }

    @Override
    protected GrokAiProcessManager delegate() {
        return delegate;
    }

    public String getCurrentModel() {
        if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings mc && mc.model() != null) {
            return mc.model();
        }
        return GrokPluginSettings.getModel();
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = (model != null && !model.isBlank()) ? model : getCurrentModel();
        String execPath = GrokExecutableLocator.locate();
        if (execPath != null) {
            start(execPath, effectiveModel);
            return;
        }

        String chosen;
        try {
            chosen = prompter.promptForExecutable("Locate grok executable", "grok").get();
        }
        catch (Exception ex) {
            chosen = null;
        }
        if (chosen != null) {
            GrokPluginSettings.setExecutable(chosen);
            start(chosen, effectiveModel);
        }
        else {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, StatusMessageUtil.formatExecutableNotFound(null)));
        }
    }

    @Override
    public void setModel(String model) {
        GrokPluginSettings.setModel(model);
        delegate.setModel(model);
    }

    public List<String> getDefaultModels() {
        return Arrays.asList(GrokPluginSettings.KNOWN_MODELS);
    }

    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        GrokAiInfoBarExtension provider = new GrokAiInfoBarExtension();
        provider.addModelChangeListener(e -> {
            String model = provider.getSelectedModel();
            if (model == null) {
                return;
            }
            setModel(model);
            AiSessionSettings cfg = host.getSessionSettings();
            String currentModel = cfg instanceof AiModelSessionSettings mc ? mc.model() : null;
            if (!model.equals(currentModel)) {
                if (cfg instanceof AiModelSessionSettings modelCfg) {
                    modelCfg.setModel(model);
                }
                delegate.setCurrentSession(currentSession);
                host.updateSessionSettings(cfg);
            }
        });
        String initialModel = session.settings() instanceof AiModelSessionSettings modelCfg && modelCfg.model() != null
                ? modelCfg.model() : GrokPluginSettings.getModel();
        provider.setSelectedModel(initialModel);
        if (initialModel != null && session.settings() instanceof AiModelSessionSettings modelSettings && modelSettings.model() == null) {
            modelSettings.setModel(initialModel);
            host.updateSessionSettings(modelSettings);
        }

        // Discover the real available model list (best-effort; falls back
        // silently to the hardcoded list). Discovery runs once per IDE run and
        // the result is broadcast to EVERY open Grok session's dropdown.
        triggerModelDiscovery();
        return provider;
    }

    /**
     * Discover the Grok model list once per IDE run via {@code grok models},
     * then broadcast it to every open Grok session's info bar via
     * {@link AiTypePropertyBus} — mirroring the Claude/Copilot flow. A session
     * opened after discovery already completed replays the cached list
     * immediately.
     */
    private void triggerModelDiscovery() {
        if (modelsFetched) {
            List<String> cached;
            synchronized (MODEL_LOCK) {
                cached = cachedModels;
            }
            if (cached != null) {
                List<String> snapshot = cached;
                AiTypePropertyBus.getInstance().fire(AiTypeEnum.GROK, new GrokModelsEvent(snapshot));
            }
            return;
        }
        GrokModelDiscovery.discoverAsync(GrokExecutableLocator.locate(), models -> {
            synchronized (MODEL_LOCK) {
                cachedModels = models;
                modelsFetched = true;
            }
            AiTypePropertyBus.getInstance().fire(AiTypeEnum.GROK, new GrokModelsEvent(models));
        });
    }

    @Override
    public void onStarted(AiSessionHost session) {
    }

    /**
     * Run after every {@code delegate.start()}. grok's {@code -s} flag (create
     * a new headless session) is rejected by the CLI if the id already exists
     * on disk ({@code Error: Session ID <id> is already in
     * use.}, empirically confirmed) — but {@code start()} always defaults to
     * create mode. If this session id already exists in grok's on-disk store,
     * switch the freshly started manager to resume it instead, so an in-place
     * restart or reopen of an existing session (e.g. on IDE restart, or
     * reopening the chat tab) behaves like a resume rather than failing
     * outright on the next message.
     */
    @Override
    protected void afterStart() {
        if (currentSession != null && isStoredSessionValid(currentSession.id())) {
            delegate.resumeSession(currentSession.id());
        }
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        return GrokUsageSignalsReader.sessionExists(sessionId);
    }
}
