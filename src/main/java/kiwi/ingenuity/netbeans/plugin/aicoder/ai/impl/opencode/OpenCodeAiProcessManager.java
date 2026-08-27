package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpErrorCodeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpMethodEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Manages an OpenCode agent via one long-lived {@code opencode acp} process per plugin session. The process is spawned
 * lazily on the first {@link #sendPrompt} call. MCP wiring is deferred to a later slice.
 *
 * <p>
 * <b>Safety invariant:</b> The child process is always launched with {@code OPENCODE_CONFIG_CONTENT} set to force
 * {@code ask} permission for all file edits, bash commands and external-directory access. Without this, OpenCode's
 * defaults allow silent file mutations even when the client advertises {@code fs.writeTextFile} capability — confirmed
 * by live probe (design doc §4).
 */
public class OpenCodeAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(OpenCodeAiProcessManager.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_STDERR_LINES = 100;
    /**
     * Developer switch for mid-turn mail steering. NOT a user setting and deliberately not surfaced in the UI — flip it
     * here in source if you are working on it.
     *
     * <p>
     * <b>OFF because it does not work.</b> The mechanism is implemented and every part of it verified against a real
     * agent: the plugin passes {@code --port}, locks the agent's HTTP server with a per-process password, confirms
     * {@code /api/session/…/prompt} is declared in the agent's own OpenAPI document, and POSTs
     * {@code {"prompt":{"text":…},"delivery":"steer"}} to it — {@code delivery} being a real enum in that schema,
     * {@code ["steer","queue"]}. The request is accepted. The agent simply never acts on it until the running turn
     * ends, which is the one thing that would have made it worth doing.
     *
     * <p>
     * Tested on opencode 1.18.23 four ways: a blocking POST with a 5 s bound (timed out, our own timeout closing the
     * connection), the same with a long bound, asynchronous fire-and-forget, and finally a three-phase task with
     * checkpoints after each phase to rule out "the turn was too short to cross a promotion boundary". In every case
     * the peer reported the message arriving only after the whole turn finished. {@code POST …/prompt} waits on the
     * running turn regardless of {@code delivery}; the sibling {@code /session/…/prompt_async} returns immediately but
     * its schema has no {@code delivery} field at all, so it cannot steer.
     *
     * <p>
     * Left in rather than deleted so it can be re-tested cheaply against a future opencode: set this true, rebuild, and
     * send a peer important-flagged mail mid-turn. If their transcript shows it before the turn ends, the upstream
     * behaviour has changed and this can become the default.
     *
     * <p>
     * While off, OpenCode spawns exactly as it always did — no {@code --port}, no probe, no HTTP calls — and reports
     * {@code AFTER_TURN} honestly, because reporting mid-turn capability we cannot deliver would make ListAiSessions
     * lie to every peer that reads it.
     */
    static final boolean EXPERIMENTAL_STEERING = false;

    /**
     * Builds the value for the OPENCODE_CONFIG_CONTENT environment variable. Forces "ask" permission for all edit, bash
     * and external-directory operations, and denies sub-agent spawning outright. This MERGES with the user's existing
     * config — it does not replace it (verified by live probe, design doc §4) — so it constrains only the sessions this
     * plugin launches and leaves the user's own {@code opencode} CLI usage alone.
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

    static JsonObject buildInitializeParams(String pluginVersion) {
        JsonObject fs = new JsonObject();
        fs.addProperty(AcpJsonKeyEnum.READ_TEXT_FILE.key(), true);
        fs.addProperty(AcpJsonKeyEnum.WRITE_TEXT_FILE.key(), true);
        JsonObject capabilities = new JsonObject();
        capabilities.add(AcpJsonKeyEnum.FS.key(), fs);
        capabilities.addProperty(AcpJsonKeyEnum.TERMINAL.key(), false);
        JsonObject info = new JsonObject();
        info.addProperty(AcpJsonKeyEnum.NAME.key(), "aicoder-netbeans");
        info.addProperty(AcpJsonKeyEnum.VERSION.key(), pluginVersion);
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.PROTOCOL_VERSION.key(), 1);
        params.add(AcpJsonKeyEnum.CLIENT_CAPABILITIES.key(), capabilities);
        params.add(AcpJsonKeyEnum.CLIENT_INFO.key(), info);
        return params;
    }

    static JsonObject buildSessionResumeParams(String acpSessionId, String cwd, String mcpEndpointUrl) {
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), acpSessionId);
        params.addProperty(AcpJsonKeyEnum.CWD.key(), cwd);
        JsonArray mcpServers = new JsonArray();
        if (mcpEndpointUrl != null) {
            JsonObject entry = new JsonObject();
            entry.addProperty(AcpJsonKeyEnum.TYPE.key(), "http");
            entry.addProperty(AcpJsonKeyEnum.NAME.key(), StringConst.PLUGIN_ID);
            entry.addProperty(AcpJsonKeyEnum.URL.key(), mcpEndpointUrl);
            entry.add(AcpJsonKeyEnum.HEADERS.key(), new JsonArray());
            mcpServers.add(entry);
        }
        params.add(AcpJsonKeyEnum.MCP_SERVERS.key(), mcpServers);
        return params;
    }

    static JsonObject buildSessionNewParams(String absoluteCwd) {
        return buildSessionNewParams(absoluteCwd, null);
    }

    static JsonObject buildSessionNewParams(String absoluteCwd, String mcpEndpointUrl) {
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.CWD.key(), absoluteCwd);
        JsonArray mcpServers = new JsonArray();
        if (mcpEndpointUrl != null) {
            JsonObject entry = new JsonObject();
            entry.addProperty(AcpJsonKeyEnum.TYPE.key(), "http");
            entry.addProperty(AcpJsonKeyEnum.NAME.key(), StringConst.PLUGIN_ID);
            entry.addProperty(AcpJsonKeyEnum.URL.key(), mcpEndpointUrl);
            entry.add(AcpJsonKeyEnum.HEADERS.key(), new JsonArray());
            mcpServers.add(entry);
        }
        params.add(AcpJsonKeyEnum.MCP_SERVERS.key(), mcpServers);
        return params;
    }

    static String resolveSessionId(boolean resumed, String resumeId, JsonObject sessionResult) {
        // session/resume response contains only configOptions; use the requested id directly.
        // session/new always returns sessionId in its response.
        if (resumed) {
            return resumeId;
        }
        return sessionResult != null && sessionResult.has(AcpJsonKeyEnum.SESSION_ID.key())
                ? sessionResult.get(AcpJsonKeyEnum.SESSION_ID.key()).getAsString() : null;
    }

    // Package-private like pendingAcpResumeId/sessionConfigOptions below, so tests
    // can inject a pipe-backed AcpConnection and assert wire ordering.
    volatile AcpConnection connection = null;
    volatile String acpSessionId = null;
    volatile String pendingAcpResumeId = null;
    volatile JsonArray sessionConfigOptions = null;
    private volatile OpenCodeAcpClientHandler activeHandler = null;
    private final List<String> recentStderr = new CopyOnWriteArrayList<>();

    private volatile OpenCodeAiMcpRegistrar registrar = null;
    /**
     * Port handed to {@code opencode acp --port}. The agent starts an HTTP server alongside the stdio ACP channel, and
     * that server is the only way to deliver mail mid-turn (ACP itself has no injection method — its sole mid-turn
     * control is session/cancel). Nothing announces the port, so the plugin picks a free one and tells the agent,
     * rather than trying to discover it afterwards. Zero until a process is spawned.
     */
    private volatile int httpPort = 0;
    /**
     * Whether this agent's HTTP server advertises the steer route. Resolved once after the handshake by asking the
     * server for its own OpenAPI document, so an opencode too old to support steering simply degrades to today's
     * behaviour instead of erroring. False until proven otherwise.
     */
    private volatile boolean steerCapable = false;
    /**
     * Per-process secret handed to the spawned agent as {@code OPENCODE_SERVER_PASSWORD}, and presented back on every
     * request we make to it. Distinct per process on purpose — opencode's session store is shared across agents, so
     * without it a request that reached the wrong agent's server would be honoured rather than refused. Null until a
     * process is spawned.
     */
    private volatile String openCodeMCPPassword = null;
    /**
     * Null unless {@link #EXPERIMENTAL_STEERING} is on. Because that flag is a compile-time constant, the branch is
     * dead code when it is false and {@link OpenCodeSteerClient} is never initialised — so its static HttpClient and
     * the threads behind it are never created for a build that does not use them.
     */
    private final OpenCodeSteerClient steerClient = EXPERIMENTAL_STEERING ? new OpenCodeSteerClient() : null;
    private OpenCodeAiSession openCodeAiSession = null;
    volatile Runnable onSessionEstablished = null;

    public OpenCodeAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    void setOnSessionEstablished(Runnable r) {
        this.onSessionEstablished = r;
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
        // the process is recycled, so ListAiSessions must read it at the moment a peer asks.
        openCodeAiSession.setSteerCapableSupplier(() -> steerCapable);
        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("OpenCode")));
    }

    /**
     * Spawns the opencode process and performs the ACP handshake. Always called on a background thread — this method
     * blocks up to 30 s on {@code initialize} and again on {@code session/new}. The instance monitor is held only for
     * brief state writes, never across the blocking waits, so other synchronized methods (stop(), cancel via
     * interrupt()) can run concurrently.
     */
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
        int port = EXPERIMENTAL_STEERING ? OpenCodeSteerClient.pickFreePort() : 0;
        List<String> cmd = port > 0
                ? OpenCodeExecutableLocator.buildHostCommand(
                        executablePath, "acp", "--cwd", workDir.getAbsolutePath(),
                        "--port", Integer.toString(port))
                : OpenCodeExecutableLocator.buildHostCommand(
                        executablePath, "acp", "--cwd", workDir.getAbsolutePath());

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

        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(listener, this::onHandlerDisconnected);
        AcpConnection conn = new AcpConnection(process.getOutputStream(), process.getInputStream(), handler);

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

        // ---- Publish results under the lock; bail if stop() ran during the waits ----
        synchronized (this) {
            if (!running) {
                conn.close();
                process.destroyForcibly();
                if (currentProcess == process) {
                    currentProcess = null;
                }
                throw new IOException("stop() called during handshake");
            }
            acpSessionId = sid;
            pendingAcpResumeId = null;
            if (currentSession != null) {
                if (currentSession.settings() instanceof OpenCodeSessionSettings) {
                    ((OpenCodeSessionSettings) currentSession.settings()).setAcpSessionId(sid);
                }
                currentSession.putExtra("opencode_acp_session_id", sid);
            }
            if (sessionResult.has(AcpJsonKeyEnum.CONFIG_OPTIONS.key()) && sessionResult.get(AcpJsonKeyEnum.CONFIG_OPTIONS.key()).isJsonArray()) {
                sessionConfigOptions = sessionResult.getAsJsonArray(AcpJsonKeyEnum.CONFIG_OPTIONS.key());
            }
            activeHandler = handler;
            this.connection = conn;
        }
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

    /**
     * Delivers an inbox notification into the running turn, so the agent reads its mail without being cancelled.
     *
     * <p>
     * ACP has no method for this — its only mid-turn control is session/cancel, which would end the turn and take any
     * in-flight tool call with it. opencode's core does support steering, but exposes it solely on the HTTP API its
     * agent already runs, so delivery goes over that rather than over the ACP channel. The steered text arrives in the
     * transcript as a user message via the same event stream, so the user sees what the agent was told.
     *
     * <p>
     * Sends a notification, not the message body, matching Codex: the agent is told mail exists and fetches it with the
     * inbox tools. That keeps one copy of the message (the inbox) rather than pasting a second into the conversation,
     * and avoids putting arbitrary text into a turn the user did not author.
     *
     * <p>
     * Every failure path is a silent no-op by design. The message is already queued in the inbox and will be delivered
     * at end of turn exactly as before this existed, so a refused or impossible steer costs promptness, never the
     * message.
     */
    private void interruptMail() {
        if (!EXPERIMENTAL_STEERING) {
            // Pre-steering behaviour: the message stays queued and arrives at the normal end-of-turn inbox flush.
            // Logged rather than dropped in silence — see EXPERIMENTAL_STEERING for what was tried and why it is off.
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO,
                        "OpenCode interrupt: Mail deferred to end-of-turn flush — no working mid-turn channel "
                        + "(session={0})", acpSessionId);
            }
            return;
        }
        String sid;
        int port;
        boolean capable;
        String cOpenCodeMCPPassword;
        synchronized (this) {
            if (!processing) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO,
                            "OpenCode interrupt: Mail IGNORED, no turn in flight — message will arrive via normal "
                            + "inbox flush (session={0})", acpSessionId);
                }
                return;
            }
            sid = acpSessionId;
            port = httpPort;
            capable = steerCapable;
            cOpenCodeMCPPassword = openCodeMCPPassword;
        }
        if (!capable || sid == null || sid.isBlank() || port <= 0) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO,
                        "OpenCode interrupt: Mail IGNORED, steering unavailable — message will arrive via normal "
                        + "inbox flush (session={0}, port={1}, steerCapable={2})",
                        new Object[]{sid, port, capable});
            }
            return;
        }
        // Off the caller's thread: this is an HTTP round trip, and the caller may be the EDT or the inbox broker.
        Thread steer = new Thread(() -> {
            // "dispatched", not "delivered": the endpoint does not answer during a turn, so this only says the
            // request left here. Whether the agent acted on it shows up in that session's transcript, not here.
            boolean dispatched = steerClient.sendSteer(port, sid, InterruptTypeEnum.MAIL_NOTIFICATION_TEXT, cOpenCodeMCPPassword);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode interrupt: Mail steer {0} (session={1})",
                        new Object[]{dispatched ? "dispatched" : "could not be dispatched — end-of-turn flush only", sid});
            }
        }, "opencode-mail-steer");
        steer.setDaemon(true);
        steer.start();
    }

    /**
     * Asks the agent's own HTTP server whether it advertises the steer route, and records the answer.
     *
     * <p>
     * Off-thread deliberately: the probe is only needed by the time mail arrives, and doing it inline would add its
     * timeout to session startup on any build that turns out not to support steering — the one case where the user
     * gains nothing by waiting.
     *
     * <p>
     * The result is pinned to the process that was just spawned. A slow probe answering after that process has been
     * replaced must not enable steering against a port belonging to a session that no longer exists.
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
        // (design doc §13), so applyInitialEffortOption must see configOptions
        // AFTER a model switch, not before.
        settingsChanged |= applyInitialModelOption(s);
        settingsChanged |= applyInitialModeOption(s);
        settingsChanged |= applyInitialEffortOption(s);
        return settingsChanged;
    }

    /**
     * Reconciles the session's chosen model against what the agent actually started with. {@code session/new} carries
     * no model parameter — OpenCode always picks its own model when a session is created — so without this, every
     * session silently ran whatever OpenCode defaulted to (typically {@code opencode/big-pickle}) regardless of what
     * the user chose per session. Unlike mode/effort, an unavailable choice is never silently substituted here: it is
     * surfaced (status message + log) instead, because running a different model than the user asked for without
     * telling them is the entire bug this method exists to fix.
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

    private void startStderrDrainer(Process process) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.WARNING, "opencode stderr: {0}", McpHookServerUtil.redactAllSecrets(line));
                    }
                    recentStderr.add(line);
                    while (recentStderr.size() > MAX_STDERR_LINES) {
                        recentStderr.remove(0);
                    }
                }
            }
            catch (IOException e) {
                LOG.log(Level.FINE, "opencode stderr drainer ended", e);
            }
        }, "opencode-stderr");
        t.setDaemon(true);
        t.start();
    }

    private void handleProcessExit(Process dead) {
        boolean suppress;
        synchronized (this) {
            if (currentProcess != dead) {
                return; // stale exit from a superseded process
            }
            processing = false;
            currentProcess = null;
            suppress = cancelledByUser;
        }
        int code = dead.exitValue();
        if (!suppress && code != 0) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                    StatusMessageUtil.formatExited("OpenCode", code, new ArrayList<>(recentStderr))));
        }
    }

    private void onHandlerDisconnected() {
        // handleProcessExit handles exit reporting; this is defensive cleanup only.
        synchronized (this) {
            processing = false;
        }
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing) {
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
            final File wd = effectiveWorkDir;
            new Thread(() -> handshakeAndSend(text, wd, projectDirs), "opencode-handshake").start();
            return;
        }

        sendTurn(text);
    }

    /**
     * Background-thread entry point when no ACP connection exists yet. Calls {@link #spawnAndHandshake} (which blocks
     * up to 60 s), then hands the prompt to {@link #sendTurn} once the connection is live — holding {@code processing}
     * true across the hand-off so an EDT {@link #sendPrompt} landing in between cannot slip past its guard and start a
     * duplicate turn/handshake. Runs entirely outside the instance monitor during the blocking wait.
     */
    private void handshakeAndSend(String text, File workDir, List<File> projectDirs) {
        try {
            spawnAndHandshake(workDir);
        }
        catch (Exception e) {
            synchronized (this) {
                processing = false;
            }
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSendFailed(e.getMessage())));
            return;
        }
        deliverAfterHandshake(text);
    }

    /**
     * Post-handshake delivery of the prompt queued by {@link #sendPrompt}, extracted from {@link #handshakeAndSend} so
     * tests can drive the hand-off without spawning a real CLI. Keep {@code processing} true through the hand-off
     * below: sendTurn rearms it, so only paths that never reach sendTurn clear it — exactly once, under the monitor.
     * Clearing it unconditionally here reopened a window in which an EDT sendPrompt saw !processing and raced this
     * thread with a second submit.
     */
    void deliverAfterHandshake(String text) {
        synchronized (this) {
            if (!running || pendingDiff) {
                processing = false; // stop()/diff panel won the race; nobody else will rearm
                return;
            }
        }
        // Connection is now established; deliver through the normal turn path. Deliberately
        // sendTurn(), not sendPrompt(): with processing still held true, sendPrompt's own
        // guard would reject the re-entry and silently drop the prompt.
        sendTurn(text);
    }

    synchronized void sendTurn(String text) {
        JsonObject promptItem = new JsonObject();
        promptItem.addProperty(AcpJsonKeyEnum.TYPE.key(), "text");
        promptItem.addProperty(AcpJsonKeyEnum.TEXT.key(), text);
        JsonArray promptArray = new JsonArray();
        promptArray.add(promptItem);

        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), acpSessionId);
        params.add(AcpJsonKeyEnum.PROMPT.key(), promptArray);

        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "opencode prompt [{0}]: {1}", new Object[]{acpSessionId, text});
        }
        processing = true;
        CompletableFuture<JsonObject> promptFuture = connection.sendRequest(AcpMethodEnum.SESSION_PROMPT, params);
        promptFuture
                .thenAccept(this::handleTurnComplete)
                .exceptionally(ex -> {
                    handleTurnError(ex);
                    return null;
                });
    }

    void handleTurnComplete(JsonObject result) {
        boolean wasRunning;
        synchronized (this) {
            processing = false;
            wasRunning = running;
            cancelledByUser = false;
        }
        if (wasRunning) {
            listener.onAiProcessEvent(new TurnCompleteEvent());
        }
    }

    void handleTurnError(Throwable ex) {
        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
        synchronized (this) {
            processing = false;
            cancelledByUser = false;
        }
        if (cause instanceof AcpException) {
            AcpException ae = (AcpException) cause;
            if (ae.code() == AcpErrorCodeEnum.REQUEST_CANCELLED.code()) {
                // -32800: session/cancel was acknowledged; treat as normal cancel completion
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
    public void interrupt(InterruptTypeEnum type) {
        if (type == InterruptTypeEnum.Mail) {
            interruptMail();
            return;
        }
        if (type != InterruptTypeEnum.Cancel) {
            return;
        }
        AcpConnection conn;
        String sid;
        OpenCodeAcpClientHandler h;
        synchronized (this) {
            if (!processing) {
                // Worth recording: "I pressed Stop and nothing happened" and "I
                // pressed Stop and it kept talking" look identical afterwards, and
                // this branch is the first of those.
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "OpenCode interrupt: IGNORED, no turn in flight (session={0})", acpSessionId);
                }
                return;
            }
            cancelledByUser = true;
            processing = false;
            conn = connection;
            sid = acpSessionId;
            h = activeHandler;
        }
        // session/cancel is a notification, so OpenCode winds the turn down at its
        // own pace and updates can still arrive afterwards. Stamping the moment the
        // user actually pressed Stop is the only way to measure that tail: without
        // it, "it carried on after I stopped it" cannot be told apart from a normal
        // wind-down, and the agent's own log gives no click time to compare against.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode interrupt: user pressed Stop, cancelling turn (session={0}, connected={1})",
                    new Object[]{sid, conn != null});
        }
        if (h != null) {
            h.cancelPendingPermissions();
        }
        if (conn != null && sid != null) {
            sendCancelNotification(conn, sid);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode interrupt: session/cancel sent (session={0})", sid);
            }
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
    }

    /**
     * Sends {@code session/cancel} as a fire-and-forget notification over the given connection. The one shared
     * mechanism for every cancel — mid-turn interrupts and teardown alike — so the two paths cannot drift apart again
     * (stop() historically forgot the cancel entirely; review finding I2). Harmless when no turn is in flight: a
     * JSON-RPC notification expects no response, so no error can come back, and an agent with nothing to cancel simply
     * ignores it.
     */
    private void sendCancelNotification(AcpConnection conn, String sid) {
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), sid);
        conn.sendNotification(AcpMethodEnum.SESSION_CANCEL, params);
    }

    @Override
    public synchronized void stop() {
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
        OpenCodeAiMcpRegistrar reg = registrar;
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

    @Override
    public void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        if (!existingSessionId.startsWith("ses_")) {
            // Guard against plugin-level UUIDs; OpenCode session ids always start with ses_.
            // AiTopComponent.loadHistory() passes the plugin UUID here — the override in
            // OpenCodeAiImplementation.resumeSession() is the primary defence, but this
            // ensures no stray caller can ever poison the pending resume slot.
            LOG.log(Level.FINE, "resumeSession: ignoring non-ACP id ''{0}''", existingSessionId);
            return;
        }
        pendingAcpResumeId = existingSessionId;
    }

    public synchronized boolean isSessionLive() {
        return acpSessionId != null;
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }

    /**
     * The configOptions array captured from the session/new response (design doc §13). Null before the ACP handshake
     * completes.
     */
    public JsonArray configOptions() {
        return sessionConfigOptions;
    }

    /**
     * Changes one session config option (model, effort, or mode — the only three ids OpenCode accepts; anything else
     * fails server-side with InvalidConfigOptionError). Completes with the COMPLETE configOptions snapshot from the
     * response, not just the changed entry — options are interdependent (e.g. effort depends on the selected model),
     * and no config_option_update notification is sent for this change, so the response is the only source of truth
     * (design doc §13).
     */
    public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
        AcpConnection conn = connection;
        String sid = acpSessionId;
        if (conn == null || sid == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenCode session is not active"));
        }
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), sid);
        params.addProperty(AcpJsonKeyEnum.CONFIG_ID.key(), configId);
        params.addProperty(AcpJsonKeyEnum.VALUE.key(), value);
        return conn.sendRequest(AcpMethodEnum.SESSION_SET_CONFIG_OPTION, params)
                .thenApply(result -> {
                    JsonArray options = result != null && result.has(AcpJsonKeyEnum.CONFIG_OPTIONS.key())
                            && result.get(AcpJsonKeyEnum.CONFIG_OPTIONS.key()).isJsonArray()
                            ? result.getAsJsonArray(AcpJsonKeyEnum.CONFIG_OPTIONS.key())
                            : new JsonArray();
                    sessionConfigOptions = options;
                    return options;
                });
    }
}
