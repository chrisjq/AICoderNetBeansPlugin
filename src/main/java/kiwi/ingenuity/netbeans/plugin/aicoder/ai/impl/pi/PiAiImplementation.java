package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final AiModelCatalog MODEL_CATALOG = new AiModelCatalog();

    public static AiModelCatalog modelCatalog() {
        return MODEL_CATALOG;
    }

    private final PiAiProcessManager delegate;

    /**
     * The info bar {@link #createInfoBarExtension} builds, kept so {@link #onStarted} can push the version
     * check into it once {@code start()}'s async version probe has actually completed — see
     * {@link #onStarted}'s javadoc for why that hand-off cannot happen at construction time. Null until an
     * info bar has been built for this session; a session can be reopened (a fresh {@code AiTopComponent}, a
     * fresh info bar) without a new {@code PiAiImplementation}, so this is reassigned rather than set-once.
     */
    private volatile PiAiInfoBarExtension infoBarProvider;

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
        } catch (Exception ex) {
            chosen = null;
        }
        if (chosen != null) {
            PiPluginSettings.setExecutable(chosen);
            start(chosen, effectiveModel);
        } else {
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
        // info bar drives that through PiSessionControl.setModel, not this method). This only updates what the
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
        PiAiInfoBarExtension provider = new PiAiInfoBarExtension(delegate, settings, delegate.getVersionCheck());
        provider.addListener(new PiInfoBarListener() {
            @Override
            public void onModelChanged(String providerSlashId) {
                if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings modelCfg) {
                    modelCfg.setModel(providerSlashId);
                    host.updateSessionSettings(modelCfg);
                }
            }

            @Override
            public void onThinkingLevelChanged(String level) {
                if (currentSession != null && currentSession.settings() instanceof PiSessionSettings piCfg) {
                    piCfg.setThinkingLevel(level);
                    host.updateSessionSettings(piCfg);
                }
            }

            @Override
            public void onCompactRequested() {
                compact(host);
            }
        });
        infoBarProvider = provider;
        return provider;
    }

    /**
     * Re-entrancy guard, same reason as {@code GithubCopilotAiImplementation}'s: a compact is an RPC, not a
     * turn, so {@code isProcessing()} is false for its whole duration and the info bar's Compact button —
     * disabled only while a TURN runs (see {@code PiAiInfoBarExtension.setCombosEnabledNow}) — would stay
     * live without it. {@link AiSessionHost#setCompacting} now disables the button too; this remains the
     * authority, because a button-only guard still races the click already in flight.
     */
    private final AtomicBoolean compactInFlight = new AtomicBoolean();

    /**
     * Refuses while a turn is running (same wording as Claude, backend name substituted), otherwise sends the
     * {@code compact} RPC command, holding the session busy via {@link AiSessionHost#setCompacting} from
     * dispatch until pi answers, and releasing it on success and failure alike.
     *
     * <p>
     * Deliberately does NOT call {@code host.suppressNextTurn}. This method used to, on the premise that pi
     * emits an echo turn after a compaction that needed hiding. Verified against pi 0.87 with raw JSON
     * logging, it does not: the complete sequence is {@code compaction_start} → {@code compaction_end} → {@code response compact
     * success:true}, and nothing follows. {@code suppressNextTurn} holds the session busy until a
     * {@code TurnCompleteEvent} arrives, so with no turn coming the input stayed locked indefinitely — and
     * because {@link #compactInFlight} had already cleared, Compact was pressable again into a session the
     * user could not type into.
     *
     * <p>
     * No status message is set here: pi's own {@code compaction_start}/{@code compaction_end} frames surface
     * "Compacting conversation…" and "Conversation compacted." from a single source, via the parser —
     * including for an AUTOMATIC threshold compaction the plugin never requested.
     */
    private void compact(AiSessionHost host) {
        if (!isRunning() || isProcessing()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, "Wait for pi to finish before compacting"));
            return;
        }
        if (!compactInFlight.compareAndSet(false, true)) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, "Compaction already in progress"));
            return;
        }
        host.setCompacting(true);
        delegate.compact().whenComplete((v, ex) -> {
            compactInFlight.set(false);
            host.setCompacting(false);
            if (ex != null) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        "Compact failed: " + (ex.getMessage() != null ? ex.getMessage() : "unknown error")));
            }
        });
    }

    /**
     * Persists a freshly generated pi session id (minted by {@code PiAiProcessManager.start} when
     * {@code PiSessionSettings.piSessionId()} was still empty) back into the session's settings, so the NEXT
     * reopen resumes the same pi conversation instead of minting another id and starting fresh.
     * {@code start()} cannot do this itself — it has no {@link AiSessionHost} to call
     * {@code updateSessionSettings} on.
     *
     * <p>
     * Also pushes the version check into the info bar: {@code
     * AiTopComponent} calls {@link #createInfoBarExtension} SYNCHRONOUSLY, before {@code start()} — which
     * runs on the async executor and is the only place {@code PiAiProcessManager.versionCheck} is ever set —
     * has had a chance to run. Without this hand-off {@link PiAiInfoBarExtension#setVersionCheck} is never
     * called by anything and the ⚠ button/verify dialog can never appear. {@code onStarted} already runs on
     * the EDT right after startup completes, so the check is available by the time this runs.
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
        PiAiInfoBarExtension provider = infoBarProvider;
        if (provider != null) {
            provider.setVersionCheck(delegate.getVersionCheck());
        }
    }
    // No registerLifecycleListeners() override: unlike Claude's usage-limit polling, pi's context gauge is pushed
    // by get_session_stats after every turn via PiSessionControl.Listener directly (see PiAiInfoBarExtension), and
    // models are discovered once by PiModelDiscovery, not per-session — the base class's no-op is correct as-is.
}
