package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import java.awt.Component;
import java.util.Arrays;
import java.util.List;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.ui.GithubCopilotAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.ui.GithubCopilotInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;
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

    private final GithubCopilotProcessManager processManager;

    // The discovered model list is shared across all Copilot sessions for the
    // IDE run (like Claude): discover once, cache, and broadcast to every open
    // session's dropdown via AiTypePropertyBus — so opening 4 sessions at once
    // populates all four, not just the one that triggered discovery.
    private static final Object MODEL_LOCK = new Object();
    private static volatile List<String> cachedModels = null;
    private static volatile boolean modelsFetched = false;

    public GithubCopilotAiImplementation(AiProcessEventListener listener) {
        super(AiTypeEnum.GitHubCoPilot, listener);
        this.processManager = new GithubCopilotProcessManager(listener);
    }

    @Override
    protected GithubCopilotProcessManager delegate() {
        return processManager;
    }

    @Override
    public void startWithDiscovery(String model, Component parent) {
        String effectiveModel = (model != null && !model.isBlank())
                ? model : GithubCopilotPluginSettings.getModel();
        String execPath = GithubCopilotExecutableLocator.locate();
        if (execPath != null) {
            processManager.start(execPath, effectiveModel);
            return;
        }
        listener.onAiProcessEvent(new kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent(
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum.FAILED,
                "GitHub Copilot CLI not found — install it or set the path in Options"));
        listener.onAiProcessEvent(new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events.GithubCopilotFatalErrorEvent(
                "EXECUTABLE_NOT_FOUND", "GitHub Copilot CLI not found"));
    }

    @Override
    public void setModel(String model) {
        GithubCopilotPluginSettings.setModel(model);
        // Propagate to the process manager so the next `copilot -p --model ...`
        // turn uses the newly selected model. Without this the dropdown change
        // only updated settings and the running session kept the old model.
        processManager.setModel(model);
        if (currentSession != null) {
            AbstractAiSessionSettings cfg = currentSession.settings() != null
                    ? currentSession.settings() : AbstractAiSessionSettings.defaults();
            if (model != null && !model.equals(cfg instanceof AbstractAiModelSessionSettings mc
                    ? mc.model() : null)) {
                AbstractAiModelSessionSettings newCfg
                        = new AbstractAiModelSessionSettings(
                                cfg.maxHistory(), cfg.restrictToProjectFiles(), cfg.allowInterAiComms(),
                                cfg.autoNotifyInbox(), cfg.allowImportantMessages(), cfg.sessionInstructions(),
                                model, cfg.autoAccept());
                currentSession.setSettings(newCfg);
            }
        }
    }

    @Override
    public GithubCopilotAiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        GithubCopilotAiInfoBarExtension provider = new GithubCopilotAiInfoBarExtension(session, host);
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
            AbstractAiSessionSettings cfg = host.getSessionSettings() != null
                    ? host.getSessionSettings() : AbstractAiSessionSettings.defaults();
            String currentModel = cfg instanceof AbstractAiModelSessionSettings mc ? mc.model() : null;
            if (!model.equals(currentModel)) {
                AbstractAiModelSessionSettings newCfg
                        = new AbstractAiModelSessionSettings(
                                cfg.maxHistory(), cfg.restrictToProjectFiles(), cfg.allowInterAiComms(),
                                cfg.autoNotifyInbox(), cfg.allowImportantMessages(), cfg.sessionInstructions(),
                                model, cfg.autoAccept());
                if (currentSession != null) {
                    currentSession.setSettings(newCfg);
                }
                processManager.setCurrentSession(currentSession);
                host.updateSessionSettings(newCfg);
            }
        });

        String initialModel = session.settings() instanceof AbstractAiModelSessionSettings modelCfg
                ? modelCfg.model() : GithubCopilotPluginSettings.getModel();
        provider.setSelectedModel(initialModel);
        if (initialModel != null && !(session.settings() instanceof AbstractAiModelSessionSettings)) {
            AbstractAiSessionSettings cfg = session.settings() != null ? session.settings() : AbstractAiSessionSettings.defaults();
            AbstractAiModelSessionSettings modelSettings = new AbstractAiModelSessionSettings(
                    cfg.maxHistory(), cfg.restrictToProjectFiles(), cfg.allowInterAiComms(),
                    cfg.autoNotifyInbox(), cfg.allowImportantMessages(), cfg.sessionInstructions(),
                    initialModel, cfg.autoAccept());
            session.setSettings(modelSettings);
            if (currentSession != null) {
                currentSession.setSettings(modelSettings);
            }
            host.updateSessionSettings(modelSettings);
        }

        // Phase 1: discover the real available model list (best-effort; falls
        // back silently to the hardcoded list). Discovery runs once per IDE run
        // and the result is broadcast to EVERY open Copilot session's dropdown.
        triggerModelDiscovery();
        return provider;
    }

    /**
     * Discover the Copilot model list once per IDE run, then broadcast it to
     * every open Copilot session's info bar via {@link AiTypePropertyBus} —
     * mirroring the Claude flow. A session opened after discovery already
     * completed replays the cached list immediately. Without this, only the
     * session that happened to trigger discovery got the loaded list.
     */
    private void triggerModelDiscovery() {
        if (modelsFetched) {
            List<String> cached;
            synchronized (MODEL_LOCK) {
                cached = cachedModels;
            }
            if (cached != null) {
                List<String> snapshot = cached;
                SwingUtilities.invokeLater(() -> AiTypePropertyBus.getInstance()
                        .fire(AiTypeEnum.GitHubCoPilot, new GithubCopilotModelsEvent(snapshot)));
            }
            return;
        }
        // discoverAsync coalesces concurrent calls (only the first runs) and
        // invokes this callback off the EDT on success. Cache + broadcast so all
        // open Copilot dropdowns update, not just the triggering session.
        GithubCopilotModelDiscovery.discoverAsync(GithubCopilotExecutableLocator.locate(), models -> {
            List<String> list = Arrays.asList(models);
            synchronized (MODEL_LOCK) {
                cachedModels = list;
                modelsFetched = true;
            }
            SwingUtilities.invokeLater(() -> AiTypePropertyBus.getInstance()
                    .fire(AiTypeEnum.GitHubCoPilot, new GithubCopilotModelsEvent(list)));
        });
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
