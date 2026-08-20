package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Owns one {@code codex app-server} subprocess per plugin session, frames
 * newline-delimited JSON-RPC 2.0 on its stdin/stdout via
 * {@link CodexJsonRpcClient}, and drives the turn lifecycle. Streaming text and
 * the permission bridge live in {@link CodexAppServerHandler}. MCP registration
 * is handled here via {@link CodexAiMcpRegistrar} and a per-spawn
 * {@code -c mcp_servers.<name>.url=...} override (design doc §0a); the info bar
 * is still a later slice.
 *
 * <p>
 * The process is spawned lazily on the first {@link #sendPrompt} call, same as
 * {@code OpenCodeAiProcessManager} — {@link #start} only validates
 * preconditions and reports READY, so opening a session tab never spawns a
 * process the user might never use.
 *
 * <p>
 * Handshake order (design doc §8): {@code initialize} -> {@code initialized} ->
 * {@code thread/start} (or {@code thread/resume} with a saved thread id),
 * capturing the thread id from the response — paired to that exact request by
 * id, so it cannot race or be missed the way the {@code thread/started}
 * notification could (design doc §3's warning about OpenCode's resume bug).
 *
 * <p>
 * {@code thread/start}/{@code thread/resume} send {@code sandbox:
 * "workspace-write"} and {@code approvalPolicy: "untrusted"} (design doc §0a
 * "Approval routing and sandbox") and the resolved model — {@code
 * ThreadStartParams.model} is honored directly (confirmed by live probe: a
 * non-default model requested in the params came back unchanged in the
 * response), unlike OpenCode, which has no model parameter on {@code
 * session/new} and needs a post-hoc {@code session/set_config_option} dance.
 */
public class CodexAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(CodexAiProcessManager.class.getName());
    private static final int MAX_STDERR_LINES = 100;
    private static final String CLIENT_NAME = "aicoder-netbeans";
    private static final String CLIENT_TITLE = "AI Coder for NetBeans";
    static final String PLUGIN_VERSION = "1.2";

    /**
     * TOML key segment under {@code mcp_servers.<name>} — same identity
     * Claude/Grok register under.
     */
    static final String MCP_SERVER_NAME = StringConst.PLUGIN_ID;
    /**
     * Mail interrupt text — same spirit and wording as {@code
     * GithubCopilotProcessManager}'s Mail notice, so the on-screen behaviour
     * reads the same across backends that support mid-turn injection.
     */
    static final String MAIL_STEER_TEXT = "[inbox] You have a new message — check it when convenient.";

    static JsonObject buildInitializeParams(String clientName, String clientTitle, String clientVersion) {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", clientName);
        clientInfo.addProperty("title", clientTitle);
        clientInfo.addProperty("version", clientVersion);
        JsonObject params = new JsonObject();
        params.add("clientInfo", clientInfo);
        params.add("capabilities", new JsonObject());
        return params;
    }

    /**
     * {@code sandbox}/{@code approvalPolicy} are plain wire strings on {@code
     * ThreadStartParams}/{@code ThreadResumeParams} (kebab-case) — NOT the same
     * shape as {@code TurnStartParams.sandboxPolicy}, which is an object with a
     * camelCase {@code type} (readOnly/workspaceWrite/dangerFullAccess).
     * Confirmed by reading both generated schemas; easy to conflate since they
     * cover the same concept. {@code model} is omitted (letting Codex use its
     * own default) when null or blank.
     */
    static JsonObject buildThreadStartParams(String cwd, String model) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd);
        params.addProperty("sandbox", "workspace-write");
        params.addProperty("approvalPolicy", "untrusted");
        if (model != null && !model.isBlank()) {
            params.addProperty("model", model);
        }
        return params;
    }

    static JsonObject buildThreadResumeParams(String threadId, String cwd, String model) {
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("cwd", cwd);
        params.addProperty("sandbox", "workspace-write");
        params.addProperty("approvalPolicy", "untrusted");
        if (model != null && !model.isBlank()) {
            params.addProperty("model", model);
        }
        return params;
    }

    static JsonObject buildTurnStartParams(String threadId, String promptText) {
        JsonObject textInput = new JsonObject();
        textInput.addProperty("type", "text");
        textInput.addProperty("text", promptText);
        JsonArray input = new JsonArray();
        input.add(textInput);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("input", input);
        return params;
    }

    /**
     * {@code TurnInterruptParams} requires both ids (schema-confirmed) —
     * threadId alone is not enough.
     */
    static JsonObject buildTurnInterruptParams(String threadId, String turnId) {
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("turnId", turnId);
        return params;
    }

    /**
     * {@code TurnSteerParams} (confirmed by generating the schema live against
     * {@code codex-cli 0.148.0} with {@code codex app-server
     * generate-json-schema} — the design doc's §0a method — since no persisted
     * copy of the Slice 5 schemas remained on disk): {@code threadId} and
     * {@code input} are the same shape as {@code turn/start}'s, but steering
     * additionally requires {@code expectedTurnId} — "Required active turn id
     * precondition. The request fails when it does not match the currently
     * active turn." {@code TurnSteerResponse} on success is just {@code
     * {turnId}}; there is no in-band error field on either params or response,
     * so a refusal (e.g. {@code ActiveTurnNotSteerable}, returned per the
     * schema when the active turn cannot accept same-turn steering — a
     * {@code /review} or manual {@code /compact} in progress) can only surface
     * as a genuine JSON-RPC error response, not a "successful" body. {@code
     * CodexJsonRpcClient} does not parse the error's {@code data} field at all
     * ({@link CodexJsonRpcException} carries only {@code code}/{@code
     * message}), so there is no {@code codexErrorInfo} discriminant available
     * to branch on here even if one wanted to — every {@code turn/steer}
     * failure is handled identically (log and leave the message for the normal
     * inbox flush), which is also exactly the required behaviour for {@code
     * ActiveTurnNotSteerable} specifically.
     */
    static JsonObject buildTurnSteerParams(String threadId, String expectedTurnId, String promptText) {
        JsonObject textInput = new JsonObject();
        textInput.addProperty("type", "text");
        textInput.addProperty("text", promptText);
        JsonArray input = new JsonArray();
        input.add(textInput);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("expectedTurnId", expectedTurnId);
        params.add("input", input);
        return params;
    }

    /**
     * Extracts the thread id from a {@code thread/start} or {@code
     * thread/resume} response — both nest it at {@code result.thread.id}
     * (camelCase, confirmed by live probe), not a top-level {@code thread_id}
     * as the design doc's unverified §2 example (sourced from {@code codex exec
     * --json}'s unrelated JSONL format) suggested. Returns null on any
     * unexpected shape rather than throwing — callers must treat null as
     * "handshake did not produce a usable id".
     */
    static String extractThreadId(JsonObject result) {
        if (result == null || !result.has("thread")) {
            return null;
        }
        JsonElement threadEl = result.get("thread");
        if (!threadEl.isJsonObject()) {
            return null;
        }
        JsonObject thread = threadEl.getAsJsonObject();
        return thread.has("id") && !thread.get("id").isJsonNull() ? thread.get("id").getAsString() : null;
    }

    /**
     * {@code turn/start}'s response also nests {@code result.turn.id} — needed
     * later for turn/interrupt.
     */
    static String extractTurnId(JsonObject result) {
        if (result == null || !result.has("turn")) {
            return null;
        }
        JsonElement turnEl = result.get("turn");
        if (!turnEl.isJsonObject()) {
            return null;
        }
        JsonObject turn = turnEl.getAsJsonObject();
        return turn.has("id") && !turn.get("id").isJsonNull() ? turn.get("id").getAsString() : null;
    }

    /**
     * {@code thread/start}/{@code thread/resume} echo the model actually
     * applied back as a top-level {@code result.model} (sibling of
     * {@code result.thread}, live-probe confirmed) — used to detect a silent
     * model-request mismatch, the same failure class an earlier OpenCode bug
     * produced.
     */
    static String extractModel(JsonObject result) {
        if (result == null || !result.has("model") || result.get("model").isJsonNull()) {
            return null;
        }
        return result.get("model").getAsString();
    }

    /**
     * Per-invocation {@code -c} overrides that register the plugin's MCP
     * endpoint with Codex for this one process — never written to
     * {@code ~/.codex/config.toml} (design doc §0a: {@code -c} is TOML-parsed
     * and per-spawn, which is what avoids the cross-session credential
     * collision a shared config file would create).
     *
     * <p>
     * {@code default_tools_approval_mode} avoids double-gating: this plugin
     * already gates every mutating tool itself — {@code ApplyEdit}/{@code
     * WriteFile} through the diff panel, {@code DeleteFile}/{@code CopyFile}/
     * {@code MoveFile} through the confirm panel — so a Codex-side prompt on
     * top asks the user twice for one action, and for a read-only tool like
     * {@code GetInstructions} it asks about nothing at all.
     *
     * <p>
     * The accepted values are {@code auto}, {@code prompt}, {@code writes} and
     * {@code approve} (confirmed by feeding the binary a bad value and reading
     * the variants back out of the deserialiser). This was {@code "auto"}, and
     * a live run showed Codex still prompting for every single tool call,
     * including read-only ones — {@code auto} appears to decide from per-tool
     * metadata, and these tools carry no read-only annotations for it to go on.
     * {@code approve} is the "already approved, do not ask" end of that axis.
     *
     * <p>
     * Safe because it does not widen what Codex may do: it only stops Codex
     * asking a second time about actions this plugin already gates. Anything
     * that mutates still stops at the diff or confirm panel.
     *
     * <p>
     * NOT yet confirmed live — verify that tool calls stop prompting, and that
     * a file edit still raises the diff panel. If prompts persist, the next
     * thing to check is whether the server-side
     * {@code mcpServer/elicitation/request} is raised independently of this
     * setting, in which case the answer is to annotate the read-only tools
     * rather than to change this value again.
     *
     * <p>
     * No header-based credentials are added here (unlike the design doc's
     * original {@code http_headers}/{@code env_http_headers} sketch) —
     * {@code McpHookServer}'s {@code tools/call} handler authenticates from
     * {@code arguments.sessionId}/{@code arguments.secretKey} only, never from
     * HTTP headers, and those travel to Codex the same way they do for every
     * other AI type: prepended to the prompt text by
     * {@code ContextProvider.buildIdentityBlock()}, gated on
     * {@code AiTypeEnum.CODEX}'s {@code CREDENTIALS} mcpOption (already set).
     */
    static List<String> buildMcpConfigArgs(String mcpEndpointUrl) {
        if (mcpEndpointUrl == null || mcpEndpointUrl.isBlank()) {
            return List.of();
        }
        return List.of(
                "-c", "mcp_servers." + MCP_SERVER_NAME + ".url=\"" + mcpEndpointUrl + "\"",
                "-c", "mcp_servers." + MCP_SERVER_NAME + ".default_tools_approval_mode=\"approve\"");
    }

    private final List<String> recentStderr = new CopyOnWriteArrayList<>();
    private volatile CodexJsonRpcClient client;
    private volatile CodexAppServerHandler appServerHandler;
    private volatile String threadId;
    private volatile String currentTurnId;
    private volatile CodexAiMcpRegistrar registrar;
    private CodexAiSession codexAiSession;
    /**
     * Set when {@link #interrupt} runs before {@code turn/start}'s response has
     * delivered {@link #currentTurnId} — {@code turn/interrupt} needs both ids
     * (schema-confirmed) and cannot be sent yet. {@link #sendTurn}'s response
     * continuation checks this and fires the deferred interrupt the instant the
     * turn id becomes known, instead of the request silently going nowhere.
     */
    private volatile boolean interruptRequested;
    volatile String pendingResumeThreadId;
    volatile Runnable onSessionEstablished;

    public CodexAiProcessManager(AiProcessEventListener listener) {
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

        if (!CodexExecutableLocator.isExecutableFile(executablePath)) {
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

        // MCP registration: start the shared HTTP server. Degrade gracefully on failure —
        // MCP tools are an enhancement, not a precondition for basic chat to work.
        CodexAiMcpRegistrar reg = new CodexAiMcpRegistrar(sessionId);
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

        codexAiSession = new CodexAiSession(currentSession, listener);

        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Codex start() [{0}]: executable={1} model={2} mcpActive={3}",
                    new Object[]{sessionId, executablePath, model, registrar != null});
        }

        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("Codex")));
    }

    /**
     * Spawns the codex process and performs the handshake. Always called on a
     * background thread — blocks up to 30 s per request. The instance monitor
     * is held only for brief state writes, never across the blocking waits,
     * mirroring {@code OpenCodeAiProcessManager.spawnAndHandshake}.
     */
    protected void spawnAndHandshake(File workDir) throws Exception {
        String mcpEndpointUrl = registrar != null ? McpServerRegistry.endpointUrlFor(AiTypeEnum.CODEX) : null;
        List<String> baseArgs = new ArrayList<>(List.of("app-server", "--listen", "stdio://"));
        baseArgs.addAll(buildMcpConfigArgs(mcpEndpointUrl));
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Codex MCP config for this spawn: {0}",
                    mcpEndpointUrl != null ? "mcp_servers." + MCP_SERVER_NAME + ".url=" + mcpEndpointUrl : "none");
        }
        List<String> cmd = CodexExecutableLocator.buildHostCommand(
                executablePath, baseArgs.toArray(new String[0]));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);

        recentStderr.clear();
        Process p = pb.start();

        synchronized (this) {
            if (!running) {
                p.destroyForcibly();
                throw new IOException("stop() called before handshake began");
            }
            currentProcess = p;
        }

        startStderrDrainer(p);
        p.onExit().thenRun(() -> handleProcessExit(p));

        CodexAppServerHandler handler = new CodexAppServerHandler(listener, this::onHandlerDisconnected);
        CodexJsonRpcClient c = new CodexJsonRpcClient(p.getOutputStream(), p.getInputStream(),
                this::onNotification, handler::onServerRequest, handler::onDisconnected);

        String resumeId = pendingResumeThreadId;
        JsonObject threadResult;
        try {
            c.sendRequest("initialize", buildInitializeParams(CLIENT_NAME, CLIENT_TITLE, PLUGIN_VERSION))
                    .get(30, TimeUnit.SECONDS);
            c.sendNotification("initialized", new JsonObject());

            if (resumeId != null) {
                try {
                    threadResult = c.sendRequest("thread/resume",
                            buildThreadResumeParams(resumeId, workDir.getAbsolutePath(), model))
                            .get(30, TimeUnit.SECONDS);
                }
                catch (Exception e) {
                    LOG.log(Level.INFO, "thread/resume failed; falling back to thread/start: {0}", e.getMessage());
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                            "Previous Codex session could not be resumed; starting fresh"));
                    threadResult = c.sendRequest("thread/start",
                            buildThreadStartParams(workDir.getAbsolutePath(), model))
                            .get(30, TimeUnit.SECONDS);
                }
            }
            else {
                threadResult = c.sendRequest("thread/start",
                        buildThreadStartParams(workDir.getAbsolutePath(), model))
                        .get(30, TimeUnit.SECONDS);
            }
        }
        catch (Exception e) {
            c.close();
            synchronized (this) {
                if (currentProcess == p) {
                    currentProcess = null;
                }
            }
            p.destroyForcibly();
            throw e;
        }

        String id = extractThreadId(threadResult);
        if (id == null || id.isBlank()) {
            c.close();
            synchronized (this) {
                if (currentProcess == p) {
                    currentProcess = null;
                }
            }
            p.destroyForcibly();
            throw new IOException((resumeId != null ? "thread/resume" : "thread/start") + " returned no usable thread id");
        }

        synchronized (this) {
            if (!running) {
                c.close();
                p.destroyForcibly();
                if (currentProcess == p) {
                    currentProcess = null;
                }
                throw new IOException("stop() called during handshake");
            }
            threadId = id;
            pendingResumeThreadId = null;
            if (currentSession != null && currentSession.settings() instanceof CodexSessionSettings cs) {
                cs.setThreadId(id);
            }
            appServerHandler = handler;
            client = c;
        }
        String actualModel = extractModel(threadResult);
        if (model != null && !model.isBlank() && actualModel != null && !actualModel.equals(model)) {
            LOG.log(Level.WARNING, "Codex model mismatch: requested \"{0}\" but thread started with \"{1}\"",
                    new Object[]{model, actualModel});
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Codex app-server handshake complete, threadId={0} requestedModel={1} actualModel={2}",
                    new Object[]{threadId, model, actualModel});
        }
        Runnable cb = onSessionEstablished;
        if (cb != null) {
            cb.run();
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

        if (client == null) {
            // spawnAndHandshake blocks for up to 90 s; sendPrompt runs on the EDT.
            // Hand off to a background thread and return immediately so the UI stays
            // responsive. processing=true prevents a second submit from racing the handshake.
            processing = true;
            final File wd = effectiveWorkDir;
            new Thread(() -> handshakeAndSend(text, wd, projectDirs), "codex-handshake").start();
            return;
        }
        sendTurn(text);
    }

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
        synchronized (this) {
            processing = false;
            if (!running) {
                return; // stop() was called while we were handshaking
            }
        }
        sendPrompt(text, workDir, projectDirs);
    }

    private synchronized void sendTurn(String text) {
        CodexJsonRpcClient c = client;
        CodexAppServerHandler handler = appServerHandler;
        String tid = threadId;
        if (c == null || handler == null || tid == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, "Codex session is not active"));
            return;
        }
        processing = true;
        interruptRequested = false;
        handler.onTurnStarting();
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "codex turn/start [{0}]: {1}", new Object[]{tid, text});
        }
        c.sendRequest("turn/start", buildTurnStartParams(tid, text))
                .thenAccept(result -> {
                    String newTurnId = extractTurnId(result);
                    boolean fireDeferredInterrupt;
                    synchronized (CodexAiProcessManager.this) {
                        currentTurnId = newTurnId;
                        fireDeferredInterrupt = interruptRequested && newTurnId != null;
                        if (fireDeferredInterrupt) {
                            interruptRequested = false;
                        }
                    }
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "codex turn/start response [{0}]: turnId={1}",
                                new Object[]{tid, newTurnId});
                    }
                    if (fireDeferredInterrupt) {
                        // interrupt() ran while turnId was still unknown and could not send
                        // turn/interrupt (needs both ids) — send it now rather than leaving
                        // the turn running server-side after the user already asked to stop.
                        c.sendRequest("turn/interrupt", buildTurnInterruptParams(tid, newTurnId));
                    }
                })
                .exceptionally(ex -> {
                    synchronized (CodexAiProcessManager.this) {
                        processing = false;
                        interruptRequested = false;
                    }
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                            "turn/start failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.toString())));
                    return null;
                });
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
        CodexJsonRpcClient c;
        CodexAppServerHandler handler;
        String tid;
        String tuid;
        synchronized (this) {
            if (!processing) {
                // "Stop did nothing because nothing was running" and "Stop ran but
                // output kept coming" are indistinguishable after the fact — this
                // branch is the first of those.
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Codex interrupt: IGNORED, no turn in flight (threadId={0})", threadId);
                }
                return;
            }
            cancelledByUser = true;
            processing = false;
            c = client;
            handler = appServerHandler;
            tid = threadId;
            tuid = currentTurnId;
            if (tuid == null) {
                // turn/start's response (which carries turnId) has not arrived yet.
                // turn/interrupt needs both ids and cannot be sent — defer to
                // sendTurn's response continuation, which fires it once the id
                // is known, instead of silently doing nothing.
                interruptRequested = true;
            }
        }
        // Stamping the moment the user actually pressed Stop is the only way to
        // measure the wind-down tail afterwards: without it, "it carried on after
        // I stopped it" cannot be told apart from a normal wind-down, and the
        // agent's own log gives no click time to compare against.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Codex interrupt: user pressed Stop, cancelling turn (threadId={0}, connected={1})",
                    new Object[]{tid, c != null});
        }
        // Cancel any outstanding permission dialog before sending turn/interrupt,
        // so Codex receives the permission reply (cancel) before the interrupt —
        // mirrors OpenCodeAiProcessManager.interrupt()'s ordering.
        if (handler != null) {
            handler.cancelPendingPermissions();
        }
        if (c != null && tid != null && tuid != null) {
            c.sendRequest("turn/interrupt", buildTurnInterruptParams(tid, tuid));
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Codex interrupt: turn/interrupt sent (threadId={0}, turnId={1})",
                        new Object[]{tid, tuid});
            }
        }
        else if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Codex interrupt: turn/interrupt deferred, turnId not yet known (threadId={0})", tid);
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
    }

    /**
     * Mail interjects the inbox notice into a running turn via {@code
     * turn/steer} instead of interrupting it — mirrors {@code
     * GithubCopilotProcessManager}'s {@code session.send(..., "immediate")}
     * approach, not Claude's turn-interrupting one, because Codex's app-server
     * exposes steering as its own method rather than a mode on an in-flight
     * send. Only attempted while a turn is actually in flight and its turn id
     * is known; both a genuinely idle session and a refused steer (e.g.
     * {@code ActiveTurnNotSteerable}) fall back identically to doing nothing
     * further — the message is not lost, it simply arrives later via the normal
     * inbox flush. Never escalates to Cancel: interrupting the user's turn to
     * deliver a notice would be worse than delivering it late.
     */
    private void interruptMail() {
        CodexJsonRpcClient c;
        String tid;
        String tuid;
        synchronized (this) {
            if (!processing) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO,
                            "Codex interrupt: Mail IGNORED, no turn in flight — message will arrive via normal "
                            + "inbox flush (threadId={0})", threadId);
                }
                return;
            }
            c = client;
            tid = threadId;
            tuid = currentTurnId;
        }
        if (c == null || tid == null || tuid == null) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO,
                        "Codex interrupt: Mail IGNORED, turn id not yet known — message will arrive via normal "
                        + "inbox flush (threadId={0})", tid);
            }
            return;
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Codex interrupt: Mail received, attempting turn/steer (threadId={0}, turnId={1})",
                    new Object[]{tid, tuid});
        }
        c.sendRequest("turn/steer", buildTurnSteerParams(tid, tuid, MAIL_STEER_TEXT))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.INFO,
                                    "Codex interrupt: turn/steer refused — message will arrive via normal inbox "
                                    + "flush (threadId={0}, turnId={1}, reason={2})",
                                    new Object[]{tid, tuid, ex.getMessage()});
                        }
                    }
                    else if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "Codex interrupt: turn/steer delivered (threadId={0}, turnId={1})",
                                new Object[]{tid, tuid});
                    }
                });
    }

    @Override
    public synchronized void stop() {
        // Logged before the state is torn down, so the record says what was
        // actually in flight at the moment of the stop rather than the
        // cleared-out aftermath.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO,
                    "Codex stop: shutting session down (threadId={0}, turnInFlight={1}, connected={2}, processAlive={3})",
                    new Object[]{threadId, processing, client != null,
                        currentProcess != null && currentProcess.isAlive()});
        }
        running = false;
        processing = false;
        cancelledByUser = true;
        interruptRequested = false;

        CodexJsonRpcClient c = client;
        client = null;
        CodexAppServerHandler handler = appServerHandler;
        appServerHandler = null;
        String tid = threadId;
        threadId = null;
        currentTurnId = null;
        pendingResumeThreadId = null;
        Process p = currentProcess;
        currentProcess = null;

        // Complete any outstanding permission dialog exceptionally so its
        // .handle() chain fires and replies "cancel" to Codex — otherwise a
        // stop() while awaiting approval leaves the dialog up and Codex's turn
        // wedged. cancelPendingPermissions() lives in CodexAppServerHandler.
        if (handler != null) {
            handler.cancelPendingPermissions();
        }
        if (c != null) {
            c.close();
        }
        if (p != null) {
            p.destroy();
        }

        CodexAiSession sess = codexAiSession;
        codexAiSession = null;
        if (sess != null) {
            sess.dispose();
        }
        CodexAiMcpRegistrar reg = registrar;
        registrar = null;
        if (reg != null) {
            McpServerRegistry.deregister(reg);
        }

        sessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        recentStderr.clear();
    }

    @Override
    public void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        // Unlike OpenCode's "ses_"-prefixed ids, Codex thread ids are plain
        // UUIDv7 with no distinguishing marker, so there is no format guard to
        // add here. The real defence is CodexAiImplementation.resumeSession()
        // overriding this call to always pass the id stored in
        // CodexSessionSettings.threadId(), never the raw plugin session id
        // AiTopComponent.loadHistory() would otherwise pass straight through.
        pendingResumeThreadId = existingSessionId;
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }

    public String threadId() {
        return threadId;
    }

    String currentTurnId() {
        return currentTurnId;
    }

    CodexJsonRpcClient client() {
        return client;
    }

    CodexAppServerHandler appServerHandler() {
        return appServerHandler;
    }

    private void onNotification(String method, JsonObject params) {
        if (CodexAppServerHandler.METHOD_TURN_COMPLETED.equals(method)) {
            synchronized (this) {
                processing = false;
            }
        }
        CodexAppServerHandler handler = appServerHandler;
        if (handler != null) {
            handler.onNotification(method, params);
        }
    }

    /**
     * Fires on the reader thread's stream-EOF disconnect signal, which can
     * arrive before or after
     * {@link Process#onExit()} — {@link #handleProcessExit} is the
     * authoritative source for the {@code EXITED} status event and exit code,
     * but this must independently clear {@code client}/{@code appServerHandler}
     * /{@code threadId} too. Without that, a crash detected here but not yet by
     * {@code onExit} leaves {@code client} non-null, so the next
     * {@code sendPrompt} takes the {@code sendTurn} path against a dead
     * connection instead of re-handshaking — the busy-forever bug this fixes.
     * Deliberately does NOT null {@code currentProcess}: that stays
     * {@link #handleProcessExit}'s job so its own staleness guard
     * (`currentProcess != dead`) keeps working.
     */
    void onHandlerDisconnected() {
        boolean suppress;
        CodexJsonRpcClient orphaned;
        synchronized (this) {
            processing = false;
            orphaned = client;
            client = null;
            appServerHandler = null;
            threadId = null;
            currentTurnId = null;
            interruptRequested = false;
            suppress = cancelledByUser;
        }
        // Nulling the field alone abandons the object: its notify/dispatch
        // executors are only ever shut down by close(), so an orphaned client
        // leaks two live thread pools forever. close() is idempotent (guarded
        // by its own closed.compareAndSet), so this is safe even if stop() or
        // handleProcessExit also closes the same instance. Safe to call from
        // here even though this method itself runs ON the notify executor
        // (close()'s notifyExecutor.shutdown() lets the in-flight task —
        // this one — finish; it does not block or self-deadlock).
        if (orphaned != null) {
            orphaned.close();
        }
        if (!suppress && PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Codex app-server disconnected");
        }
    }

    /**
     * {@link Process#onExit()} callback — the only reliable signal that the
     * subprocess itself died (as opposed to the reader thread merely losing its
     * stream, which {@link #onHandlerDisconnected} handles). Mirrors
     * {@code OpenCodeAiProcessManager.handleProcessExit}: reports
     * {@code EXITED} with the exit code and recent stderr so a crash is visible
     * instead of leaving the session looking READY with no message at all.
     */
    void handleProcessExit(Process dead) {
        boolean suppress;
        CodexJsonRpcClient orphaned;
        synchronized (this) {
            if (currentProcess != dead) {
                return; // stale exit from a superseded process
            }
            processing = false;
            currentProcess = null;
            orphaned = client;
            client = null;
            appServerHandler = null;
            threadId = null;
            currentTurnId = null;
            interruptRequested = false;
            suppress = cancelledByUser;
        }
        // Same leak this method must not reintroduce even if onHandlerDisconnected
        // somehow fires after this (e.g. SIGKILL) — see its javadoc. close() is
        // idempotent, so closing an already-closed client here is a safe no-op.
        if (orphaned != null) {
            orphaned.close();
        }
        int code = dead.exitValue();
        if (!suppress && code != 0) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                    StatusMessageUtil.formatExited("Codex", code, new ArrayList<>(recentStderr))));
        }
    }

    private void startStderrDrainer(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.WARNING, "codex stderr: {0}", line);
                    }
                    recentStderr.add(line);
                    while (recentStderr.size() > MAX_STDERR_LINES) {
                        recentStderr.remove(0);
                    }
                }
            }
            catch (IOException e) {
                LOG.log(Level.FINE, "codex stderr drainer ended", e);
            }
        }, "codex-stderr");
        t.setDaemon(true);
        t.start();
    }
}
