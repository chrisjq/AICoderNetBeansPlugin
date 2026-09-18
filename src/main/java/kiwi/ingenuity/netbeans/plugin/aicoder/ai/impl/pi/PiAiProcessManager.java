package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiThinkingLevelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiToolResultEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiPersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui.PiSessionControl;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.JsonUtils;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/*
 * ============================================================================================================
 * CROSS-PACKAGE CONTRACT (Task 7 / WP-5). WP-1's classes (PiPersistentSession, PiStreamJsonParser, PiJsonKeyEnum,
 * PiRpcCommandEnum, PiEventTypeEnum, PiTimeoutEnum, events/*) landed while this file was being written and it was
 * reconciled against their real, delivered shapes below — no more guessing for those. WP-2's PiAiMcpRegistrar
 * (extension/* package) has NOT landed yet; this file still references it as a forward reference (same package, so no
 * import statement is at risk of being stripped by OrganiseImports — see the boss's note that stripping only bites
 * cross-package imports of not-yet-existing classes).
 *
 * PiPersistentSession.send(JsonObject command) — ONE argument: the command object must already carry
 * PiJsonKeyEnum.COMMAND (e.g. PiRpcCommandEnum.PROMPT.command()) plus any parameters as top-level properties; the
 * session adds type/id itself. Returns the FULL {type,command,success,data?,error?} response frame — a response that
 * never arrives leaves the future pending forever (no built-in timeout), so callers that need a bound apply
 * PiTimeoutEnum.RPC_RESPONSE_TIMEOUT_MILLIS themselves via orTimeout().
 *
 * PiStreamJsonParser does NOT surface get_state/get_available_models/get_session_stats/get_available_thinking_levels/
 * set_model/set_thinking_level responses at all (PiSessionInfoEvent and PiUsageEvent were deleted — pi has no quota,
 * and per-session data must not go on the type-global AiTypePropertyBus; PiModelsEvent still exists but is fired only
 * by PiModelDiscovery's own no-session discovery path, not by anything session-scoped). Per PiSessionControl's own
 * javadoc ("the process manager translates whatever it receives internally... into calls on Listener"), THIS class
 * reads every one of those response frames directly off the matching send() future for ITS OWN session instead — see
 * fetchInitialPickers/refreshThinkingLevels/refreshContextUsage/setModel/setThinkingLevel below.
 *
 * Field names confirmed live (Codex_1, relayed by the Boss 2026-09-18): get_state -> data.thinkingLevel (string),
 * data.model {id, provider, ...}; get_session_stats -> data.contextUsage {tokens, contextWindow, percent};
 * get_available_thinking_levels -> data.levels; get_available_models -> data.models[{id, provider, ...}]. All now
 * PiJsonKeyEnum constants (THINKING_LEVEL, ID, TOKENS, CONTEXT_WINDOW) — read via those below, not literals.
 * ============================================================================================================
 */
/**
 * Manages pi via ONE long-lived {@code pi --mode rpc} process per plugin session (see the persistent-session contract
 * above). Mirrors {@code ClaudeAiProcessManager}'s shape and threading, adapted for pi's id-correlated request/response
 * RPC instead of Claude's fire-and-forget stream-json, and implements {@link PiSessionControl} so
 * {@code PiAiInfoBarExtension} (WP-3) can drive the model/thinking-level pickers without knowing pi's wire format.
 *
 * <p>
 * <b>Like Claude, the process is spawned LAZILY</b> — on the first {@link #sendPrompt}, not in {@link #start}. The
 * Boss's decision (2026-09-18): correct cwd matters more than eager READY; {@link AiProcessManager#start} carries no
 * working-directory parameter, and the real project root is only known once {@link #sendPrompt} is called with one
 * (same as Claude — see {@code ClaudeAiProcessManager#ensureSession}, which spawns from {@code sendPrompt}'s
 * {@code workingDir} too). {@link #start} fires READY immediately after MCP registration succeeds, exactly like Claude;
 * until the first prompt actually spawns pi and {@link #fetchInitialPickers} runs, the info bar shows
 * {@code PiPluginSettings.getKnownModels()} and the session's stored model/level (already handled by
 * {@code PiAiInfoBarExtension}'s constructor seeding from {@code PiSessionSettings} — no change needed there).
 *
 * <p>
 * <b>No F5 mail-interrupt hold.</b> Claude and OpenCode both hold a Mail interrupt while a plugin tool call is in
 * flight, because their own interrupt mechanism would otherwise abort that call. pi's {@code steer} command is queued
 * natively by pi itself and never touches a running tool call (spec, verified live: "the running tool is not
 * interrupted; the steered text arrives as a new user message at the start of the next turn") — so
 * {@link #interrupt(InterruptTypeEnum)}'s {@code Mail} case below needs none of that machinery.
 */
public class PiAiProcessManager extends AiProcessManager implements PiSessionControl {

    private static final Logger LOG = Logger.getLogger(PiAiProcessManager.class.getName());
    private static final int MAX_STDERR_LINES = 100;

    private volatile PiAiMcpRegistrar registrar = null;
    private volatile PiAiSession piAiSession = null;
    protected volatile PiPersistentSession persistentSession = null;
    private volatile String piSessionId;
    private volatile String configuredThinkingLevel;
    private volatile String launchedModel;
    private volatile String launchedThinkingLevel;
    private int launchCount = 0;
    private final List<String> recentStderr = new CopyOnWriteArrayList<>();

    private volatile boolean awaitingCancelResult = false;
    int cancelWatchdogMillis = 5000;

    /**
     * Set by {@code interrupt(Cancel)} when the user stops a turn; consumed exactly once by the next {@link
     * #sendPrompt} — never anywhere else, so it cannot be lost by a stray sendPrompt() guard failure or burned by a
     * launch that never actually reaches pi (live finding, Boss 2026-09-19). pi's own {@code abort} produces plain
     * tool-result text ("Command aborted") with no signal distinguishing "the user cancelled this" from "this tool
     * genuinely failed" — verified live: a real pi session read that text as an error and said it would have retried
     * the command without the notice. A boolean, not a counter/queue: two cancels before either is ever consumed
     * collapse into one notice, matching every other cancel-related flag on this class.
     */
    private volatile boolean pendingCancelNotice = false;

    /**
     * Wording modelled on {@code AiTopComponent.INBOX_INTERRUPT_EXPLANATION} — the one existing precedent in this
     * codebase for telling the model what an interruption actually was: direct, second person, states the fact and the
     * one instruction that matters, no hedging.
     */
    private static final String CANCEL_NOTICE = "Your previous turn was stopped by the user. The interrupted tool "
            + "call did not complete; this was not an error. Do not retry it unless the user asks.\n\n";

    /**
     * Gates the FIRST {@link #sendPrompt} after a spawn until the extension's {@code session_start} tool-registration
     * handler finishes — pi does not await that handler before accepting a prompt, so a prompt arriving during the
     * registration window (a real HTTP handshake: initialize / notifications/initialized / tools/list) sees zero plugin
     * tools (live finding, Boss 2026-09-19, reproduced deterministically: ~150ms after spawn loses every tool, ~3s
     * after spawn is clean). Created in {@link #ensureSession} BEFORE the process (and its reader thread) exists, on
     * every NEW spawn — the extension can notify within microseconds of starting, so creating this any later loses the
     * signal to that race. {@link #sendPrompt} captures it but leaves the field itself alone until its wait actually
     * finishes, since {@link #buildParserListener}'s release check reads this same field: nulling it out before the
     * wait starts would leave a signal arriving mid-wait with nothing to count down. Released early by that notify-text
     * match — success or failure, either way no MORE tools are coming, so there is nothing left to wait for.
     */
    private volatile CountDownLatch toolsRegisteredLatch;

    /**
     * Whole-attempt bound on {@link #toolsRegisteredLatch}'s wait. Comfortably above the ~3s mark Boss's live repro
     * confirmed clean, while still bounded — a hung or never-registering extension must never wedge the session; the
     * prompt is sent regardless once this elapses (logged at FINE). Package-private and mutable, like {@link
     * #cancelWatchdogMillis}, so tests can shrink it instead of waiting out the real bound.
     */
    long toolsRegisteredWaitMillis = 5_000L;

    /**
     * The two {@code ctx.ui.notify} prefixes {@code aicoder-pi-extension.ts.template}'s {@code registerMcpTools} emits
     * at the end of EVERY path, success or failure — see that function's own javadoc ("KEEP BOTH STRINGS EXACTLY AS
     * WRITTEN — the Java side matches on them verbatim"). Each is followed by a dynamic suffix (a tool count, or an
     * error message), hence a prefix match rather than equality. Both count as "registration is done, stop waiting" — a
     * failure means no tools are coming, not that some are.
     */
    private static final String TOOLS_REGISTERED_PREFIX = "AI Coder MCP tools registered: ";
    private static final String TOOLS_UNAVAILABLE_PREFIX = "AI Coder MCP tools unavailable: ";

    /**
     * Test seam: when non-null, {@link #ensureSession} uses this instead of {@code registrar.getExtensionFilePath()} —
     * lets state/threading tests exercise launch without needing a real {@code PiAiMcpRegistrar}/extension file on
     * disk. Null in production.
     */
    String extensionPathForTests = null;

    /**
     * Test seam: every internal {@code reg.deleteExtensionFile()} call (stop, failed start, unexpected process exit,
     * file-generation failure — see the spec's *Extension file generation and lifetime* and {@code PiAiMcpRegistrar}'s
     * "Known limitation on deletion" javadoc) routes through this, so tests can assert it ran on every path without a
     * live {@code McpServerRegistry}/on-disk extension file. Defaults to the real method in production.
     */
    Consumer<PiAiMcpRegistrar> deleteExtensionFile = PiAiMcpRegistrar::deleteExtensionFile;

    private volatile PiSessionControl.Listener controlListener;
    private volatile String currentModelSelection;
    private volatile String currentThinkingLevelSelection;
    private volatile PiVersionCheck versionCheck;

    public PiAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    /**
     * Test seam mirroring {@code ClaudeAiProcessManager#launchPersistentSession}: overridden in tests to return a
     * scripted fake instead of spawning a real {@code pi} process.
     */
    protected PiPersistentSession launchPersistentSession(List<String> cmd, File workDir,
                                                          Consumer<String> eventLine, Consumer<String> stderrLine) throws IOException {
        return PiPersistentSession.launch(cmd, workDir, eventLine, stderrLine);
    }

    boolean isAwaitingCancelResult() {
        return awaitingCancelResult;
    }

    int getLaunchCount() {
        return launchCount;
    }

    PiPersistentSession getPersistentSession() {
        return persistentSession;
    }

    /**
     * The pi-facing {@code --session-id}, separate from the plugin's own {@link #sessionId} (spec *Session id and
     * resume*: "The plugin generates a UUID for a new session and stores it in PiSessionSettings"). Read by
     * {@code PiAiImplementation.onStarted} to persist a freshly generated id back into {@code PiSessionSettings}.
     */
    public String getPiSessionId() {
        return piSessionId;
    }

    /**
     * The result of the {@code pi --version} check run at the start of {@link #start}, or {@code null} if it has not
     * run yet or failed. Read by {@code PiAiImplementation.createInfoBarExtension} to construct
     * {@code PiAiInfoBarExtension}; see that class's {@code setVersionCheck} for the case where the info bar is built
     * before this is known.
     */
    public PiVersionCheck getVersionCheck() {
        return versionCheck;
    }

    /**
     * Test seam: {@code versionCheck} is otherwise only ever set inside {@link #start} (after a real {@code pi
     * --version} probe on the async executor) — lets a test drive {@code PiAiImplementation.onStarted}'s version-check
     * hand-off without running a live executable.
     */
    void setVersionCheckForTests(PiVersionCheck versionCheck) {
        this.versionCheck = versionCheck;
    }

    /**
     * Launch-time default thinking level, from {@code PiSessionSettings.thinkingLevel()} (session) or
     * {@code PiPluginSettings.getThinkingLevel()} (global default) — set by {@code PiAiImplementation} before
     * {@link #start}, mirroring how {@link #setSessionConfigDir} is set. Deliberately not named
     * {@code setThinkingLevel} — that name is {@link PiSessionControl#setThinkingLevel}, which sends a LIVE
     * {@code set_thinking_level} RPC command instead of just configuring the next launch.
     */
    public void configureThinkingLevel(String level) {
        this.configuredThinkingLevel = (level == null || level.isBlank()) ? null : level;
    }

    /**
     * Test seam: {@code executablePath} is otherwise only ever set inside {@link #start}, which a pure
     * {@link #buildLaunchCommand} test has no reason to call. Inherited from {@link AiProcessManager} in a different
     * package, so tests (not a subclass) cannot reach the protected field directly.
     */
    void setExecutablePathForTests(String path) {
        this.executablePath = path;
    }

    /**
     * Test seam: {@code running} is otherwise only ever set inside {@link #start} — lets a test exercise
     * {@code compact()}'s caller-side running guard (and its own null-session guard, via a directly-assigned
     * {@link #persistentSession}) without a live executable or MCP registration. Inherited from
     * {@link AiProcessManager} in a different package, so tests (not a subclass) cannot reach the protected field
     * directly — same reasoning as {@link #setExecutablePathForTests}.
     */
    void setRunningForTests(boolean running) {
        this.running = running;
    }

    /**
     * Test seam: {@code registrar} is otherwise only ever set inside {@link #start}, which threading/lifecycle tests
     * have no reason to call — lets a test assert {@link #deleteExtensionFile} runs on {@link #stop}/unexpected exit
     * without going through real MCP registration. Companion seam to {@link #extensionPathForTests}.
     */
    void setRegistrarForTests(PiAiMcpRegistrar reg) {
        this.registrar = reg;
    }

    @Override
    public synchronized void start(String executablePath, String model) {
        stop();
        this.executablePath = executablePath;
        this.model = model;

        if (!PiExecutableLocator.isExecutableFile(executablePath)) {
            running = false;
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                                                      StatusMessageUtil.formatStartFailed("executable not found at " + executablePath)));
            return;
        }
        if (currentSession == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                                                      StatusMessageUtil.formatSessionNotConfigured()));
            return;
        }
        sessionId = currentSession.id();
        String storedPiSessionId = currentSession.settings() instanceof PiSessionSettings ps ? ps.piSessionId() : null;
        piSessionId = resolvePiSessionId(storedPiSessionId, piSessionId);

        if (registrar != null) {
            McpServerRegistry.deregister(registrar);
            registrar = null;
        }
        PiAiMcpRegistrar reg = new PiAiMcpRegistrar(sessionId, executablePath);
        boolean mcpReady;
        try {
            mcpReady = McpServerRegistry.register(reg).get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mcpReady = false;
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "MCP registration failed for pi session " + sessionId, e);
            mcpReady = false;
        }
        if (!mcpReady) {
            deleteExtensionFile.accept(reg); // idempotent cleanup — a failed registration may still have generated the file
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                                                      StatusMessageUtil.formatMcpSetupFailed()));
            return;
        }
        registrar = reg;
        piAiSession = new PiAiSession(currentSession, listener);
        running = true;

        // spec *Process lifecycle*, step 2: "Runs the version check". Best-effort — a failed/slow `pi --version`
        // (e.g. a flaky executable) must not block start(); it only means the info bar's warning button stays
        // hidden this session (PiVersionCheck.isWarningApplies() is false for a blank/unknown installed version).
        try {
            versionCheck = new PiVersionCheck(PiExecutableLocator.testExecutable(executablePath));
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "pi --version check failed; version warning suppressed for this session", e);
            versionCheck = null;
        }

        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("Pi")));
    }

    /**
     * Pure selection logic pinned by a dedicated test (Review round 2, BigP_2): prefers a stored pi session id over
     * minting a fresh one, rather than minting unconditionally in {@link #start} and relying on
     * {@code PiAiImplementation.afterStart()}'s later {@link #resumeSession} call to silently overwrite it. Falls back
     * to the given in-memory id (already minted from an earlier {@link #start} on this same instance) before minting a
     * brand new one, so a restart never mints twice for no reason.
     */
    static String resolvePiSessionId(String storedPiSessionId, String currentPiSessionId) {
        if (storedPiSessionId != null && !storedPiSessionId.isBlank()) {
            return storedPiSessionId;
        }
        if (currentPiSessionId != null && !currentPiSessionId.isBlank()) {
            return currentPiSessionId;
        }
        return UUID.randomUUID().toString();
    }

    List<String> buildLaunchCommand(String sid, String extensionPath) {
        List<String> args = new ArrayList<>();
        args.add("--mode");
        args.add("rpc");
        args.add("--session-id");
        args.add(sid);
        args.add("-e");
        args.add(extensionPath);
        if (model != null && !model.isBlank()) {
            args.add("--model");
            args.add(model);
        }
        if (configuredThinkingLevel != null && !configuredThinkingLevel.isBlank()) {
            args.add("--thinking");
            args.add(configuredThinkingLevel);
        }
        return PiExecutableLocator.buildHostCommand(executablePath, args.toArray(String[]::new));
    }

    private void addStderr(String line) {
        recentStderr.add(line);
        while (recentStderr.size() > MAX_STDERR_LINES) {
            recentStderr.remove(0);
        }
    }

    /**
     * Wraps {@link #listener} so THINKING/READY/FAILED transitions update {@link #processing} and fire
     * {@link PiSessionControl.Listener#onTurnRunningChanged}, mirroring
     * {@code ClaudeAiProcessManager#buildParserListener}. {@code TurnCompleteEvent} and a READY {@code StatusEvent}
     * both arrive on {@code agent_settled} per {@link PiStreamJsonParser}'s own handling (it emits both together),
     * matching the spec's verified turn order. Also catches {@link PiThinkingLevelChangedEvent} (Sonet's Round-5
     * wire-shape scan: {@code thinking_level_changed} fires when pi changes the level by any means other than our own
     * {@code set_thinking_level} RPC, e.g. normalising an unsupported level) and pushes it into the info bar's picker
     * the same way {@link #fetchInitialPickers} seeds it from {@code get_state} at session start — see that event
     * class's own javadoc for why the hop lives here rather than in the parser.
     */
    private AiProcessEventListener buildParserListener() {
        return event -> {
            boolean isTurnComplete = event instanceof TurnCompleteEvent;
            boolean isTerminal = isTurnComplete
                    || (event instanceof StatusEvent se
                    && (se.type() == StatusEventTypeEnum.FAILED || se.type() == StatusEventTypeEnum.READY));
            if (isTerminal) {
                boolean wasProcessing;
                synchronized (PiAiProcessManager.this) {
                    wasProcessing = processing;
                    processing = false;
                    awaitingCancelResult = false;
                }
                if (wasProcessing) {
                    notifyTurnRunningChanged(false);
                }
                if (isTurnComplete) {
                    refreshContextUsage();
                }
            }
            else if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.THINKING) {
                boolean wasProcessing;
                synchronized (PiAiProcessManager.this) {
                    wasProcessing = processing;
                }
                if (!wasProcessing) {
                    notifyTurnRunningChanged(true);
                }
            }
            else if (event instanceof PiThinkingLevelChangedEvent tlc) {
                currentThinkingLevelSelection = tlc.level();
                notifySelectionChanged();
            }
            else if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO) {
                releaseToolsRegisteredWaitIfSignalled(se.text());
            }
            if (isSuppressedByUserCancel(event)) {
                return;
            }
            listener.onAiProcessEvent(event);
        };
    }

    /**
     * Releases {@link #toolsRegisteredLatch} the moment the extension's own readiness notify arrives — see that field's
     * javadoc and {@code aicoder-pi-extension.ts.template}'s {@code registerMcpTools} for the two exact prefixes this
     * matches. A no-op once the latch has already been consumed (or was never armed), so a stray repeat notify can
     * never do anything surprising.
     */
    private void releaseToolsRegisteredWaitIfSignalled(String text) {
        if (text == null) {
            return;
        }
        if (!text.startsWith(TOOLS_REGISTERED_PREFIX) && !text.startsWith(TOOLS_UNAVAILABLE_PREFIX)) {
            return;
        }
        CountDownLatch latch = toolsRegisteredLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * True for a FAILED status or a failed tool result that is really just the tail end of a turn the user aborted via
     * Stop — pi reports an aborted turn as {@code message_end stopReason:"error" errorMessage:"This operation was
     * aborted"} (and the in-flight tool's own {@code tool_execution_end} as {@code isError:true}), NOT
     * {@code stopReason:"aborted"}, when the abort lands DURING a tool call (live finding, Boss 2026-09-18 — a plain
     * mid-thinking abort does use {@code "aborted"}, which {@link PiStreamJsonParser#parseMessageEnd} already renders
     * nothing for). {@link #interrupt(InterruptTypeEnum)}'s {@code Cancel} case already shows the STOPPED status
     * synchronously; without this, the aborted turn's own tail repaints that clean stop as a red failure a moment
     * later. Keyed on {@link #cancelledByUser}, not the error text — text-matching would also swallow a genuine failure
     * that happens to mention "aborted". A non-error tool result in this same window is left alone: only the
     * FAILED/error shapes that are artifacts of the abort itself are suppressed.
     */
    private boolean isSuppressedByUserCancel(AiProcessEvent event) {
        boolean cancelled;
        synchronized (this) {
            cancelled = cancelledByUser;
        }
        if (!cancelled) {
            return false;
        }
        if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.FAILED) {
            return true;
        }
        return event instanceof PiToolResultEvent tre && tre.isError();
    }

    private synchronized PiPersistentSession ensureSession(File workDir) throws IOException {
        if (persistentSession != null && persistentSession.isAlive()
                && Objects.equals(launchedModel, model) && Objects.equals(launchedThinkingLevel, configuredThinkingLevel)) {
            return persistentSession;
        }
        if (persistentSession != null) {
            persistentSession.close();
            persistentSession = null;
        }
        PiAiMcpRegistrar reg = registrar;
        String extensionPath = extensionPathForTests != null ? extensionPathForTests
                               : (reg != null ? reg.getExtensionFilePath() : null);
        if (extensionPath == null) {
            if (reg != null) {
                deleteExtensionFile.accept(reg); // idempotent cleanup for a partial/failed generation attempt
            }
            // spec *Extension file generation and lifetime*: "a failure to write it is a FATAL start error".
            throw new IOException("pi extension file not available — MCP registration did not complete");
        }
        List<String> cmd = buildLaunchCommand(piSessionId, extensionPath);
        AiProcessEventListener parserListener = buildParserListener();
        PiStreamJsonParser p = new PiStreamJsonParser(parserListener);
        recentStderr.clear();
        final String sidForLog = sessionId;
        // Armed BEFORE the process (and its reader thread) exists, not after launchPersistentSession returns: the
        // extension can emit its readiness notify within microseconds of starting, and buildParserListener's release
        // check reads this same field — creating the latch afterward lost the signal to that race every time (live
        // finding, Boss 2026-09-19, build-1: all three signal-dependent tests always ran the full timeout).
        toolsRegisteredLatch = new CountDownLatch(1);
        PiPersistentSession launched;
        try {
            launched = launchPersistentSession(cmd, workDir,
                                               line -> {
                                                   if (PluginSettings.isDebugJson()) {
                                                       LOG.log(Level.INFO, "pi event [{0}]: {1}", new Object[]{sidForLog, line});
                                                   }
                                                   p.parseLine(line);
                                               },
                                               err -> {
                                                   if (PluginSettings.isDebugJson()) {
                                                       LOG.log(Level.WARNING, "pi stderr [{0}]: {1}", new Object[]{sidForLog, err});
                                                   }
                                                   addStderr(err);
                                               });
        }
        catch (IOException e) {
            // The extension file was already generated (extensionPath != null above) but the process never came up
            // — without this, the file would survive until a later stop()/IDE shutdown despite the session never
            // having started (Review C).
            if (reg != null) {
                deleteExtensionFile.accept(reg);
            }
            throw e;
        }
        p.setUiResponseSender(launched::sendRawLine);
        persistentSession = launched;
        launchedModel = model;
        launchedThinkingLevel = configuredThinkingLevel;
        launchCount++;
        launched.process().onExit().thenRun(() -> handleProcessExit(launched));
        fetchInitialPickers(launched);
        return persistentSession;
    }

    private static JsonObject command(PiRpcCommandEnum cmd) {
        JsonObject o = new JsonObject();
        o.addProperty(PiJsonKeyEnum.COMMAND.key(), cmd.command());
        return o;
    }

    private static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future) {
        return withTimeout(future, PiTimeoutEnum.RPC_RESPONSE_TIMEOUT_MILLIS.millis());
    }

    private static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, long millis) {
        return future.orTimeout(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Sends {@code get_state}, {@code get_available_models} and {@code get_available_thinking_levels} to fill the
     * info-bar pickers once the process has actually spawned (lazily, from the first {@link #sendPrompt} — see the
     * class javadoc). Each command is independent and best-effort: a failure on one must not block the others. Reads
     * the raw response {@code data} directly rather than relying on {@link PiStreamJsonParser}'s parallel event stream
     * — see the class-level cross-package-contract note for why.
     */
    private void fetchInitialPickers(PiPersistentSession session) {
        withTimeout(session.send(command(PiRpcCommandEnum.GET_STATE))).whenComplete((resp, ex) -> {
            if (ex != null || !isSuccess(resp)) {
                return;
            }
            JsonObject data = dataOf(resp);
            if (data == null) {
                return;
            }
            currentModelSelection = combinedModel(data, PiJsonKeyEnum.MODEL.key());
            currentThinkingLevelSelection = JsonUtils.getString(data, PiJsonKeyEnum.THINKING_LEVEL.key());
            notifySelectionChanged();
        });
        withTimeout(session.send(command(PiRpcCommandEnum.GET_AVAILABLE_MODELS))).whenComplete((resp, ex) -> {
            List<String> models = (ex == null && isSuccess(resp)) ? extractModelList(resp) : List.of();
            if (models.isEmpty()) {
                models = List.of(PiPluginSettings.getKnownModels());
            }
            notifyAvailableModels(models);
        });
        refreshThinkingLevels(session);
        refreshContextUsage();
    }

    /**
     * {@code get_state}'s {@code data.model} (and each entry of {@code get_available_models}'s {@code data.models}) is
     * an object {@code {id, provider, ...}} — confirmed live (Codex_1, relayed by the Boss 2026-09-18), not the plain
     * "provider/id" string originally assumed. Combines into the "provider/id" display form the info bar and
     * {@code PiSessionSettings} use everywhere else.
     */
    private static String combinedModel(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        JsonObject modelObj = parent.getAsJsonObject(key);
        String id = JsonUtils.getString(modelObj, PiJsonKeyEnum.ID.key());
        String provider = JsonUtils.getString(modelObj, PiJsonKeyEnum.PROVIDER.key());
        if (id == null || id.isBlank()) {
            return null;
        }
        return (provider != null && !provider.isBlank()) ? provider + "/" + id : id;
    }

    private void refreshThinkingLevels(PiPersistentSession session) {
        withTimeout(session.send(command(PiRpcCommandEnum.GET_AVAILABLE_THINKING_LEVELS)))
                .whenComplete((resp, ex) -> {
                    if (ex != null || !isSuccess(resp)) {
                        return;
                    }
                    JsonObject data = dataOf(resp);
                    List<String> levels = new ArrayList<>();
                    if (data != null && data.has(PiJsonKeyEnum.LEVELS.key()) && data.get(PiJsonKeyEnum.LEVELS.key()).isJsonArray()) {
                        data.getAsJsonArray(PiJsonKeyEnum.LEVELS.key()).forEach(el -> {
                            if (!el.isJsonNull()) {
                                levels.add(el.getAsString());
                            }
                        });
                    }
                    if (!levels.isEmpty()) {
                        PiSessionControl.Listener l = controlListener;
                        if (l != null) {
                            l.onAvailableThinkingLevelsChanged(levels);
                        }
                    }
                });
    }

    /**
     * Sends {@code get_session_stats} and pushes {@code data.contextUsage} straight to
     * {@link PiSessionControl.Listener#onContextUsageChanged} — spec: "fed from get_session_stats.contextUsage after
     * each turn". {@link PiStreamJsonParser} does not surface this response at all (there is no more
     * {@code PiUsageEvent} — deleted along with {@code PiSessionInfoEvent}, since per-session data must not go on the
     * type-global {@code AiTypePropertyBus}), so the raw token counts this interface needs are read directly off this
     * command's own {@code send()} future instead.
     */
    private void refreshContextUsage() {
        PiPersistentSession s = persistentSession;
        if (s == null) {
            return;
        }
        withTimeout(s.send(command(PiRpcCommandEnum.GET_SESSION_STATS))).whenComplete((resp, ex) -> {
            if (ex != null || !isSuccess(resp)) {
                return;
            }
            JsonObject data = dataOf(resp);
            if (data == null || !data.has(PiJsonKeyEnum.CONTEXT_USAGE.key())
                    || !data.get(PiJsonKeyEnum.CONTEXT_USAGE.key()).isJsonObject()) {
                return;
            }
            JsonObject contextUsage = data.getAsJsonObject(PiJsonKeyEnum.CONTEXT_USAGE.key());
            long used = JsonUtils.getLong(contextUsage, PiJsonKeyEnum.TOKENS.key());
            long window = JsonUtils.getLong(contextUsage, PiJsonKeyEnum.CONTEXT_WINDOW.key());
            if (window > 0) {
                PiSessionControl.Listener l = controlListener;
                if (l != null) {
                    l.onContextUsageChanged((int) used, (int) window);
                }
            }
        });
    }

    private static boolean isSuccess(JsonObject response) {
        return response != null && response.has(PiJsonKeyEnum.SUCCESS.key())
                && response.get(PiJsonKeyEnum.SUCCESS.key()).getAsBoolean();
    }

    private static JsonObject dataOf(JsonObject response) {
        return response != null && response.has(PiJsonKeyEnum.DATA.key()) && response.get(PiJsonKeyEnum.DATA.key()).isJsonObject()
               ? response.getAsJsonObject(PiJsonKeyEnum.DATA.key()) : null;
    }

    private static String errorOf(JsonObject response) {
        if (response == null) {
            return "no response";
        }
        String err = JsonUtils.getString(response, PiJsonKeyEnum.ERROR.key());
        return err != null ? err : "pi command failed";
    }

    /**
     * {@code get_available_models}'s {@code data.models} is an array of {@code {id, provider, ...}} objects — confirmed
     * live (Codex_1, relayed by the Boss 2026-09-18); see {@link #combinedModel}.
     */
    private static List<String> extractModelList(JsonObject response) {
        JsonObject data = dataOf(response);
        List<String> out = new ArrayList<>();
        if (data != null && data.has(PiJsonKeyEnum.MODELS.key()) && data.get(PiJsonKeyEnum.MODELS.key()).isJsonArray()) {
            data.getAsJsonArray(PiJsonKeyEnum.MODELS.key()).forEach(el -> {
                if (el.isJsonObject()) {
                    String id = JsonUtils.getString(el.getAsJsonObject(), PiJsonKeyEnum.ID.key());
                    String provider = JsonUtils.getString(el.getAsJsonObject(), PiJsonKeyEnum.PROVIDER.key());
                    if (id != null && !id.isBlank()) {
                        out.add((provider != null && !provider.isBlank()) ? provider + "/" + id : id);
                    }
                }
            });
        }
        return out;
    }

    private void notifySelectionChanged() {
        PiSessionControl.Listener l = controlListener;
        if (l != null) {
            l.onCurrentSelectionChanged(currentModelSelection, currentThinkingLevelSelection);
        }
    }

    private void notifyAvailableModels(List<String> models) {
        PiSessionControl.Listener l = controlListener;
        if (l != null) {
            l.onAvailableModelsChanged(models);
        }
    }

    private void notifyTurnRunningChanged(boolean running) {
        PiSessionControl.Listener l = controlListener;
        if (l != null) {
            l.onTurnRunningChanged(running);
        }
    }

    /**
     * Called when the persistent process exits (spec, verified: an idle {@code pi --mode rpc} exits with code 0 on
     * stdin close, and this is also the unexpected-exit path). Unwedges the current turn; running stays true so the
     * next message relaunches via {@link #ensureSession} — mirrors {@code ClaudeAiProcessManager#handleProcessExit}.
     * Deletes this session's extension file (idempotent) now that its pi process is gone — {@code getExtensionFilePath}
     * regenerates a fresh one on the next {@link #ensureSession} if the session relaunches.
     */
    private void handleProcessExit(PiPersistentSession dead) {
        boolean suppress;
        boolean wasProcessing;
        PiAiMcpRegistrar reg;
        synchronized (this) {
            if (persistentSession != dead) {
                return;
            }
            suppress = cancelledByUser;
            wasProcessing = processing;
            processing = false;
            persistentSession = null;
            awaitingCancelResult = false;
            reg = registrar;
        }
        try {
            int code = dead.process().exitValue();
            // A turn in flight when the process dies leaves a pending assistant response with no terminal status even
            // on a clean exit(0) — e.g. pi killed externally mid-turn — so that case must still report EXITED rather
            // than only the code != 0 case (Codex_1's round-3 finding). An idle or user-cancelled exit stays quiet.
            if (!suppress && (code != 0 || wasProcessing)) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                                                          StatusMessageUtil.formatExited("Pi", code, new ArrayList<>(recentStderr))));
            }
            notifyTurnRunningChanged(false);
        }
        finally {
            if (reg != null) {
                deleteExtensionFile.accept(reg);
            }
        }
    }

    /**
     * Deliberately NOT a {@code synchronized} method (unlike most of this class's mutators) — {@link
     * #toolsRegisteredLatch}'s wait below can run for up to {@link #TOOLS_REGISTERED_WAIT_MILLIS}, and blocking that
     * long while holding this instance's monitor would freeze out every OTHER synchronized method on it for the same
     * stretch, including {@link #interrupt}'s Stop path (its {@code Cancel} case takes this same lock) — turning a
     * bounded wait into an apparent hang of the Stop button (live finding, Boss 2026-09-19). The guard check, {@link
     * #ensureSession}, and the {@code processing}/latch bookkeeping still run as one atomic {@code synchronized(this)}
     * block, exactly as before; only the wait itself, and the final send, happen outside it.
     */
    @Override
    public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        PiPersistentSession session;
        CountDownLatch waitForToolsRegistered;
        synchronized (this) {
            if (pendingDiff || !running || processing) {
                return;
            }
            cancelledByUser = false;

            if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
                sessionWorkingDir = workingDir;
            }
            File effectiveWorkDir = sessionWorkingDir != null ? sessionWorkingDir : workingDir;

            try {
                session = ensureSession(effectiveWorkDir);
            }
            catch (IOException e) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                                                          StatusMessageUtil.formatSendFailed(e.getMessage())));
                return;
            }
            processing = true;
            // Captured here, but deliberately NOT nulled out yet — see the finally block below for why.
            waitForToolsRegistered = toolsRegisteredLatch;
        }
        notifyTurnRunningChanged(true);
        if (waitForToolsRegistered != null) {
            try {
                if (!waitForToolsRegistered.await(toolsRegisteredWaitMillis, TimeUnit.MILLISECONDS)) {
                    LOG.log(Level.FINE, "Timed out waiting for pi''s MCP tool registration; sending the first prompt "
                            + "anyway (session={0})", sessionId);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finally {
                // Only cleared now, not at capture time above: buildParserListener's release check reads this same
                // field, so nulling it before the wait actually starts would make a signal arriving DURING the wait
                // unable to find the latch it needs to count down — the exact bug build-1 caught (every signal-
                // dependent test ran the full timeout regardless of when the notify actually arrived). Guarded by
                // identity so a concurrent relaunch's fresh latch (vanishingly unlikely mid-wait, but not impossible)
                // is never clobbered by this stale reference.
                synchronized (this) {
                    if (toolsRegisteredLatch == waitForToolsRegistered) {
                        toolsRegisteredLatch = null;
                    }
                }
            }
        }
        // Consumed here, not before ensureSession(): a launch failure above returns without ever reaching pi, so the
        // notice must not be burned on an attempt that was never actually sent (live finding, Boss 2026-09-19).
        String effectiveText = text;
        synchronized (this) {
            if (pendingCancelNotice) {
                pendingCancelNotice = false;
                effectiveText = CANCEL_NOTICE + text;
            }
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "pi prompt [{0}]: {1}", new Object[]{sessionId, effectiveText});
        }
        JsonObject cmd = command(PiRpcCommandEnum.PROMPT);
        cmd.addProperty(PiJsonKeyEnum.MESSAGE.key(), effectiveText);
        // Deliberately no orTimeout: this is the turn's ack, which is expected to arrive quickly, but the turn it
        // starts can legitimately run far longer than any fixed bound.
        session.send(cmd).whenComplete((resp, ex) -> {
            // Only handles the ack failing outright — the turn's real completion (TurnCompleteEvent / READY /
            // FAILED) arrives through buildParserListener() off the event stream, same as every other backend.
            if (ex != null || !isSuccess(resp)) {
                boolean wasProcessing;
                synchronized (PiAiProcessManager.this) {
                    wasProcessing = processing;
                    processing = false;
                }
                if (wasProcessing) {
                    notifyTurnRunningChanged(false);
                }
                String reason = ex != null ? ex.getMessage() : errorOf(resp);
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                                                          StatusMessageUtil.formatSendFailed(reason)));
            }
        });
    }

    @Override
    public void interrupt(InterruptTypeEnum type) {
        PiPersistentSession s;
        switch (type) {
            case Mail -> {
                // The check-then-send must be one atomic step under this lock: send() itself is non-blocking (it
                // hands the frame to PiPersistentSession's own stdin write and returns a future immediately), so
                // holding the lock across it costs nothing. Releasing the lock between the "processing" check and
                // s.send(cmd) let the turn settle in the gap — the steer then landed on an idle session (pi queues
                // it as a new user message) while the generic idle-delivery path sent the same notice too, so the
                // notice could appear twice (Review round 2, BigP_2).
                synchronized (this) {
                    s = persistentSession;
                    if (s == null || !processing) {
                        // Idle: the generic idle-delivery path calls sendPrompt() itself, same as every backend —
                        // nothing to do here. See the class javadoc: pi needs no F5 hold, since steer is queued
                        // natively by pi and never touches a running tool call.
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.INFO, "Pi interrupt: Mail IGNORED (session={0}, sessionAlive={1}, turnInFlight={2})",
                                    new Object[]{sessionId, s != null, processing});
                        }
                        return;
                    }
                    JsonObject cmd = command(PiRpcCommandEnum.STEER);
                    cmd.addProperty(PiJsonKeyEnum.MESSAGE.key(), InterruptTypeEnum.MAIL_NOTIFICATION_TEXT);
                    s.send(cmd); // fire-and-forget: verified to answer {success:true} immediately, never aborts a running tool
                }
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Pi interrupt: steer sent (session={0})", sessionId);
                }
            }
            case Cancel -> {
                synchronized (this) {
                    s = persistentSession;
                    if (!processing) {
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.INFO, "Pi interrupt: IGNORED, no turn in flight (session={0})", sessionId);
                        }
                        return;
                    }
                    cancelledByUser = true;
                    pendingCancelNotice = true;
                    processing = false;
                    awaitingCancelResult = s != null;
                }
                notifyTurnRunningChanged(false);
                if (s != null) {
                    s.send(command(PiRpcCommandEnum.ABORT)); // answers only after agent_end -> agent_settled (verified)
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "Pi interrupt: abort sent (session={0})", sessionId);
                    }
                }
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
                if (s != null) {
                    startCancelWatchdog(s);
                }
            }
        }
    }

    /**
     * Force-closes the session if pi never answers {@code abort} — mirrors
     * {@code ClaudeAiProcessManager#startCancelWatchdog}. Deletes the extension file itself (mirroring {@link #stop})
     * rather than relying on {@link #handleProcessExit}'s own delete: nulling {@link #persistentSession} here BEFORE
     * {@code s.close()} makes handleProcessExit's {@code persistentSession != dead} guard skip it once the process
     * actually dies, which would otherwise leak the file until IDE shutdown/startup sweep (Review A).
     */
    private void startCancelWatchdog(PiPersistentSession s) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(cancelWatchdogMillis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            PiAiMcpRegistrar reg;
            synchronized (PiAiProcessManager.this) {
                if (!awaitingCancelResult || persistentSession != s) {
                    return;
                }
                persistentSession = null;
                awaitingCancelResult = false;
                reg = registrar;
            }
            try {
                s.close();
            }
            finally {
                if (reg != null) {
                    deleteExtensionFile.accept(reg);
                }
            }
        }, "pi-cancel-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @Override
    public synchronized void stop() {
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Pi stop: shutting session down (session={0}, turnInFlight={1}, sessionAlive={2})",
                    new Object[]{sessionId, processing, persistentSession != null});
        }
        running = false;
        processing = false;
        cancelledByUser = true;
        awaitingCancelResult = false;
        // Incidental fix while touching this reset block (Boss 2026-09-19's tools-registered task, not itself part
        // of it): stop() is also start()'s own reset-before-restart step, and a pendingCancelNotice left over from a
        // Stop that was never followed by another sendPrompt() would otherwise leak onto the FIRST prompt of a brand
        // new, never-interrupted session after a restart.
        pendingCancelNotice = false;

        PiPersistentSession s = persistentSession;
        persistentSession = null;
        if (s != null) {
            s.close();
        }
        PiAiSession sessionToDispose = piAiSession;
        piAiSession = null;
        if (sessionToDispose != null) {
            sessionToDispose.dispose();
        }
        PiAiMcpRegistrar reg = registrar;
        registrar = null;
        if (reg != null) {
            try {
                McpServerRegistry.deregister(reg);
            }
            finally {
                // McpServerRegistry only runs unregisterHooks() (which also deletes the file) for the LAST session
                // of AiTypeEnum.PI still registered — a middle session closing needs this direct, idempotent call
                // too, or its extension file leaks until the next IDE-shutdown sweep. See PiAiMcpRegistrar's own
                // "Known limitation on deletion" javadoc.
                deleteExtensionFile.accept(reg);
            }
        }

        sessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigDir = null;
        recentStderr.clear();
        launchedModel = null;
        launchedThinkingLevel = null;
        launchCount = 0;
        currentModelSelection = null;
        currentThinkingLevelSelection = null;
        versionCheck = null;
    }

    /**
     * pi's {@code --session-id} creates-or-resumes uniformly (spec *Sessions*: "uses an exact session id, creating it
     * if missing") — unlike Claude, which needs a distinct {@code --resume} flag and therefore a
     * {@code launchedProjectDirs}/{@code firstMessage}-style guard. Simply adopting {@code existingSessionId} as
     * {@link #piSessionId} is enough; the very next {@link #ensureSession} launches with it either way. Mirrors
     * {@code CodexAiImplementation.resumeSession}'s pattern (a backend-specific stored id, not the plugin's own session
     * id) rather than Claude's (same id for both) — see {@link #getPiSessionId()}'s javadoc.
     */
    @Override
    public synchronized void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        piSessionId = existingSessionId;
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }

    // ---- PiSessionControl ----
    @Override
    public void setListener(PiSessionControl.Listener listener) {
        this.controlListener = listener;
    }

    @Override
    public CompletableFuture<Void> setModel(String provider, String modelId) {
        PiPersistentSession s = persistentSession;
        if (s == null) {
            return CompletableFuture.completedFuture(null);
        }
        JsonObject cmd = command(PiRpcCommandEnum.SET_MODEL);
        cmd.addProperty(PiJsonKeyEnum.PROVIDER.key(), provider);
        cmd.addProperty(PiJsonKeyEnum.MODEL_ID.key(), modelId);
        String combined = (provider != null && !provider.isBlank()) ? provider + "/" + modelId : modelId;
        return withTimeout(s.send(cmd)).thenAccept(resp -> {
            // A response for a session that has since been stopped/replaced must not overwrite state or trigger a
            // refresh on whatever session is now current (Codex_1's round-3 finding) — discard rather than a
            // generation token, since the sent-to session reference is already captured above.
            if (!isSuccess(resp) || persistentSession != s) {
                return;
            }
            model = combined;
            launchedModel = combined; // avoid a spurious relaunch on the next turn — see ensureSession()'s guard
            currentModelSelection = combined;
            notifySelectionChanged();
            refreshThinkingLevels(s);
        });
    }

    @Override
    public CompletableFuture<Void> setThinkingLevel(String level) {
        PiPersistentSession s = persistentSession;
        if (s == null) {
            return CompletableFuture.completedFuture(null);
        }
        JsonObject cmd = command(PiRpcCommandEnum.SET_THINKING_LEVEL);
        cmd.addProperty(PiJsonKeyEnum.LEVEL.key(), level);
        return withTimeout(s.send(cmd)).thenAccept(resp -> {
            // Same stale-session guard as setModel above.
            if (!isSuccess(resp) || persistentSession != s) {
                return;
            }
            configuredThinkingLevel = level;
            launchedThinkingLevel = level;
            currentThinkingLevelSelection = level;
            notifySelectionChanged();
        });
    }

    @Override
    public boolean isTurnRunning() {
        return processing;
    }

    /**
     * Sends {@code compact}; the caller (mirroring {@code ClaudeAiImplementation.compact}) is responsible for refusing
     * this while a turn is running with the shared message wording — see spec *Process lifecycle*, Compact. Completes
     * exceptionally when there is no session, the send itself fails, or pi answers with {@code success:false} — the
     * caller must only suppress the next turn's echo once acceptance is confirmed (Codex_1's round-3 finding: acting on
     * dispatch alone left a suppressed turn stranded whenever pi rejected or lost the compact, or exited first). Uses
     * {@link PiTimeoutEnum#COMPACT_RESPONSE_TIMEOUT_MILLIS}, not the generic {@link #withTimeout(CompletableFuture)} —
     * a very long transcript could plausibly take pi longer than the generic 30s bound to ack, and a timeout here
     * surfaces as "Compact failed" even when pi actually finished the compaction (round-4 review, BigP_2 F3).
     */
    public CompletableFuture<Void> compact() {
        PiPersistentSession s = persistentSession;
        if (s == null) {
            return CompletableFuture.failedFuture(new IOException("Pi session is not running"));
        }
        return withTimeout(s.send(command(PiRpcCommandEnum.COMPACT)), PiTimeoutEnum.COMPACT_RESPONSE_TIMEOUT_MILLIS.millis())
                .thenCompose(resp -> {
                    if (!isSuccess(resp)) {
                        return CompletableFuture.failedFuture(new IOException(errorOf(resp)));
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }
}
