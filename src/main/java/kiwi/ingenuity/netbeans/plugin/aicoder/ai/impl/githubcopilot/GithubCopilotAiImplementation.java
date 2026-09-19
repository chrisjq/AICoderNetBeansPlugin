package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui.GithubCopilotAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui.GithubCopilotInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * GitHub Copilot backend implementation for NetBeans.
 *
 * Integrates GitHub Copilot (Codex) API to drive the shared AiTopComponent chat UI and reuses the shared MCP tool
 * server for IDE introspection, edits, builds, git, etc.
 *
 * Implementation uses GitHub Copilot API (REST) or VS Code extension protocol. Manages session lifecycle,
 * authentication, and model selection.
 */
public class GithubCopilotAiImplementation extends AiImplementation {

    private static final Logger LOG = Logger.getLogger(GithubCopilotAiImplementation.class.getName());

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
     * Discover the Copilot model list once per IDE run, then broadcast it to every open Copilot session's info bar via
     * {@link AiTypePropertyBus} — mirroring the Claude flow. A session opened after discovery already completed replays
     * the cached list immediately. Without this, only the session that happened to trigger discovery got the loaded
     * list.
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
    private volatile AiSessionHost sessionHost;
    private volatile GithubCopilotAiInfoBarExtension infoBar;

    public GithubCopilotAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.GitHubCoPilot, listener, prompter);
        this.processManager = new GithubCopilotProcessManager(listener);
        this.processManager.setOnModelFallback(this::applyModelFallback);
        this.processManager.setOnReasoningEffortCleared(this::handleReasoningEffortCleared);
    }

    @Override
    protected GithubCopilotProcessManager delegate() {
        return processManager;
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = resolveStartupModel(model);
        String execPath = GithubCopilotExecutableLocator.locate();
        if (execPath != null) {
            // Copilot's session is built EAGERLY inside start() (unlike pi's lazy
            // spawn-on-first-prompt), so the effort must be on the process manager
            // BEFORE start() runs — afterStart() would be too late. Provenance rides
            // along per design-spec rule 3a: only a session-pinned effort may be
            // cleared+persisted+INFOed when the model doesn't support it; a value
            // inherited from the global default is silently omitted for this session.
            processManager.setReasoningEffort(resolveEffectiveReasoningEffort(), resolveEffectiveReasoningEffortFromSession() != null);
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

    /**
     * Resolves the model to start a session with: an explicit argument first, then the session's own choice, then the
     * global default. Mirrors OpenCodeAiImplementation.resolveStartupModel and
     * CodexAiImplementation.resolveStartupModel — extracted so it can be tested without going through
     * {@link GithubCopilotExecutableLocator#locate}, which depends on what is actually installed on the machine.
     *
     * <p>
     * AiTopComponent always calls {@code startWithDiscovery(null)}, so falling straight to the global default here — as
     * this used to do — silently ran a session on {@code GithubCopilotPluginSettings.getModel()} regardless of what the
     * user had picked for it. The info bar, session settings and ListAiSessions all kept reporting the session's own
     * model, so the mismatch was invisible. Same bug already found and fixed for OpenCode and Codex; Claude, Grok and
     * Ollama check session settings via their own getCurrentModel()/inline equivalents.
     */
    String resolveStartupModel(String model) {
        String sessionModel = currentSession != null
                && currentSession.settings() instanceof AiModelSessionSettings s
                && s.model() != null && !s.model().isBlank()
                              ? s.model() : null;
        String effectiveModel = (model != null && !model.isBlank())
                                ? model
                                : sessionModel != null ? sessionModel : GithubCopilotPluginSettings.getModel();
        if (PluginSettings.isDebugJson()) {
            String source = (model != null && !model.isBlank()) ? "explicit argument"
                            : sessionModel != null ? "session setting" : "global default";
            LOG.log(Level.INFO, "GitHub Copilot requested model=\"{0}\" at session start (source: {1})",
                    new Object[]{effectiveModel, source});
        }
        return effectiveModel;
    }

    /**
     * Resolves the reasoning effort to apply at session construction: the session's own stored value wins over the
     * global default. {@code null} means "omit the setting entirely" at every stage — mirrors
     * {@link #resolveStartupModel} and {@code PiAiImplementation.effectiveThinkingLevel()}. Package-private for direct
     * unit testing.
     */
    String resolveEffectiveReasoningEffort() {
        String sessionEffort = resolveEffectiveReasoningEffortFromSession();
        if (sessionEffort != null) {
            return sessionEffort;
        }
        String globalDefault = GithubCopilotPluginSettings.getReasoningEffort();
        return (globalDefault == null || globalDefault.isBlank()) ? null : globalDefault;
    }

    /**
     * The session's own stored reasoning effort, or {@code null} if it has none. Not the effective value — the
     * companion {@link #resolveEffectiveReasoningEffort()} falls back to the global default — this tells you which
     * scope {@code resolveEffectiveReasoningEffort()} resolved from, which is exactly the provenance design-spec rule
     * 3a keys on: a session-sourced value may be cleared when unsupported, a global-sourced one may not.
     * Package-private for direct unit testing.
     */
    String resolveEffectiveReasoningEffortFromSession() {
        return currentSession != null
                && currentSession.settings() instanceof GithubCopilotSessionSettings s
                && s.reasoningEffort() != null && !s.reasoningEffort().isBlank()
               ? s.reasoningEffort() : null;
    }

    @Override
    public void setModel(String model) {
        // Session-scoped change — deliberately does not write the global default.
        // The global default (Tools → Options) is owned solely by the settings panel.
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

    /**
     * Session-scoped reasoning-effort change, mirroring {@link #setModel(String)}: a CopilotSession binds its reasoning
     * effort at create/resume time just like the model, so a live change requires rebuilding the session — reusing
     * {@link GithubCopilotProcessManager#recycleForModelChange()} rather than a second restart path, per the design
     * spec.
     */
    public void setReasoningEffort(String effort) {
        processManager.setReasoningEffort(effort);
        processManager.recycleForModelChange();
        if (currentSession != null && currentSession.settings() instanceof GithubCopilotSessionSettings settings
                && !java.util.Objects.equals(effort, settings.reasoningEffort())) {
            settings.setReasoningEffort(effort);
        }
    }

    @Override
    public GithubCopilotAiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        GithubCopilotAiInfoBarExtension provider = new GithubCopilotAiInfoBarExtension(session, host);
        this.sessionHost = host;
        this.infoBar = provider;
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

            @Override
            public void onReasoningEffortChanged(String effort) {
                setReasoningEffort(effort);
                AiSessionSettings cfg = host.getSessionSettings();
                if (cfg instanceof GithubCopilotSessionSettings ghSettings) {
                    if (!java.util.Objects.equals(effort, ghSettings.reasoningEffort())) {
                        ghSettings.setReasoningEffort(effort);
                    }
                    processManager.setCurrentSession(currentSession);
                    host.updateSessionSettings(cfg);
                }
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

        // Seed the combo from the session's own stored value, falling back to the global default for display only —
        // unlike the model, an unset reasoning effort is never written back into session settings just because it
        // is shown: doing so would turn "inherits the global default" into a pinned value that survives a later
        // global-default change, breaking the session-wins-over-global rule.
        provider.setSelectedReasoningEffort(resolveDisplayReasoningEffort(
                session.settings() instanceof GithubCopilotSessionSettings ghSettings ? ghSettings : null));

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

    /**
     * Applies the model the CLI fell back to when the requested one was not available for the account: session
     * settings, persisted, then the info-bar dropdown. Wired to {@link GithubCopilotProcessManager#setOnModelFallback}
     * in the constructor, so it is always connected regardless of whether the info bar or {@code onStarted} has run
     * yet.
     *
     * <p>
     * Deliberately does not call {@link #setModel} — that would additionally recycle the Copilot session while we are
     * already handling a start failure. Package-private rather than private so the test can call it directly, matching
     * {@link #resolveStartupModel}.
     */
    void applyModelFallback(String model) {
        if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings settings) {
            settings.setModel(model);
        }
        AiSessionHost host = sessionHost;
        if (host != null && currentSession != null) {
            host.updateSessionSettings(currentSession.settings());
        }
        GithubCopilotAiInfoBarExtension bar = infoBar;
        if (bar != null) {
            bar.setSelectedModel(model);
        }
    }

    /**
     * Resolves what the reasoning-effort combo should display for {@code settings}: its own stored value if present,
     * else the global default, for display only — shared by {@link #createInfoBarExtension}'s initial seed and
     * {@link #handleReasoningEffortCleared}, which both need exactly the same fallback.
     */
    private static String resolveDisplayReasoningEffort(GithubCopilotSessionSettings settings) {
        String effort = settings != null ? settings.reasoningEffort() : null;
        if (effort != null && !effort.isBlank()) {
            return effort;
        }
        String globalDefault = GithubCopilotPluginSettings.getReasoningEffort();
        return (globalDefault == null || globalDefault.isBlank()) ? null : globalDefault;
    }

    /**
     * Wired to {@link GithubCopilotProcessManager#setOnReasoningEffortCleared} in the constructor: fires when
     * {@link GithubCopilotProcessManager#resolveValidatedReasoningEffort} clears a stored-but-unsupported reasoning
     * effort. The manager only clears its own in-memory field — without this, the persisted session setting would keep
     * the stale value forever, re-triggering the same INFO event on every subsequent start. Mirrors
     * {@link #applyModelFallback}: persists the clear into session settings, then updates the info-bar combo (which
     * falls back to the global default for display, same as the initial seed — not blanked to "(model default)"
     * unconditionally). Package-private so the test can call it directly, matching {@link #applyModelFallback}.
     */
    void handleReasoningEffortCleared() {
        GithubCopilotSessionSettings settings = currentSession != null
                && currentSession.settings() instanceof GithubCopilotSessionSettings s ? s : null;
        if (settings != null) {
            settings.setReasoningEffort(null);
        }
        AiSessionHost host = sessionHost;
        if (host != null && currentSession != null) {
            host.updateSessionSettings(currentSession.settings());
        }
        GithubCopilotAiInfoBarExtension bar = infoBar;
        if (bar != null) {
            bar.setSelectedReasoningEffort(resolveDisplayReasoningEffort(settings));
        }
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
        // Retain the host so applyModelFallback can persist session settings
        // even if the info bar has not been created or has been recreated.
        this.sessionHost = session;
        // Nothing else to do here. Copilot is driven exclusively in prompt mode (`copilot -p`),
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
