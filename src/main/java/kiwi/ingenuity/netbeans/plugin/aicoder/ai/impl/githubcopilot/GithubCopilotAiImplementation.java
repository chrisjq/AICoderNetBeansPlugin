package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui.GithubCopilotAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui.GithubCopilotInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * GitHub Copilot backend implementation for NetBeans.
 *
 * Integrates GitHub Copilot (Codex) API to drive the shared AiTopComponent chat
 * UI and reuses the shared MCP tool server for IDE introspection, edits,
 * builds, git, etc.
 *
 * Implementation uses GitHub Copilot API (REST) or VS Code extension protocol.
 * Manages session lifecycle, authentication, and model selection.
 */
public class GithubCopilotAiImplementation extends AiImplementation {

    // The discovered model list is shared across all Copilot sessions for the
    // IDE run (like Claude): discover once, cache, and broadcast to every open
    // session's dropdown via AiTypePropertyBus — so opening 4 sessions at once
    // populates all four, not just the one that triggered discovery.
    private static final AiModelCatalog MODEL_CATALOG = new AiModelCatalog();
    private static volatile GithubCopilotQuotaEvent cachedQuotaEvent;

    public static AiModelCatalog modelCatalog() {
        return MODEL_CATALOG;
    }

    public static void addModelCatalogListener(java.util.function.Consumer<List<String>> listener) {
        MODEL_CATALOG.addListener(listener);
    }

    public static void removeModelCatalogListener(java.util.function.Consumer<List<String>> listener) {
        MODEL_CATALOG.removeListener(listener);
    }

    public static void publishQuota(GithubCopilotQuotaEvent event) {
        cachedQuotaEvent = event;
        AiTypePropertyBus.getInstance().fire(AiTypeEnum.GitHubCoPilot, event);
    }

    /**
     * Discover the Copilot model list once per IDE run, then broadcast it to
     * every open Copilot session's info bar via {@link AiTypePropertyBus} —
     * mirroring the Claude flow. A session opened after discovery already
     * completed replays the cached list immediately. Without this, only the
     * session that happened to trigger discovery got the loaded list.
     */
    public static void triggerModelDiscovery() {
        if (!MODEL_CATALOG.beginRefresh()) {
            return;
        }
        GithubCopilotModelDiscovery.discoverAsync(GithubCopilotExecutableLocator.locate(), models -> {
            List<String> list = Arrays.asList(models);
            if (MODEL_CATALOG.publish(list)) {
                AiTypePropertyBus.getInstance().fire(AiTypeEnum.GitHubCoPilot, new GithubCopilotModelsEvent(list));
            }
        });
    }
    private final GithubCopilotProcessManager processManager;

    public GithubCopilotAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.GitHubCoPilot, listener, prompter);
        this.processManager = new GithubCopilotProcessManager(listener);
    }

    @Override
    protected GithubCopilotProcessManager delegate() {
        return processManager;
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = (model != null && !model.isBlank())
                ? model : GithubCopilotPluginSettings.getModel();
        String execPath = GithubCopilotExecutableLocator.locate();
        if (execPath != null) {
            // start() can block on MCP registration, so run it off the EDT.
            start(execPath, effectiveModel);
            return;
        }
        listener.onAiProcessEvent(new kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent(
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum.FAILED,
                "GitHub Copilot CLI not found — install it or set the path in Options"));
        listener.onAiProcessEvent(new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotFatalErrorEvent(
                "EXECUTABLE_NOT_FOUND", "GitHub Copilot CLI not found"));
    }

    @Override
    public void setModel(String model) {
        GithubCopilotPluginSettings.setModel(model);
        // A CopilotSession binds its model at create/resume time, so changing the
        // model on a live session has no effect until the session is rebuilt.
        // recycleForModelChange() closes and clears it immediately (cheap, no RPC —
        // this runs on the EDT from the info-bar combo listener); the next send
        // re-establishes it on a background thread with the new model, preserving
        // the conversation via the retained copilotSessionId.
        processManager.setModel(model);
        processManager.recycleForModelChange();
        if (currentSession != null) {
            AiSessionSettings cfg = currentSession.settings();
            if (model != null && !model.equals(cfg instanceof AiModelSessionSettings mc
                    ? mc.model() : null)) {
                if (cfg instanceof AiModelSessionSettings modelCfg) {
                    modelCfg.setModel(model);
                }
            }
        }
    }

    @Override
    public GithubCopilotAiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        GithubCopilotAiInfoBarExtension provider = new GithubCopilotAiInfoBarExtension(session, host);
        Consumer<List<String>> catalogListener = models -> provider.setAvailableModels(models.toArray(String[]::new));
        MODEL_CATALOG.addListener(catalogListener);
        provider.setDisposeAction(() -> MODEL_CATALOG.removeListener(catalogListener));
        provider.addListener(new GithubCopilotInfoBarListener() {
            @Override
            public void onCompactRequested() {
                compact(host);
            }

            @Override
            public void onModelChanged(String model) {
            }
        });
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
                processManager.setCurrentSession(currentSession);
                host.updateSessionSettings(cfg);
            }
        });

        String initialModel = session.settings() instanceof AiModelSessionSettings modelCfg && modelCfg.model() != null
                ? modelCfg.model() : GithubCopilotPluginSettings.getModel();
        provider.setSelectedModel(initialModel);
        if (initialModel != null && session.settings() instanceof AiModelSessionSettings modelSettings && modelSettings.model() == null) {
            modelSettings.setModel(initialModel);
            host.updateSessionSettings(modelSettings);
        }

        // Phase 1: discover the real available model list (best-effort; falls
        // back silently to the hardcoded list). Discovery runs once per IDE run
        // and the result is broadcast to EVERY open Copilot session's dropdown.
        triggerModelDiscovery();
        GithubCopilotQuotaEvent cached = cachedQuotaEvent;
        if (cached != null) {
            provider.onPropertyEvent(cached);
        }
        return provider;
    }

    private void compact(AiSessionHost host) {
        if (!isRunning() || isProcessing()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Wait for GitHub Copilot to finish before compacting"));
            return;
        }

        sendPrompt("Please compact this conversation by creating a concise summary of the key context, decisions, and code changes made so far. Do not reply.", host.resolveWorkDir(), List.of());

        if (isProcessing()) {
            host.suppressNextTurn("Compacting conversation...", null);
        }
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        return true;
    }

    @Override
    public void onStarted(AiSessionHost session) {
        // No-op. Copilot is driven exclusively in prompt mode (`copilot -p`),
        // where memory is disabled by default (the only switch is the opt-in
        // `--enable-memory`, which GithubCopilotProcessManager never passes).
        // The user's ~/.copilot/settings.json also persists "memory": false.
        // So there is nothing to do on start or resume — a previous "/memory off"
        // turn here was redundant and cost a startup round-trip every session.
    }

    @Override
    protected void afterStart() {
    }
}
