package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpErrorCodeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpMethodEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpStopReasonEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Manages an OpenCode agent via one long-lived {@code opencode acp} process per plugin session. The process
 * is spawned lazily on the first {@link #sendPrompt} call. MCP wiring is deferred to a later slice.
 *
 * <p>
 * <b>Safety invariant:</b> The child process is always launched with {@code OPENCODE_CONFIG_CONTENT} set to
 * force {@code ask} permission for all file edits, bash commands and external-directory access. Without this,
 * OpenCode's defaults allow silent file mutations even when the client advertises {@code fs.writeTextFile}
 * capability — confirmed by live probe.
 */
public class OpenCodeAiProcessManager extends AbstractAcpProcessManager {

    private static final Logger LOG = Logger.getLogger(OpenCodeAiProcessManager.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /**
     * Developer switch for mid-turn mail steering. NOT a user setting and deliberately not surfaced in the UI
     * — flip it here in source if you are working on it.
     *
     * <p>
     * <b>OFF because it does not work.</b> The mechanism is implemented and every part of it verified against
     * a real agent: the plugin passes {@code --port}, locks the agent's HTTP server with a per-process
     * password, confirms {@code /api/session/…/prompt} is declared in the agent's own OpenAPI document, and
     * POSTs {@code {"prompt":{"text":…},"delivery":"steer"}} to it — {@code delivery} being a real enum in
     * that schema, {@code ["steer","queue"]}. The request is accepted. The agent simply never acts on it
     * until the running turn ends, which is the one thing that would have made it worth doing.
     *
     * <p>
     * Tested on opencode 1.18.23 four ways: a blocking POST with a 5 s bound (timed out, our own timeout
     * closing the connection), the same with a long bound, asynchronous fire-and-forget, and finally a
     * three-phase task with checkpoints after each phase to rule out "the turn was too short to cross a
     * promotion boundary". In every case the peer reported the message arriving only after the whole turn
     * finished. {@code POST …/prompt} waits on the running turn regardless of {@code delivery}; the sibling
     * {@code /session/…/prompt_async} returns immediately but its schema has no {@code delivery} field at
     * all, so it cannot steer.
     *
     * <p>
     * Left in rather than deleted so it can be re-tested cheaply against a future opencode: set this true,
     * rebuild, and send a peer important-flagged mail mid-turn. If their transcript shows it before the turn
     * ends, the upstream behaviour has changed and this can become the default.
     *
     * <p>
     * While off, OpenCode spawns exactly as it always did — no {@code --port}, no probe, no HTTP calls — and
     * reports {@code AFTER_TURN} honestly, because reporting mid-turn capability we cannot deliver would make
     * PeerSessionList lie to every peer that reads it.
     */
    static final boolean EXPERIMENTAL_STEERING = false;
    /**
     * Standing guidance prepended to every turn's prompt text, verbatim from the user. Lives HERE — in the
     * one backend that needs it — rather than in shared AiTypeEnum/ContextProvider code, because it is
     * BEHAVIOUR of this backend's own send path, not configuration a shared component has to read: OpenCode
     * keeps its own bash/grep/read/edit tools and reached for them first, and the FORCE_MCP_TOOL_USE
     * handshake line that told it otherwise is seen exactly once at connect. Prepending inside
     * {@link #sendTurn} means the reminder rides EVERY turn by construction — there is no delta logic or
     * first-send gate on this path to drop it. Contrast the plugin header at ContextProvider.buildPreamble,
     * which is gated on {@code lastSentProjects == null} and fires once per session: that gate is precisely
     * why the existing guidance does not stick, and this text must not share its fate.
     */
    private static final String MCP_TOOL_PREFERENCE = "Use the plugin's MCP tools over internal tools.";

    /**
     * Builds the value for the OPENCODE_CONFIG_CONTENT environment variable. Forces "ask" permission for all
     * edit, bash and external-directory operations, and denies sub-agent spawning outright. This MERGES with
     * the user's existing config — it does not replace it (verified by live probe) — so it constrains only
     * the sessions this plugin launches and leaves the user's own {@code opencode} CLI usage alone.
     */
    static String buildPermissionConfigJson() {
        JsonObject permission = new JsonObject();
        permission.addProperty(AcpJsonKeyEnum.EDIT.key(), "ask");
        permission.addProperty(AcpJsonKeyEnum.BASH.key(), "ask");
        permission.addProperty(AcpJsonKeyEnum.EXTERNAL_DIRECTORY.key(), "ask");
        // Sub-agents run invisibly: they get their own session, never surface in
        // this session's transcript, and their failures are not reported back. A
        // live OpenCode session sat "busy" for 80 minutes after a spawned explore
        // sub-agent died mid-stream with nothing logged — the parent simply waited
        // forever. The plugin also already provides multi-agent work through peer
        // sessions, which ARE visible and interruptible, so nothing is lost.
        //
        // "deny" is OpenCode's own vocabulary here: it applies
        // {"permission":"task","action":"deny"} to every sub-agent it spawns to
        // stop them recursing.
        permission.addProperty(AcpJsonKeyEnum.TASK.key(), "deny");
        JsonObject config = new JsonObject();
        config.add(AcpJsonKeyEnum.PERMISSION.key(), permission);
        return GSON.toJson(config);
    }

    protected Path sharedOpenCodeDatabase() {
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }

    protected String probeOpenCodeVersion() throws IOException, InterruptedException {
        return OpenCodeExecutableLocator.testExecutable(executablePath);
    }

    private OpenCodeStartupCoordinator coordinateOpenCodeStartup() {
        try {
            return OpenCodeStartupCoordinator.acquire(sharedOpenCodeDatabase(), probeOpenCodeVersion());
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Could not probe OpenCode version; starting without migration coordination", e);
            return OpenCodeStartupCoordinator.acquire(null, null);
        }
    }

    /**
     * Builds the per-plugin-session path exemption used by the ACP permission handler.
     */
    Predicate<String> ownSessionConfigFileCheck() {
        return AbstractAcpClientHandler.ownSessionConfigFileCheck(sessionId);
    }

    static List<String> buildAcpCommand(String executablePath, int port) {
        return port > 0
               ? OpenCodeExecutableLocator.buildHostCommand(executablePath, "acp", "--port", Integer.toString(port))
               : OpenCodeExecutableLocator.buildHostCommand(executablePath, "acp");
    }

    protected static JsonObject buildInitializeParams(String pluginVersion) {
        return AbstractAcpProcessManager.buildInitializeParams(pluginVersion);
    }

    static JsonObject buildSessionResumeParams(String acpSessionId, String cwd, String mcpEndpointUrl) {
        return AbstractAcpProcessManager.buildSessionParams(cwd, mcpEndpointUrl, acpSessionId);
    }

    static JsonObject buildSessionNewParams(String absoluteCwd) {
        return buildSessionNewParams(absoluteCwd, null);
    }

    static JsonObject buildSessionNewParams(String absoluteCwd, String mcpEndpointUrl) {
        return AbstractAcpProcessManager.buildSessionParams(absoluteCwd, mcpEndpointUrl, null);
    }

    protected static String resolveSessionId(boolean resumed, String resumeId, JsonObject sessionResult) {
        // session/resume response contains only configOptions; use the requested id directly.
        // session/new always returns sessionId in its response.
        return AbstractAcpProcessManager.resolveSessionId(resumed, resumeId, sessionResult);
    }

    // Package-private like pendingAcpResumeId/sessionConfigOptions below, so tests
    // can inject a pipe-backed AcpConnection and assert wire ordering.
    volatile AcpConnection connection = null;
    volatile String acpSessionId = null;
    volatile String pendingAcpResumeId = null;
    volatile JsonArray sessionConfigOptions = null;
    // Package-private so tests can hand the manager a handler that has recorded refusals.
    volatile OpenCodeAcpClientHandler activeHandler = null;

    /**
     * Port handed to {@code opencode acp --port}. The agent starts an HTTP server alongside the stdio ACP
     * channel, and that server is the only way to deliver mail mid-turn (ACP itself has no injection method —
     * its sole mid-turn control is session/cancel). Nothing announces the port, so the plugin picks a free
     * one and tells the agent, rather than trying to discover it afterwards. Zero until a process is spawned.
     */
    private volatile int httpPort = 0;
    /**
     * Whether this agent's HTTP server advertises the steer route. Resolved once after the handshake by
     * asking the server for its own OpenAPI document, so an opencode too old to support steering simply
     * degrades to today's behaviour instead of erroring. False until proven otherwise.
     */
    private volatile boolean steerCapable = false;
    /**
     * Per-process secret handed to the spawned agent as {@code OPENCODE_SERVER_PASSWORD}, and presented back
     * on every request we make to it. Distinct per process on purpose — opencode's session store is shared
     * across agents, so without it a request that reached the wrong agent's server would be honoured rather
     * than refused. Null until a process is spawned.
     */
    private volatile String openCodeMCPPassword = null;
    /**
     * Null unless {@link #EXPERIMENTAL_STEERING} is on. Because that flag is a compile-time constant, the
     * branch is dead code when it is false and {@link OpenCodeSteerClient} is never initialised — so its
     * static HttpClient and the threads behind it are never created for a build that does not use them.
     */
    private final OpenCodeSteerClient steerClient = EXPERIMENTAL_STEERING ? new OpenCodeSteerClient() : null;
    private OpenCodeAiSession openCodeAiSession = null;
    volatile Runnable onSessionEstablished = null;

    public OpenCodeAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    void setHandshakeTurnForTesting(Object turn) {
        beginHandshakeTurn(turn);
    }

    void setOnSessionEstablished(Runnable r) {
        this.onSessionEstablished = r;
    }

    @Override
    protected AcpConnection currentAcpConnection() {
        return connection;
    }

    @Override
    protected String currentAcpSessionId() {
        return acpSessionId;
    }

    @Override
    protected void cancelPendingPermissionsOnActiveHandler() {
        OpenCodeAcpClientHandler h = activeHandler;
        if (h != null) {
            h.cancelPendingPermissions();
        }
    }

    /**
     * Mirrors OpenCode's pre-Stage-1 {@code interrupt}, which captured {@code h = activeHandler} inside
     * {@code synchronized(this)} before calling {@code h.cancelPendingPermissions()} after unlock — reading
     * the field here, under the lock {@link #interrupt} calls this from, and binding the method reference to
     * that local rather than to the field itself.
     */
    @Override
    protected Runnable capturePermissionCancellerUnderLock() {
        OpenCodeAcpClientHandler h = activeHandler;
        return h != null ? h::cancelPendingPermissions : null;
    }

    @Override
    protected String backendDisplayNameForLogging() {
        return "OpenCode";
    }

    @Override
    public synchronized void start(String executablePath, String model) {
        stop();
        this.executablePath = executablePath;
        this.model = model;

        if (!OpenCodeExecutableLocator.isExecutableFile(executablePath)) {
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
        // Opt-in hardening (off by default): pin experimental.mcp_timeout into
        // the user's global OpenCode config so long MCP tool calls survive
        // OpenCode's short default. Invasive enough to stay behind a flag — it
        // persists outside the IDE and applies to every server, not just ours.
        // See OpenCodeMcpTimeoutPinner.
        if (OpenCodeMcpTimeoutPinner.PIN_MCP_TIMEOUT) {
            OpenCodeMcpTimeoutPinner.applyMcpTimeoutPin();
        }
        // MCP registration: start the shared HTTP server. Degrade gracefully on failure.
        OpenCodeAiMcpRegistrar reg = new OpenCodeAiMcpRegistrar(sessionId);
        try {
            boolean ok = McpServerRegistry.register(reg).get(30, TimeUnit.SECONDS);
            if (ok) {
                registrar = reg;
            }
            else {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "MCP server registration returned false — running without MCP tools"));
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "MCP server registration failed; running without MCP tools", e);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "MCP server unavailable — running without MCP tools"));
        }

        if (PluginSettings.isDebugJson()) {
            String m = currentSession.settings() instanceof OpenCodeSessionSettings ocs ? ocs.model() : null;
            LOG.log(Level.INFO,
                    "OpenCode start() [{0}]: registering OpenCodeAiSession — session#={1} settings#={2} model={3}",
                    new Object[]{sessionId, System.identityHashCode(currentSession),
                                 System.identityHashCode(currentSession.settings()), m});
        }
        openCodeAiSession = new OpenCodeAiSession(currentSession, listener);
        // Live check, not a snapshot: capability is probed asynchronously after the handshake and is reset whenever
        // the process is recycled, so PeerSessionList must read it at the moment a peer asks.
        openCodeAiSession.setSteerCapableSupplier(() -> steerCapable);
        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("OpenCode")));
    }

    /**
     * Spawns the opencode process and performs the ACP handshake. Always called on a background thread — this
     * method blocks up to 30 s on {@code initialize} and again on {@code session/new}. The instance monitor
     * is held only for brief state writes, never across the blocking waits, so other synchronized methods
     * (stop(), cancel via interrupt()) can run concurrently.
     */
    @Override
    protected void spawnAndHandshake(File workDir) throws Exception {
        // --port is what makes the agent's embedded HTTP server reachable, and that server is the only channel for
        // mid-turn mail. Left to itself opencode binds 4096 or an ephemeral port and announces neither, so we choose.
        // Safe on every realistic install: the flag has been on the acp command since Nov 2025, and opencode ignores
        // unknown flags (exit 0) rather than failing, so an older build simply starts without a port we can reach and
        // the capability probe below turns steering off. Never pass --mdns alongside it: that flips the server's
        // bind from 127.0.0.1 to 0.0.0.0, exposing an unauthenticated agent API to the LAN.
        // Only claim a port when steering is being worked on. With it off there is nothing to talk to the HTTP
        // server about, and passing --port would add a failure mode for no benefit: if the chosen port is taken
        // between our picking it and the agent binding it, opencode exits with ServeError and the session fails to
        // start at all. Verified — it does not fall back to another port.
        OpenCodeStartupCoordinator startupCoordinator = coordinateOpenCodeStartup();
        int port = EXPERIMENTAL_STEERING && startupCoordinator.supportsAcpPortFlag()
                   ? OpenCodeSteerClient.pickFreePort() : 0;
        try {
            // OpenCode v2's acp command accepts no flags. The process directory and
            // session/new or session/resume cwd parameter carry the working directory.
            List<String> cmd = buildAcpCommand(executablePath, port);
            // Locks the agent's HTTP server to this plugin. Verified against opencode 1.18.23: with this set, /doc and
            // POST /api/session/{id}/prompt both answer 401 unauthenticated and 200 with the credentials, while the ACP
            // stdio channel is unaffected. Two things depend on it. First, opencode keeps sessions in a shared SQLite
            // store, so any agent's server resolves any session id — a steer that reached the wrong server would be
            // honoured, not refused, and inter-AI mail would land in another agent's turn. Second, without it the agent
            // API is unauthenticated on loopback, so any local process could prompt, steer or abort the user's sessions.
            // The documented default username is used; only the password varies per process.
            String openCodeMCPPassword = EXPERIMENTAL_STEERING ? OpenCodeSteerClient.generateServerPassword() : null;
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            pb.environment().put("OPENCODE_CONFIG_CONTENT", buildPermissionConfigJson());
            if (openCodeMCPPassword != null) {
                pb.environment().put("OPENCODE_SERVER_PASSWORD", openCodeMCPPassword);
            }

            recentStderr.clear();
            Process process = pb.start();
            // Register the process under the lock so stop() can destroy it if it races us here.
            synchronized (this) {
                if (!running) {
                    process.destroyForcibly();
                    throw new IOException("stop() called before handshake began");
                }
                currentProcess = process;
                httpPort = port;
                this.openCodeMCPPassword = openCodeMCPPassword;
                // Reset per-spawn: a recycled process may be a different opencode build, and carrying the previous
                // answer over would have us POST steers at a port nothing is listening on.
                steerCapable = false;
            }

            startStderrDrainer(process);
            // The disconnect callback must know WHICH connection lost its stream, so a late callback from an
            // abandoned connection can never tear down the one that replaced it.
            AcpConnection[] connHolder = new AcpConnection[1];
            OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(listener,
                    () -> onHandlerDisconnected(connHolder[0]),
                    this::trackToolCallLifecycle,
                    ownSessionConfigFileCheck(), null, sessionId);
            AcpConnection conn = new AcpConnection(process.getOutputStream(), process.getInputStream(), handler, "OpenCode");
            connHolder[0] = conn;

            process.onExit().thenRun(() -> handleProcessExit(process));
            // ---- Blocking wait 1: initialize (outside the monitor) ----
            JsonObject initResult;
            try {
                initResult = conn.sendRequest(AcpMethodEnum.INITIALIZE, buildInitializeParams(Installer.VERSION))
                        .get(30, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                conn.close();
                synchronized (this) {
                    if (currentProcess == process) {
                        currentProcess = null;
                    }
                }
                process.destroyForcibly();
                throw new IOException("ACP initialize failed: " + e.getMessage(), e);
            }
            int proto = initResult.has(AcpJsonKeyEnum.PROTOCOL_VERSION.key()) ? initResult.get(AcpJsonKeyEnum.PROTOCOL_VERSION.key()).getAsInt() : -1;
            if (proto != 1) {
                conn.close();
                synchronized (this) {
                    if (currentProcess == process) {
                        currentProcess = null;
                    }
                }
                process.destroyForcibly();
                throw new IOException("Unsupported ACP protocol version: " + proto + " (expected 1)");
            }
            // ---- Blocking wait 2: session/resume (if stored) or session/new ----
            // Use the registry's single source of truth for the endpoint URL — correct for all sessions,
            // not just the first (the registrar only receives addMcpEndpoint on the first of its type).
            String mcpBaseUrl = McpServerRegistry.endpointUrlFor(AiTypeEnum.OPENCODE);
            String resumeId = pendingAcpResumeId;
            JsonObject sessionResult = null;
            boolean resumed = false;

            if (resumeId != null) {
                // Scoped generically to "while a load/resume call is in flight" (see
                // AbstractAcpClientHandler.beginSuppressingSessionUpdatesForLoad) — OpenCode's own
                // session/resume carries no replay (confirmed live: its response has only configOptions,
                // nothing streamed), so this is a harmless no-op here, kept symmetric with Grok's.
                long loadSuppressionToken = handler.beginSuppressingSessionUpdatesForLoad();
                try {
                    JsonObject resumeResult = conn.sendRequest(AcpMethodEnum.SESSION_RESUME,
                            buildSessionResumeParams(resumeId, workDir.getAbsolutePath(), mcpBaseUrl))
                            .get(30, TimeUnit.SECONDS);
                    // session/resume returns only configOptions (no sessionId) — the client
                    // supplied the id in the request; a non-exception return means resume succeeded.
                    sessionResult = resumeResult;
                    resumed = true;
                }
                catch (Exception e) {
                    LOG.log(Level.INFO, "session/resume failed; falling back to session/new: {0}", e.getMessage());
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                            "Previous OpenCode session could not be resumed; starting fresh"));
                }
                finally {
                    // Ordered after any (hypothetical, since OpenCode's resume carries none today) replay
                    // notification already queued on acp-notify — see the review-finding note on
                    // AbstractAcpClientHandler.beginSuppressingSessionUpdatesForLoad.
                    long token = loadSuppressionToken;
                    Runnable clearSuppression = () -> handler.endSuppressingSessionUpdatesForLoad(token);
                    try {
                        conn.runOnNotifyThread(clearSuppression);
                    }
                    catch (RejectedExecutionException e) {
                        clearSuppression.run();
                    }
                }
            }

            if (!resumed) {
                try {
                    sessionResult = conn.sendRequest(AcpMethodEnum.SESSION_NEW,
                            buildSessionNewParams(workDir.getAbsolutePath(), mcpBaseUrl))
                            .get(30, TimeUnit.SECONDS);
                }
                catch (Exception e) {
                    conn.close();
                    synchronized (this) {
                        if (currentProcess == process) {
                            currentProcess = null;
                        }
                    }
                    process.destroyForcibly();
                    throw new IOException("ACP session/new failed: " + e.getMessage(), e);
                }
            }

            String sid = resolveSessionId(resumed, resumeId, sessionResult);
            if (sid == null || sid.isBlank()) {
                conn.close();
                synchronized (this) {
                    if (currentProcess == process) {
                        currentProcess = null;
                    }
                }
                process.destroyForcibly();
                throw new IOException("session/new returned no " + AcpJsonKeyEnum.SESSION_ID.key());
            }
            // ---- Publish results, or report a dead agent, under the lock shared by every ACP backend ----
            final JsonObject resolvedSessionResult = sessionResult;
            publishConnectionOrReportExit(conn, process,
                    () -> {
                        acpSessionId = sid;
                        pendingAcpResumeId = null;
                        if (currentSession != null) {
                            if (currentSession.settings() instanceof OpenCodeSessionSettings) {
                                ((OpenCodeSessionSettings) currentSession.settings()).setAcpSessionId(sid);
                            }
                            currentSession.putExtra("opencode_acp_session_id", sid);
                        }
                        if (resolvedSessionResult.has(AcpJsonKeyEnum.CONFIG_OPTIONS.key()) && resolvedSessionResult.get(AcpJsonKeyEnum.CONFIG_OPTIONS.key()).isJsonArray()) {
                            sessionConfigOptions = resolvedSessionResult.getAsJsonArray(AcpJsonKeyEnum.CONFIG_OPTIONS.key());
                        }
                        activeHandler = handler;
                        this.connection = conn;
                    },
                    () -> handleProcessExit(process));
            startupCoordinator.recordSuccessfulStart();
            if (resumed) {
                LOG.log(Level.INFO, "Resumed OpenCode ACP session: {0}", acpSessionId);
            }
            else {
                LOG.log(Level.INFO, "Started new OpenCode ACP session: {0}", acpSessionId);
            }
            Runnable cb = onSessionEstablished;
            if (cb != null) {
                cb.run();
            }
            boolean settingsChanged = applyInitialModeIfNeeded();
            if (settingsChanged && cb != null) {
                // Validation replaced or cleared a stored value — re-persist so the
                // corrected settings survive the next IDE restart.
                cb.run();
            }
            // Notify info bar that config options are now available (fired outside
            // the monitor to avoid deadlock; the info bar guards with invokeLater).
            if (sessionConfigOptions != null) {
                listener.onAiProcessEvent(new OpenCodeConfigOptionsEvent(sessionConfigOptions));
            }
            if (EXPERIMENTAL_STEERING) {
                probeSteerCapabilityAsync(process, port, openCodeMCPPassword);
            }
        }
        finally {
            startupCoordinator.close();
        }
    }

    /**
     * Asks the agent's own HTTP server whether it advertises the steer route, and records the answer.
     *
     * <p>
     * Off-thread deliberately: the probe is only needed by the time mail arrives, and doing it inline would
     * add its timeout to session startup on any build that turns out not to support steering — the one case
     * where the user gains nothing by waiting.
     *
     * <p>
     * The result is pinned to the process that was just spawned. A slow probe answering after that process
     * has been replaced must not enable steering against a port belonging to a session that no longer exists.
     */
    private void probeSteerCapabilityAsync(Process spawned, int port, String password) {
        Thread probe = new Thread(() -> {
            // Doubles as an authentication check: wrong or missing credentials answer 401, which reads here as
            // "not capable" and leaves the session on end-of-turn delivery. Do not "optimise" this into an
            // unauthenticated request — it would report capable for a server we cannot actually steer.
            boolean capable = steerClient.probeSteerCapability(port, password);
            synchronized (this) {
                if (currentProcess != spawned) {
                    return;
                }
                steerCapable = capable;
            }
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode steer capability: {0} (port={1}, session={2})",
                        new Object[]{capable ? "AVAILABLE" : "unavailable", port, acpSessionId});
            }
        }, "opencode-steer-probe");
        probe.setDaemon(true);
        probe.start();
    }

    boolean applyInitialModeIfNeeded() {
        if (sessionConfigOptions == null || currentSession == null) {
            return false;
        }
        if (!(currentSession.settings() instanceof OpenCodeSessionSettings)) {
            return false;
        }
        OpenCodeSessionSettings s = (OpenCodeSessionSettings) currentSession.settings();
        boolean settingsChanged = false;
        // Model first: effort's available values depend on which model is active
        // so applyInitialEffortOption must see configOptions
        // AFTER a model switch, not before.
        settingsChanged |= applyInitialModelOption(s);
        settingsChanged |= applyInitialModeOption(s);
        settingsChanged |= applyInitialEffortOption(s);
        return settingsChanged;
    }

    /**
     * Reconciles the session's chosen model against what the agent actually started with. {@code session/new}
     * carries no model parameter — OpenCode always picks its own model when a session is created — so without
     * this, every session silently ran whatever OpenCode defaulted to (typically {@code opencode/big-pickle})
     * regardless of what the user chose per session. Unlike mode/effort, an unavailable choice is never
     * silently substituted here: it is surfaced (status message + log) instead, because running a different
     * model than the user asked for without telling them is the entire bug this method exists to fix.
     */
    private boolean applyInitialModelOption(OpenCodeSessionSettings s) {
        String agentCurrentModel = null;
        List<String> availableModels = new ArrayList<>();
        for (JsonElement el : sessionConfigOptions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if ("model".equals(opt.has(AcpJsonKeyEnum.ID.key()) ? opt.get(AcpJsonKeyEnum.ID.key()).getAsString() : null)) {
                if (opt.has(AcpJsonKeyEnum.CURRENT_VALUE.key())) {
                    agentCurrentModel = opt.get(AcpJsonKeyEnum.CURRENT_VALUE.key()).getAsString();
                }
                if (opt.has(AcpJsonKeyEnum.OPTIONS.key()) && opt.get(AcpJsonKeyEnum.OPTIONS.key()).isJsonArray()) {
                    for (JsonElement v : opt.getAsJsonArray(AcpJsonKeyEnum.OPTIONS.key())) {
                        if (v.isJsonObject() && v.getAsJsonObject().has(AcpJsonKeyEnum.VALUE.key())) {
                            availableModels.add(v.getAsJsonObject().get(AcpJsonKeyEnum.VALUE.key()).getAsString());
                        }
                    }
                }
                break;
            }
        }
        String requestedModel = s.model();
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode configOptions model: agent reports \"{0}\" (session requested \"{1}\")",
                    new Object[]{agentCurrentModel, requestedModel});
            if (requestedModel != null && !requestedModel.isBlank() && !requestedModel.equals(agentCurrentModel)) {
                LOG.log(Level.WARNING,
                        "OpenCode model mismatch: session settings say \"{0}\" but the agent started with \"{1}\"",
                        new Object[]{requestedModel, agentCurrentModel});
            }
        }
        if (requestedModel == null || requestedModel.isBlank() || requestedModel.equals(agentCurrentModel)) {
            return false;
        }
        if (!availableModels.isEmpty() && !availableModels.contains(requestedModel)) {
            LOG.log(Level.WARNING,
                    "OpenCode session requested model \"{0}\" but the agent does not offer it (available: {1}); "
                    + "running \"{2}\" instead", new Object[]{requestedModel, availableModels, agentCurrentModel});
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Model \"" + requestedModel + "\" is not available; running \"" + agentCurrentModel
                    + "\" instead"));
            return false;
        }
        try {
            JsonArray updated = setConfigOption("model", requestedModel).get(30, TimeUnit.SECONDS);
            sessionConfigOptions = updated;
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "OpenCode rejected model \"{0}\": {1}",
                    new Object[]{requestedModel, e.getMessage()});
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Model \"" + requestedModel + "\" was rejected by OpenCode; running \"" + agentCurrentModel
                    + "\" instead"));
        }
        return false;
    }

    private boolean applyInitialModeOption(OpenCodeSessionSettings s) {
        String agentCurrentMode = null;
        List<String> availableModes = new ArrayList<>();
        for (JsonElement el : sessionConfigOptions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if ("mode".equals(opt.has(AcpJsonKeyEnum.ID.key()) ? opt.get(AcpJsonKeyEnum.ID.key()).getAsString() : null)) {
                if (opt.has(AcpJsonKeyEnum.CURRENT_VALUE.key())) {
                    agentCurrentMode = opt.get(AcpJsonKeyEnum.CURRENT_VALUE.key()).getAsString();
                }
                if (opt.has(AcpJsonKeyEnum.OPTIONS.key()) && opt.get(AcpJsonKeyEnum.OPTIONS.key()).isJsonArray()) {
                    for (JsonElement v : opt.getAsJsonArray(AcpJsonKeyEnum.OPTIONS.key())) {
                        if (v.isJsonObject() && v.getAsJsonObject().has(AcpJsonKeyEnum.VALUE.key())) {
                            availableModes.add(v.getAsJsonObject().get(AcpJsonKeyEnum.VALUE.key()).getAsString());
                        }
                    }
                }
                break;
            }
        }
        String effectiveMode = s.mode();
        if (effectiveMode == null || effectiveMode.isBlank()) {
            effectiveMode = OpenCodePluginSettings.DEFAULT_MODE;
        }
        boolean settingsChanged = false;
        if (!availableModes.isEmpty() && !availableModes.contains(effectiveMode)) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Mode \"" + effectiveMode + "\" is not available; using agent default"));
            effectiveMode = agentCurrentMode;
            s.setMode(effectiveMode);
            settingsChanged = true;
        }
        if (effectiveMode != null && !effectiveMode.equals(agentCurrentMode)) {
            try {
                JsonArray updated = setConfigOption("mode", effectiveMode).get(30, TimeUnit.SECONDS);
                sessionConfigOptions = updated;
            }
            catch (Exception e) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Mode \"" + effectiveMode + "\" rejected by OpenCode; using default"));
            }
        }
        return settingsChanged;
    }

    private boolean applyInitialEffortOption(OpenCodeSessionSettings s) {
        String agentCurrentEffort = null;
        List<String> availableEfforts = new ArrayList<>();
        boolean effortOptionExists = false;
        for (JsonElement el : sessionConfigOptions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if ("effort".equals(opt.has(AcpJsonKeyEnum.ID.key()) ? opt.get(AcpJsonKeyEnum.ID.key()).getAsString() : null)) {
                effortOptionExists = true;
                if (opt.has(AcpJsonKeyEnum.CURRENT_VALUE.key())) {
                    agentCurrentEffort = opt.get(AcpJsonKeyEnum.CURRENT_VALUE.key()).getAsString();
                }
                if (opt.has(AcpJsonKeyEnum.OPTIONS.key()) && opt.get(AcpJsonKeyEnum.OPTIONS.key()).isJsonArray()) {
                    for (JsonElement v : opt.getAsJsonArray(AcpJsonKeyEnum.OPTIONS.key())) {
                        if (v.isJsonObject() && v.getAsJsonObject().has(AcpJsonKeyEnum.VALUE.key())) {
                            availableEfforts.add(v.getAsJsonObject().get(AcpJsonKeyEnum.VALUE.key()).getAsString());
                        }
                    }
                }
                break;
            }
        }
        String storedEffort = s.effort();
        if (!effortOptionExists) {
            if (storedEffort != null) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Effort setting \"" + storedEffort + "\" ignored: model does not support effort"));
                s.setEffort(null);
                return true;
            }
            return false;
        }
        if (storedEffort == null) {
            return false;
        }
        if (!availableEfforts.contains(storedEffort)) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Effort \"" + storedEffort + "\" is not available; clearing stored effort"));
            s.setEffort(null);
            return true;
        }
        if (!storedEffort.equals(agentCurrentEffort)) {
            try {
                JsonArray updated = setConfigOption("effort", storedEffort).get(30, TimeUnit.SECONDS);
                sessionConfigOptions = updated;
            }
            catch (Exception e) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Effort \"" + storedEffort + "\" rejected by OpenCode"));
            }
        }
        return false;
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (processing) {
            // Every refusal reports its reason and returns control to the user; the in-flight turn's later completion
            // is stale and is ignored by the UI busy/ready contract.
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, sendRefusalReason()));
            listener.onAiProcessEvent(new TurnCompleteEvent());

            return;
        }
        if (pendingDiff || !running || isWorkInFlight()) {
            // A submit AiTopComponent has already locked the UI for must never return silently, or the tab
            // would stay locked forever (cross-cutting rule found in the Copilot and Codex backends). None of
            // these refusals has a closer of its own, so INFO says why and TurnCompleteEvent releases the lock.
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, sendRefusalReason()));
            listener.onAiProcessEvent(new TurnCompleteEvent());
            return;
        }
        cancelledByUser = false;

        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        File effectiveWorkDir = sessionWorkingDir != null ? sessionWorkingDir : workingDir;

        if (connection == null) {
            // spawnAndHandshake blocks for up to 60 s; sendPrompt runs on the EDT.
            // Hand off to a background thread and return immediately so the UI stays responsive.
            // processing=true prevents a second submit from racing the handshake.
            processing = true;
            Object turn = new Object();
            beginHandshakeTurn(turn);
            final File wd = effectiveWorkDir;
            new Thread(() -> handshakeAndSend(text, wd, turn), "opencode-handshake").start();
            return;
        }

        sendTurn(text);
    }

    /**
     * The reason a send was refused, for the INFO a refused send must post before its TurnCompleteEvent. Each
     * call runs under the manager monitor (sendPrompt and deliverAfterHandshake are both synchronized).
     */
    @Override
    protected String sendRefusalReason() {
        if (processing) {
            return "OpenCode is already processing a turn";
        }
        if (isWorkInFlight()) {
            return "OpenCode is compacting the conversation";
        }
        if (pendingDiff) {
            return "OpenCode is waiting for a pending diff review";
        }
        return "OpenCode session is not running";
    }

    @Override
    protected synchronized void sendTurn(String text) {
        JsonObject promptItem = new JsonObject();
        promptItem.addProperty(AcpJsonKeyEnum.TYPE.key(), "text");
        promptItem.addProperty(AcpJsonKeyEnum.TEXT.key(), MCP_TOOL_PREFERENCE + "\n\n" + text);
        JsonArray promptArray = new JsonArray();
        promptArray.add(promptItem);

        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), acpSessionId);
        params.add(AcpJsonKeyEnum.PROMPT.key(), promptArray);

        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "opencode prompt [{0}]: {1}", new Object[]{acpSessionId, text});
        }
        // A new turn starts with no in-flight tool calls and no held mail interrupt. Stale state
        // could only have survived a teardown path that failed to clear it; resetting here keeps
        // the next turn clean regardless.
        resetMailInterruptHold();
        // Same reasoning for refusals: none can belong to this turn yet, and one left over must never be reported as
        // this turn's.
        OpenCodeAcpClientHandler handler = activeHandler;
        if (handler != null) {
            handler.clearTurnRefusals();
            // A compaction that stalled past its timeout (or was raced by an exit) may have left the
            // suppression flag set; a real turn must never be silenced by one. Each turn re-enters here,
            // which is why this is the safety net and not only sendCompactPrompt's own completion.
            handler.clearTextSuppression();
            // Same safety net for a resume whose ordered clear never ran.
            handler.clearSuppressingSessionUpdatesForLoad();
        }
        processing = true;
        Object turn = new Object();
        beginActiveTurn(turn); // before the send: a write to a dead agent fails it synchronously
        CompletableFuture<JsonObject> promptFuture = connection.sendRequest(AcpMethodEnum.SESSION_PROMPT, params);
        promptFuture
                .thenAccept(result -> {
                    if (claimTurn(turn)) {
                        handleTurnComplete(result);
                    }
                })
                .exceptionally(ex -> {
                    if (claimTurn(turn)) {
                        handleTurnError(ex);
                    }
                    return null;
                });
    }

    void handleTurnComplete(JsonObject result) {
        boolean wasRunning;
        boolean stoppedByUser;
        synchronized (this) {
            processing = false;
            // Turn over: nothing left mid-turn to interrupt, so clear any HELD mail interrupt
            // WITHOUT sending — the mail was already delivered by the broker and is visible in
            // the session's own context on its next turn either way (mirrors Claude).
            resetMailInterruptHold();
            wasRunning = running;
            // Read BEFORE it is cleared: it is the only thing that tells a turn the user stopped from one a refusal
            // ended, and both come back as stopReason "cancelled".
            stoppedByUser = cancelledByUser;
            cancelledByUser = false;
        }
        if (wasRunning) {
            // BEFORE the turn-complete event, never after: the UI handles that event by deciding whether the session
            // carries straight on or goes idle, and it can only take the refusal into account if it has already been
            // told of it. Events reach the UI in the order they are posted here.
            reportPolicyRefusals(endedByCancellation(result), stoppedByUser);
            listener.onAiProcessEvent(new TurnCompleteEvent());
        }
        else {
            discardTurnRefusals();
        }
    }

    /**
     * True when the {@code session/prompt} response says the turn ended as {@code cancelled}. OpenCode v2
     * ends a turn that way when the plugin's read policy refuses a tool call, as well as when the user
     * presses Stop or a mail interrupt cancels it; the caller tells those apart.
     */
    private static boolean endedByCancellation(JsonObject result) {
        if (result == null || !result.has(AcpJsonKeyEnum.STOP_REASON.key())) {
            return false;
        }
        JsonElement reason = result.get(AcpJsonKeyEnum.STOP_REASON.key());
        return reason.isJsonPrimitive()
               && AcpStopReasonEnum.fromWire(reason.getAsString()) == AcpStopReasonEnum.CANCELLED;
    }

    /**
     * Tells the UI, invisibly, when a refusal by the read policy is what ended the turn that is about to be
     * reported complete: posts a {@link PolicyRefusalEvent}, which the UI answers with an agent-only turn.
     *
     * <p>
     * OpenCode v2 answers a refused tool call by ending the WHOLE turn and reporting it as "The user declined
     * this tool call", although the user was never asked. The agent then believes, and tells the user, that
     * they declined something.
     *
     * <p>
     * Posted only when ALL of these hold, so nothing is said to a session that did not need it:
     * <ul>
     * <li>the handler refused at least one read this turn (otherwise the whole method is one emptiness
     * check);</li>
     * <li>the turn ended {@code cancelled} — v1 finishes normally and carries on after a refusal, so a
     * follow-up there would restart a session that never stopped;</li>
     * <li>the user did not press Stop — that turn ended because they asked.</li>
     * </ul>
     * The refusals are taken whether or not they are reported, so none is ever carried into a later turn. How
     * often the agent may be woken is the UI's business, not this method's: it is what refills that budget,
     * when the user sends a message.
     *
     * <p>
     * Never calls {@code requestGracefulInterrupt}: the turn is already over, and interrupting would only
     * send another cancel.
     */
    private void reportPolicyRefusals(boolean endedCancelled, boolean stoppedByUser) {
        OpenCodeAcpClientHandler handler = activeHandler;
        if (handler == null) {
            return;
        }
        List<PolicyRefusalEvent.Refusal> refusals = handler.consumeTurnRefusals();
        if (refusals.isEmpty() || stoppedByUser || !endedCancelled) {
            return;
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode policy refusal ended the turn: telling the UI so the agent can be resumed "
                                + "(session={0}, refusals={1})", new Object[]{sessionId, refusals.size()});
        }
        listener.onAiProcessEvent(new PolicyRefusalEvent(refusals));
    }

    /**
     * Forgets the turn's refusals without reporting them, for a turn that did not end the way a refusal ends
     * one.
     */
    private void discardTurnRefusals() {
        OpenCodeAcpClientHandler handler = activeHandler;
        if (handler != null) {
            handler.clearTurnRefusals();
        }
    }

    void handleTurnError(Throwable ex) {
        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
        boolean stoppedByUser;
        synchronized (this) {
            processing = false;
            // Same turn-end clearing as handleTurnComplete — a cancelled/errored turn has no
            // in-flight tool calls left to interrupt (mirrors Claude).
            resetMailInterruptHold();
            stoppedByUser = cancelledByUser;
            cancelledByUser = false;
        }
        boolean cancelledReply = cause instanceof AcpException cancelEx
                                 && cancelEx.code() == AcpErrorCodeEnum.REQUEST_CANCELLED.code();
        if (!cancelledReply) {
            // A turn that failed for any other reason is not one a refusal ended, and its refusals must not be carried
            // into a later turn.
            discardTurnRefusals();
        }
        if (cause instanceof AcpException) {
            AcpException ae = (AcpException) cause;
            if (ae.code() == AcpErrorCodeEnum.REQUEST_CANCELLED.code()) {
                // -32800: session/cancel was acknowledged; treat as normal cancel completion
                //
                // A cancellation the ERROR channel reports is the same cancelled turn handleTurnComplete sees, so the
                // same refusal report applies, before the turn-complete event for the same reason. There is no
                // stopReason here; -32800 itself says "cancelled".
                reportPolicyRefusals(true, stoppedByUser);
                listener.onAiProcessEvent(new TurnCompleteEvent());
                return;
            }
            if (ae.code() == AcpErrorCodeEnum.AUTH_REQUIRED.code()) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        "Run `opencode auth login` in the terminal"));
                return;
            }
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                StatusMessageUtil.formatSendFailed(cause != null ? cause.getMessage() : ex.getMessage())));
    }

    @Override
    public synchronized void stop() {
        // A compaction still running when the session is stopped must not be left locking the UI: its
        // closing FAILED comes from here, never a late READY from a response that will not come
        // (mirrors PiAiProcessManager.stop()).
        failWorkInFlight("OpenCode session stopped while work was in progress");
        // Logged before the state is torn down, so the record says what was actually
        // in flight at the moment of the stop rather than the cleared-out aftermath.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode stop: shutting session down (session={0}, turnInFlight={1}, connected={2}, processAlive={3})",
                    new Object[]{acpSessionId, processing, connection != null,
                                 currentProcess != null && currentProcess.isAlive()});
        }
        running = false;
        processing = false;
        cancelledByUser = true;
        resetMailInterruptHold();
        // Ends the turn without a closing status of its own, deliberately: stop() runs either from start(),
        // whose READY or FAILED then closes it, or when the session closes and nothing is listening. A
        // handshake still running for it must stay silent rather than report a late FAILED.
        clearHandshakeTurn();
        clearActiveTurn(); // same for a prompt in flight, whose response fails when the connection is closed

        OpenCodeAcpClientHandler h = activeHandler;
        activeHandler = null;
        AcpConnection conn = connection;
        connection = null;
        String sid = acpSessionId;
        acpSessionId = null;
        pendingAcpResumeId = null;
        Process proc = currentProcess;
        currentProcess = null;

        if (h != null) {
            h.cancelPendingPermissions();
        }
        if (conn != null && sid != null) {
            // Cancel in-flight work BEFORE session/close. The ACP spec makes
            // close imply cancellation, so an agent answers close only after
            // its own wind-down finishes — for a long tool call that can exceed
            // the bounded wait below, and "graceful close" silently degrades to
            // an abrupt kill exactly when work was running. Sending the cancel
            // first means the agent is already cancelling by the time the close
            // request arrives, so the wait now covers only the tail of that
            // wind-down. Sent unconditionally: harmless when idle (a
            // notification expects no response), and unconditional cannot drift
            // from the turn state the way a gate could.
            sendCancelNotification(conn, sid);
            JsonObject params = new JsonObject();
            params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), sid);
            CompletableFuture<JsonObject> closeFuture = conn.sendRequest(AcpMethodEnum.SESSION_CLOSE, params);
            // stop() runs synchronously from AiTopComponent.componentClosed() (EDT), so the
            // bounded wait for session/close's response must happen on a background thread,
            // never here — mirrors ClaudePersistentSession.close()'s reaper thread. The
            // graceful close is still attempted (that is why the cancel notification above is
            // sent first); conn.close()/proc.destroy() just run unconditionally once the wait
            // returns or times out, instead of blocking the caller for it.
            Thread reaper = new Thread(() -> {
                try {
                    closeFuture.get(OpenCodeTimeoutEnum.SESSION_CLOSE_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (Exception e) {
                    LOG.log(Level.FINE, "session/close timed out or failed during stop", e);
                }
                conn.close();
                if (proc != null) {
                    proc.destroy();
                }
            }, "opencode-stop-reaper");
            reaper.setDaemon(true);
            reaper.start();
        }
        else if (proc != null) {
            proc.destroy();
        }

        OpenCodeAiSession sess = openCodeAiSession;
        openCodeAiSession = null;
        var reg = registrar;
        registrar = null;

        if (sess != null) {
            sess.dispose();
        }
        if (reg != null) {
            McpServerRegistry.deregister(reg);
        }

        sessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigOptions = null;
        recentStderr.clear();
    }

    public synchronized boolean isSessionLive() {
        return acpSessionId != null;
    }

    @Override
    protected void setAcpConnection(AcpConnection conn) {
        connection = conn;
    }

    @Override
    protected void setAcpSessionId(String sid) {
        acpSessionId = sid;
    }

    @Override
    protected void setPendingAcpResumeId(String resumeId) {
        pendingAcpResumeId = resumeId;
    }

    @Override
    protected JsonArray currentConfigOptions() {
        return sessionConfigOptions;
    }

    @Override
    protected void setCurrentConfigOptions(JsonArray options) {
        sessionConfigOptions = options;
    }

    /**
     * No live agent means no HTTP endpoint to steer: PeerSessionList must not keep offering mid-turn mail.
     */
    @Override
    protected void onConnectionDetached() {
        activeHandler = null;
        steerCapable = false;
    }

    /**
     * OpenCode's copy of {@code handleProcessExit} did not reset {@code cancelledByUser} after reading it
     * into the suppress-EXITED decision — its flag is instead cleared by the next {@code sendPrompt}. Kept
     * exactly as found rather than silently unified with Grok's (which does reset it here), since nothing
     * confirms which is intentional.
     */
    @Override
    protected boolean resetCancelledByUserOnProcessExit() {
        return false;
    }

    /**
     * OpenCode session ids always start with {@code ses_}; guards against a stray plugin-level UUID reaching
     * the resume slot. {@code AiTopComponent.loadHistory()} passes the plugin UUID here — the override in
     * {@code OpenCodeAiImplementation.resumeSession()} is the primary defence, but this ensures no stray
     * caller can ever poison the pending resume slot.
     */
    @Override
    protected boolean isPlausibleResumeId(String candidateId) {
        if (!candidateId.startsWith("ses_")) {
            LOG.log(Level.FINE, "resumeSession: ignoring non-ACP id ''{0}''", candidateId);
            return false;
        }
        return true;
    }

    @Override
    protected void onCancelIgnored() {
        // Worth recording: "I pressed Stop and nothing happened" and "I pressed Stop and it kept talking"
        // look identical afterwards, and this branch is the first of those.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode interrupt: IGNORED, no turn in flight (session={0})", acpSessionId);
        }
    }

    @Override
    protected void onCancelAccepted(String sid, boolean connected) {
        // session/cancel is a notification, so OpenCode winds the turn down at its own pace and updates can
        // still arrive afterwards. Stamping the moment the user actually pressed Stop is the only way to
        // measure that tail: without it, "it carried on after I stopped it" cannot be told apart from a
        // normal wind-down, and the agent's own log gives no click time to compare against.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode interrupt: user pressed Stop, cancelling turn (session={0}, connected={1})",
                    new Object[]{sid, connected});
        }
    }

    @Override
    protected void onCancelNotificationSent(String sid) {
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode interrupt: session/cancel sent (session={0})", sid);
        }
    }

    private void cacheDiscoveredModels(JsonArray configOptions) {
        if (configOptions == null) {
            return;
        }
        for (JsonElement element : configOptions) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject option = element.getAsJsonObject();
            String id = option.has(AcpJsonKeyEnum.ID.key())
                        ? option.get(AcpJsonKeyEnum.ID.key()).getAsString() : null;
            if (!"model".equals(id) || !option.has(AcpJsonKeyEnum.OPTIONS.key())
                || !option.get(AcpJsonKeyEnum.OPTIONS.key()).isJsonArray()) {
                continue;
            }
            List<String> models = new ArrayList<>();
            for (JsonElement value : option.getAsJsonArray(AcpJsonKeyEnum.OPTIONS.key())) {
                if (value.isJsonObject() && value.getAsJsonObject().has(AcpJsonKeyEnum.VALUE.key())) {
                    models.add(value.getAsJsonObject().get(AcpJsonKeyEnum.VALUE.key()).getAsString());
                }
            }
            if (!models.isEmpty()) {
                OpenCodePluginSettings.setDiscoveredModels(models.toArray(new String[0]));
                OpenCodeAiImplementation.modelCatalog().publish(models);
            }
            return;
        }
    }

    /**
     * Compacts the OpenCode session's conversation. ACP has no dedicated compaction method; OpenCode answers
     * a {@code session/prompt} whose text is exactly {@code "/compact"} by summarising the session and
     * returning {@code stopReason: "end_turn"}. Run through {@link #runWork} so the busy/ready contract is
     * honoured: one BUSY (non-cancellable) on the way in, one closing status on every path out — never a
     * TurnCompleteEvent, and the summary OpenCode streams back as ordinary text is suppressed.
     */
    public void compact() {
        if (isBusy()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Wait for OpenCode to finish before compacting"));
            return;
        }
        if (connection == null || acpSessionId == null) {
            // Lazy start: no ACP session exists before the first prompt, so there is nothing to summarise. A
            // FAILED here would be a red status for a non-event — say so in an INFO instead (review).
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Nothing to compact yet"));
            return;
        }
        boolean started = runWork(
                "Compacting conversation…",
                false,
                DEFAULT_WORK_TIMEOUT_MILLIS,
                this::sendCompactPrompt,
                result -> new StatusEvent(StatusEventTypeEnum.READY, "Conversation compacted"),
                error -> new StatusEvent(StatusEventTypeEnum.FAILED,
                        error instanceof TimeoutException ? "Compact timed out" : "Compact failed: "
                                                                                  + (error != null && error.getMessage() != null ? error.getMessage() : "unknown error")));
        if (!started) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Compaction already in progress"));
        }
    }

    /**
     * The work for {@link #compact()}: sends the exact {@code "/compact"} prompt with no MCP-tool guidance —
     * that prefix belongs to user turns only and would make OpenCode treat this as a normal prompt. The
     * future completes when the {@code session/prompt} response arrives; {@link #runWork} closes it as READY.
     * Deliberately NOT chained through {@link #handleTurnComplete}: a compaction is not a turn, so it must
     * report no TurnCompleteEvent.
     *
     * <p>
     * While the prompt is in flight the handler drops streamed-back text/thought/tool chunks — OpenCode v1
     * answers {@code /compact} by streaming the summary back as ordinary agent text, which is not part of the
     * conversation. The flag is cleared and the returned future completed only once the response is sequenced
     * onto the connection's notification executor (acp-notify), i.e. after every session/update chunk the
     * reader already queued has been delivered suppressed. Clearing on the response thread itself would let a
     * lagging acp-notify deliver those queued chunks as {@link TextDeltaEvent}s after READY — exactly the
     * leak this ordering closes (review). Every path releases the flag: the sequenced finish, the connection
     * being closed out from under us (inline fallback), and the timeout (an {@code orTimeout} on the gate).
     */
    private CompletableFuture<JsonObject> sendCompactPrompt() {
        AcpConnection conn = connection;
        String sid = acpSessionId;
        if (conn == null || sid == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("OpenCode session is not active"));
        }
        OpenCodeAcpClientHandler handler = activeHandler;
        long token = handler != null ? handler.beginTextSuppression() : 0L;
        JsonObject promptItem = new JsonObject();
        promptItem.addProperty(AcpJsonKeyEnum.TYPE.key(), "text");
        promptItem.addProperty(AcpJsonKeyEnum.TEXT.key(), "/compact");
        JsonArray promptArray = new JsonArray();
        promptArray.add(promptItem);
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), sid);
        params.add(AcpJsonKeyEnum.PROMPT.key(), promptArray);
        CompletableFuture<JsonObject> sequenced = new CompletableFuture<>();
        conn.sendRequest(AcpMethodEnum.SESSION_PROMPT, params)
                .whenComplete((result, error) -> {
                    // The wire future completes on the dispatch executor (or the thread that answered it). The
                    // reader already queued any chunks that precede the response on acp-notify, so finish must
                    // be queued on the SAME executor to run after them — clearing and READY then follow the
                    // suppression rather than race ahead of it.
                    Runnable finish = () -> {
                        if (handler != null) {
                            handler.endTextSuppression(token);
                        }
                        if (error != null) {
                            sequenced.completeExceptionally(error);
                        }
                        else {
                            sequenced.complete(result);
                        }
                    };
                    try {
                        conn.runOnNotifyThread(finish);
                    }
                    catch (RejectedExecutionException e) {
                        finish.run(); // connection already closed: nothing is queued behind this finish, run inline
                    }
                });
        // A compaction that stalls past its timeout must still release the suppression flag. Every other path
        // is closed by finish above, so this re-arms only on the timeout signal.
        sequenced.whenComplete((result, error) -> {
            if (error instanceof TimeoutException && handler != null) {
                handler.endTextSuppression(token);
            }
        });
        return sequenced;
    }

}
