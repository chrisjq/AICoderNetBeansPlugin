package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.nio.file.Path;
import java.util.Locale;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui.PiAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui.PiInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Thin adapter so the generic multi-AI system (AiSession, AiTopComponent, etc.) can use the pi
 * implementation, mirrors {@code ClaudeAiImplementation}'s role. Unlike Claude, pi needs no credential
 * monitor or its own model/usage-fetch machinery — {@link PiModelDiscovery} already discovers models via
 * {@code pi --list-models} and publishes straight into {@link #modelCatalog()} on its own; this class only
 * wires the process manager, session paths, the pi session id used for {@code --session-id}, and the info
 * bar.
 */
public class PiAiImplementation extends AiImplementation {

    private static final AiModelCatalog MODEL_CATALOG = new AiModelCatalog(AiTypeEnum.PI);

    public static AiModelCatalog modelCatalog() {
        return MODEL_CATALOG;
    }

    private final PiAiProcessManager delegate;

    public PiAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.PI, listener, prompter);
        this.delegate = new PiAiProcessManager(listener);
    }

    @Override
    protected PiAiProcessManager delegate() {
        return delegate;
    }

    public String getCurrentModel() {
        if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings mc && mc.model() != null) {
            return mc.model();
        }
        return PiPluginSettings.getModel();
    }

    private String effectiveThinkingLevel() {
        if (currentSession != null && currentSession.settings() instanceof PiSessionSettings ps
            && ps.thinkingLevel() != null && !ps.thinkingLevel().isBlank()) {
            return ps.thinkingLevel();
        }
        String global = PiPluginSettings.getThinkingLevel();
        return (global != null && !global.isBlank()) ? global : null;
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = (model != null && !model.isBlank()) ? model : getCurrentModel();
        String execPath = PiExecutableLocator.locate();
        if (execPath != null) {
            // Executable found: start exactly once, as ClaudeAiImplementation does — a failure unrelated to the
            // executable (e.g. MCP setup) must not trigger a second start() and a duplicate MCP registration.
            start(execPath, effectiveModel);
            return;
        }

        String chosen;
        try {
            chosen = prompter.promptForExecutable("Locate pi executable", "pi").get();
        }
        catch (Exception ex) {
            chosen = null;
        }
        if (chosen != null) {
            PiPluginSettings.setExecutable(chosen);
            start(chosen, effectiveModel);
        }
        else {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, StatusMessageUtil.formatExecutableNotFound(null)));
        }
    }

    /**
     * pi's process spawns lazily on the first {@link #sendPrompt} (see {@code PiAiProcessManager}'s class
     * javadoc), so — unlike an earlier draft of this method — there is no race to beat: setting these after
     * {@code delegate.start()} returns, exactly like {@code ClaudeAiImplementation.afterStart()}, is soon
     * enough, since the first real {@code ensureSession()} call is always well after this method returns.
     */
    @Override
    protected void afterStart() {
        Path configPath = getSessionConfigPath();
        if (configPath != null) {
            delegate.setSessionConfigDir(configPath);
        }
        if (currentSession != null && currentSession.settings() instanceof PiSessionSettings ps) {
            String storedPiSessionId = ps.piSessionId();
            if (storedPiSessionId != null && !storedPiSessionId.isBlank()) {
                delegate.resumeSession(storedPiSessionId);
            }
            delegate.configureThinkingLevel(effectiveThinkingLevel());
        }
    }

    @Override
    public void setModel(String model) {
        // Session-scoped default — deliberately does not write the global default (Options owns that). Unlike
        // Claude/Codex, no session recycle: pi switches models live via `set_model` on the running process (the
        // info bar's model listener drives that through PiAiProcessManager.setModel, not this method). This only updates what the
        // NEXT (re)spawn of the CLI process launches with, e.g. after an unexpected exit.
        if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings mc) {
            mc.setModel(model);
        }
        delegate.setModel(model);
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        // pi's --session-id creates-or-resumes transparently (spec *Sessions*: "creating it if missing") — there is
        // no failure mode where a stale/missing id blocks the session, unlike Claude's --resume. Mirrors Codex's and
        // OpenCode's identical rationale for their own isStoredSessionValid().
        return true;
    }

    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        PiSessionSettings settings = session.settings() instanceof PiSessionSettings ps ? ps : null;
        PiAiInfoBarExtension provider = new PiAiInfoBarExtension(settings, delegate.getVersionCheck(),
                MODEL_CATALOG.getCachedModels());
        delegate.sessionSnapshot().forEach(provider::onAiProcessImplEvent);
        provider.addListener(new PiInfoBarListener() {
            @Override
            public void onModelChanged(String providerSlashId) {
                int slash = providerSlashId.indexOf('/');
                delegate.setModel(slash > 0 ? providerSlashId.substring(0, slash) : "",
                        slash > 0 ? providerSlashId.substring(slash + 1) : providerSlashId)
                        .thenRun(() -> {
                            if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings modelCfg) {
                                modelCfg.setModel(providerSlashId);
                                host.updateSessionSettings(modelCfg);
                            }
                        });
            }

            @Override
            public void onThinkingLevelChanged(String level) {
                delegate.setThinkingLevel(level).thenRun(() -> {
                    if (currentSession != null && currentSession.settings() instanceof PiSessionSettings piCfg) {
                        piCfg.setThinkingLevel(level);
                        host.updateSessionSettings(piCfg);
                    }
                });
            }

            @Override
            public void onVersionVerified(PiVersionCheck check) {
                PiPluginSettings.setVerifiedVersion(check.installedVersion());
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus.getInstance().fire(
                        AiTypeEnum.PI, new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionVerifiedEvent());
            }

            @Override
            public void onVersionMarkedNotWorking(PiVersionCheck check) {
                check.markNotWorkingThisSession();
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus.getInstance().fire(
                        AiTypeEnum.PI, new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionVerifiedEvent());
            }

            @Override
            public void onCompactRequested() {
                compact();
            }
        });
        return provider;
    }

    /**
     * Runs pi's non-turn compact RPC through the shared busy/ready contract. The parser owns the progress
     * message for automatic and manual compactions; this operation owns the single manual closing status.
     */
    private void compact() {
        if (!isRunning() || delegate().isAwaitingCancelResult()
            || (isBusy() && !delegate().isWorkInFlight())) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, "Wait for pi to finish before compacting"));
            return;
        }
        boolean started = delegate.runWork("Compacting conversation…", false,
                PiTimeoutEnum.COMPACT_RESPONSE_TIMEOUT_MILLIS.millis(),
                delegate::compactUnlessSessionEnds,
                ignored -> new StatusEvent(StatusEventTypeEnum.READY, "Conversation compacted."),
                this::compactFailureStatus);
        if (!started) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, "Compaction already in progress"));
        }
    }

    private StatusEvent compactFailureStatus(Throwable error) {
        String detail = error.getMessage() != null ? error.getMessage() : "unknown error";
        String normalized = detail.toLowerCase(Locale.ROOT);
        if (normalized.contains("nothing to compact") || normalized.contains("already compacted")) {
            return new StatusEvent(StatusEventTypeEnum.READY, "Nothing to compact.");
        }
        return new StatusEvent(StatusEventTypeEnum.FAILED, "Compact failed: " + detail);
    }

    /**
     * Persists a freshly generated pi session id (minted by {@code PiAiProcessManager.start} when
     * {@code PiSessionSettings.piSessionId()} was still empty) back into the session's settings, so the NEXT
     * reopen resumes the same pi conversation instead of minting another id and starting fresh.
     * {@code start()} cannot do this itself — it has no {@link AiSessionHost} to call
     * {@code updateSessionSettings} on.
     */
    @Override
    public void onStarted(AiSessionHost session) {
        if (currentSession != null && currentSession.settings() instanceof PiSessionSettings ps) {
            String live = delegate.getPiSessionId();
            if (live != null && !live.isBlank() && !live.equals(ps.piSessionId())) {
                ps.setPiSessionId(live);
                session.updateSessionSettings(ps);
            }
        }
    }
    // No registerLifecycleListeners() override: unlike Claude's usage-limit polling, pi's context gauge is pushed
    // by get_session_stats after every turn as a PiContextUsageEvent (see PiAiInfoBarExtension), and models are
    // discovered once by PiModelDiscovery, not per-session — the base class's no-op is correct as-is.
}
