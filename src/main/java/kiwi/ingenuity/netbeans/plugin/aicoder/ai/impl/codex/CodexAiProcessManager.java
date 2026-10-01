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
import java.util.concurrent.CompletableFuture;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexReasoningEffortEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Owns one {@code codex app-server} subprocess per plugin session, frames newline-delimited JSON-RPC 2.0 on
 * its stdin/stdout via {@link CodexJsonRpcClient}, and drives the turn lifecycle. Streaming text and the
 * permission bridge live in {@link CodexAppServerHandler}. MCP registration is handled here via
 * {@link CodexAiMcpRegistrar} and a per-spawn {@code -c mcp_servers.<name>.url=...} override; the info bar is
 * still a later slice.
 *
 * <p>
 * The process is spawned lazily on the first {@link #sendPrompt} call, same as
 * {@code OpenCodeAiProcessManager} — {@link #start} only validates preconditions and reports READY, so
 * opening a session tab never spawns a process the user might never use.
 *
 * <p>
 * Handshake order: {@code initialize} -> {@code initialized} -> {@code thread/start} (or
 * {@code thread/resume} with a saved thread id), capturing the thread id from the response — paired to that
 * exact request by id, so it cannot race or be missed the way the {@code thread/started} notification could
 * (the warning about OpenCode's resume bug).
 *
 * <p>
 * {@code thread/start}/{@code thread/resume} send {@code sandbox:
 * "workspace-write"} and {@code approvalPolicy: "untrusted"} and the resolved model — {@code
 * ThreadStartParams.model} is honored directly (confirmed by live probe: a non-default model requested in the
 * params came back unchanged in the response), unlike OpenCode, which has no model parameter on {@code
 * session/new} and needs a post-hoc {@code session/set_config_option} dance.
 */
public class CodexAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(CodexAiProcessManager.class.getName());
    private static final int MAX_STDERR_LINES = 100;
    private static final String CLIENT_NAME = "aicoder-netbeans";
    private static final String CLIENT_TITLE = "AI Coder for NetBeans";

    /**
     * TOML key segment under {@code mcp_servers.<name>} — same identity Claude/Grok register under.
     */
    static final String MCP_SERVER_NAME = StringConst.PLUGIN_ID;
    /**
     * Mail interrupt text — same spirit and wording as {@code
     * GithubCopilotProcessManager}'s Mail notice, so the on-screen behaviour reads the same across backends
     * that support mid-turn injection.
     */
    static final String MAIL_STEER_TEXT = InterruptTypeEnum.MAIL_NOTIFICATION_TEXT;

    static JsonObject buildInitializeParams(String clientName, String clientTitle, String clientVersion) {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty(CodexJsonKeyEnum.NAME.key(), clientName);
        clientInfo.addProperty(CodexJsonKeyEnum.TITLE.key(), clientTitle);
        clientInfo.addProperty(CodexJsonKeyEnum.VERSION.key(), clientVersion);
        JsonObject params = new JsonObject();
        params.add(CodexJsonKeyEnum.CLIENT_INFO.key(), clientInfo);
        params.add(CodexJsonKeyEnum.CAPABILITIES.key(), new JsonObject());
        return params;
    }

    /**
     * {@code sandbox}/{@code approvalPolicy} are plain wire strings on {@code
     * ThreadStartParams}/{@code ThreadResumeParams} (kebab-case) — NOT the same shape as
     * {@code TurnStartParams.sandboxPolicy}, which is an object with a camelCase {@code type}
     * (readOnly/workspaceWrite/dangerFullAccess). Confirmed by reading both generated schemas; easy to
     * conflate since they cover the same concept. {@code model} is omitted (letting Codex use its own
     * default) when null or blank.
     */
    static JsonObject buildThreadStartParams(String cwd, String model) {
        JsonObject params = new JsonObject();
        params.addProperty(CodexJsonKeyEnum.CWD.key(), cwd);
        params.addProperty(CodexJsonKeyEnum.SANDBOX.key(), "workspace-write");
        params.addProperty(CodexJsonKeyEnum.APPROVAL_POLICY.key(), "untrusted");
        if (model != null && !model.isBlank()) {
            params.addProperty(CodexJsonKeyEnum.MODEL.key(), model);
        }
        return params;
    }

    static JsonObject buildThreadResumeParams(String threadId, String cwd, String model) {
        JsonObject params = new JsonObject();
        params.addProperty(CodexJsonKeyEnum.THREAD_ID.key(), threadId);
        params.addProperty(CodexJsonKeyEnum.CWD.key(), cwd);
        params.addProperty(CodexJsonKeyEnum.SANDBOX.key(), "workspace-write");
        params.addProperty(CodexJsonKeyEnum.APPROVAL_POLICY.key(), "untrusted");
        if (model != null && !model.isBlank()) {
            params.addProperty(CodexJsonKeyEnum.MODEL.key(), model);
        }
        return params;
    }

    /**
     * {@code TurnStartParams}, codex-cli 0.155.0: {@code threadId} and the {@code input} array as usual, plus
     * an optional {@code effort} override — "Override the reasoning effort for this turn and subsequent
     * turns." A null or blank {@code effort} omits the field, letting the model apply its own default (the
     * "not set" entry {@code (model default)}).
     */
    static JsonObject buildTurnStartParams(String threadId, String promptText, String effort) {
        JsonObject textInput = new JsonObject();
        textInput.addProperty(CodexJsonKeyEnum.TYPE.key(), "text");
        textInput.addProperty(CodexJsonKeyEnum.TEXT.key(), promptText);
        JsonArray input = new JsonArray();
        input.add(textInput);
        JsonObject params = new JsonObject();
        params.addProperty(CodexJsonKeyEnum.THREAD_ID.key(), threadId);
        params.add(CodexJsonKeyEnum.INPUT.key(), input);
        if (effort != null && !effort.isBlank()) {
            params.addProperty(CodexJsonKeyEnum.EFFORT.key(), effort);
        }
        return params;
    }

    /**
     * {@code TurnInterruptParams} requires both ids (schema-confirmed) — threadId alone is not enough.
     */
    static JsonObject buildTurnInterruptParams(String threadId, String turnId) {
        JsonObject params = new JsonObject();
        params.addProperty(CodexJsonKeyEnum.THREAD_ID.key(), threadId);
        params.addProperty(CodexJsonKeyEnum.TURN_ID.key(), turnId);
        return params;
    }

    /**
     * {@code TurnSteerParams} (confirmed by generating the schema live against {@code codex-cli 0.148.0} with {@code codex app-server
     * generate-json-schema} — since no persisted copy of the schemas remained on disk): {@code threadId} and
     * {@code input} are the same shape as {@code turn/start}'s, but steering additionally requires
     * {@code expectedTurnId} — "Required active turn id precondition. The request fails when it does not
     * match the currently active turn." {@code TurnSteerResponse} on success is just {@code
     * {turnId}}; there is no in-band error field on either params or response, so a refusal (e.g.
     * {@code ActiveTurnNotSteerable}, returned per the schema when the active turn cannot accept same-turn
     * steering — a {@code /review} or manual {@code /compact} in progress) can only surface as a genuine
     * JSON-RPC error response, not a "successful" body. {@code
     * CodexJsonRpcClient} does not parse the error's {@code data} field at all ({@link CodexJsonRpcException}
     * carries only {@code code}/{@code
     * message}), so there is no {@code codexErrorInfo} discriminant available to branch on here even if one
     * wanted to — every {@code turn/steer} failure is handled identically (log and leave the message for the
     * normal inbox flush), which is also exactly the required behaviour for {@code
     * ActiveTurnNotSteerable} specifically.
     */
    static JsonObject buildTurnSteerParams(String threadId, String expectedTurnId, String promptText) {
        JsonObject textInput = new JsonObject();
        textInput.addProperty(CodexJsonKeyEnum.TYPE.key(), "text");
        textInput.addProperty(CodexJsonKeyEnum.TEXT.key(), promptText);
        JsonArray input = new JsonArray();
        input.add(textInput);
        JsonObject params = new JsonObject();
        params.addProperty(CodexJsonKeyEnum.THREAD_ID.key(), threadId);
        params.addProperty(CodexJsonKeyEnum.EXPECTED_TURN_ID.key(), expectedTurnId);
        params.add(CodexJsonKeyEnum.INPUT.key(), input);
        return params;
    }

    /**
     * {@code thread/compact/start} takes only the thread id; its response is an empty object and the
     * compaction then runs as a turn on that thread.
     */
    static JsonObject buildThreadCompactStartParams(String threadId) {
        JsonObject params = new JsonObject();
        params.addProperty(CodexJsonKeyEnum.THREAD_ID.key(), threadId);
        return params;
    }

    /**
     * The turn a notification belongs to: {@code params.turn.id} on {@code turn/started} and
     * {@code turn/completed}, a flat {@code params.turnId} on item, delta and token-usage notifications. Null
     * when the notification names no turn.
     */
    static String notificationTurnId(JsonObject params) {
        String nested = extractTurnId(params);
        if (nested != null) {
            return nested;
        }
        if (params != null && params.has(CodexJsonKeyEnum.TURN_ID.key())
            && params.get(CodexJsonKeyEnum.TURN_ID.key()).isJsonPrimitive()) {
            return params.get(CodexJsonKeyEnum.TURN_ID.key()).getAsString();
        }
        return null;
    }

    /**
     * Extracts the thread id from a {@code thread/start} or {@code
     * thread/resume} response — both nest it at {@code result.thread.id} (camelCase, confirmed by live
     * probe), not a top-level {@code thread_id} as an earlier unverified example (sourced from {@code codex exec
     * --json}'s unrelated JSONL format) suggested. Returns null on any unexpected shape rather than throwing
     * — callers must treat null as "handshake did not produce a usable id".
     */
    static String extractThreadId(JsonObject result) {
        if (result == null || !result.has(CodexJsonKeyEnum.THREAD.key())) {
            return null;
        }
        JsonElement threadEl = result.get(CodexJsonKeyEnum.THREAD.key());
        if (!threadEl.isJsonObject()) {
            return null;
        }
        JsonObject thread = threadEl.getAsJsonObject();
        return thread.has(CodexJsonKeyEnum.ID.key()) && !thread.get(CodexJsonKeyEnum.ID.key()).isJsonNull() ? thread.get(CodexJsonKeyEnum.ID.key()).getAsString() : null;
    }

    /**
     * {@code turn/start}'s response also nests {@code result.turn.id} — needed later for turn/interrupt.
     */
    static String extractTurnId(JsonObject result) {
        if (result == null || !result.has(CodexJsonKeyEnum.TURN.key())) {
            return null;
        }
        JsonElement turnEl = result.get(CodexJsonKeyEnum.TURN.key());
        if (!turnEl.isJsonObject()) {
            return null;
        }
        JsonObject turn = turnEl.getAsJsonObject();
        return turn.has(CodexJsonKeyEnum.ID.key()) && !turn.get(CodexJsonKeyEnum.ID.key()).isJsonNull() ? turn.get(CodexJsonKeyEnum.ID.key()).getAsString() : null;
    }

    /**
     * {@code thread/start}/{@code thread/resume} echo the model actually applied back as a top-level
     * {@code result.model} (sibling of {@code result.thread}, live-probe confirmed) — used to detect a silent
     * model-request mismatch, the same failure class an earlier OpenCode bug produced.
     */
    static String extractModel(JsonObject result) {
        if (result == null || !result.has(CodexJsonKeyEnum.MODEL.key()) || result.get(CodexJsonKeyEnum.MODEL.key()).isJsonNull()) {
            return null;
        }
        return result.get(CodexJsonKeyEnum.MODEL.key()).getAsString();
    }

    /**
     * {@code thread/start}/{@code thread/resume}/{@code thread/fork} carry the live {@code reasoningEffort}
     * (read as a top-level sibling of {@code result.model}, falling back to
     * {@code result.thread.reasoningEffort}) and seeds the info-bar combo's current selection. Returns null
     * when absent, matching the "unknown" convention of the other extractors.
     */
    static String extractReasoningEffort(JsonObject result) {
        if (result == null) {
            return null;
        }
        JsonElement top = result.get(CodexJsonKeyEnum.REASONING_EFFORT.key());
        String v = (top != null && !top.isJsonNull() && top.isJsonPrimitive()) ? top.getAsString() : null;
        if (v != null && !v.isBlank()) {
            return v;
        }
        JsonElement threadEl = result.get(CodexJsonKeyEnum.THREAD.key());
        if (threadEl != null && threadEl.isJsonObject()) {
            JsonElement te = threadEl.getAsJsonObject().get(CodexJsonKeyEnum.REASONING_EFFORT.key());
            v = (te != null && !te.isJsonNull() && te.isJsonPrimitive()) ? te.getAsString() : null;
        }
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Finds a model's entry in a {@code model/list} response ({@code data} array of Model objects) by id,
     * falling back to the deprecated {@code model} key. Returns null when absent.
     */
    static JsonObject findModelObject(JsonObject result, String model) {
        if (result == null || model == null || model.isBlank() || !result.has(CodexJsonKeyEnum.DATA.key())) {
            return null;
        }
        JsonElement dataEl = result.get(CodexJsonKeyEnum.DATA.key());
        if (!dataEl.isJsonArray()) {
            return null;
        }
        for (JsonElement e : dataEl.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            for (CodexJsonKeyEnum key : new CodexJsonKeyEnum[]{CodexJsonKeyEnum.ID, CodexJsonKeyEnum.MODEL}) {
                if (o.has(key.key()) && !o.get(key.key()).isJsonNull() && o.get(key.key()).isJsonPrimitive()
                    && model.equals(o.get(key.key()).getAsString())) {
                    return o;
                }
            }
        }
        return null;
    }

    /**
     * Reads a Model object's {@code supportedReasoningEfforts} array, taking each entry's
     * {@code reasoningEffort} value (deduped, in order). Empty when the model exposes none.
     */
    static List<String> extractSupportedReasoningEfforts(JsonObject modelObj) {
        if (modelObj == null || !modelObj.has(CodexJsonKeyEnum.SUPPORTED_REASONING_EFFORTS.key())) {
            return List.of();
        }
        JsonElement arrEl = modelObj.get(CodexJsonKeyEnum.SUPPORTED_REASONING_EFFORTS.key());
        if (!arrEl.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement e : arrEl.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonElement re = e.getAsJsonObject().get(CodexJsonKeyEnum.REASONING_EFFORT.key());
            if (re != null && !re.isJsonNull() && re.isJsonPrimitive()) {
                String s = re.getAsString();
                if (!s.isBlank() && !out.contains(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * Reads a Model object's {@code defaultReasoningEffort}; null when absent.
     */
    static String extractDefaultReasoningEffort(JsonObject modelObj) {
        if (modelObj == null || !modelObj.has(CodexJsonKeyEnum.DEFAULT_REASONING_EFFORT.key())) {
            return null;
        }
        JsonElement e = modelObj.get(CodexJsonKeyEnum.DEFAULT_REASONING_EFFORT.key());
        return (e != null && !e.isJsonNull() && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    /**
     * Probes {@code model/list} on the established client, caches the capability list for subsequent dialogs,
     * and fires a per-session {@link CodexReasoningEffortEvent} (combo data for the info bar). Runs in the
     * handshake thread; any probe failure degrades silently to "no capability info" — the backend then never
     * sends an {@code effort} field. Also applies {@link #applyInitialEffortOption} so a
     * stored-but-unsupported effort is cleared up front rather than sent and ignored.
     */
    private void fireReasoningEffortEvent(CodexJsonRpcClient c, String activeModel, String echoEffort) {
        List<String> supported = List.of();
        String defaultEffort = null;
        try {
            JsonObject result = c.sendRequest("model/list", new JsonObject()).get(30, TimeUnit.SECONDS);
            JsonObject modelObj = findModelObject(result, activeModel);
            if (modelObj != null) {
                supported = extractSupportedReasoningEfforts(modelObj);
                defaultEffort = extractDefaultReasoningEffort(modelObj);
            }
            else {
                LOG.log(Level.FINE, "model/list did not include \"{0}\"; treating as no reasoning-effort support",
                        activeModel);
            }
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "model/list unavailable; reasoning effort stays (model default): {0}",
                    e.getMessage() != null ? e.getMessage() : e.toString());
        }
        if (supported.isEmpty()) {
            defaultEffort = null;
        }
        CodexReasoningEffortCatalog.cache(activeModel, supported);
        listener.onAiProcessEvent(new CodexReasoningEffortEvent(activeModel, supported, defaultEffort, echoEffort));
        applyInitialEffortOption(activeModel, supported);
    }

    /**
     * Reasoning-effort validation at session establishment, mirroring
     * {@code OpenCodeAiProcessManager.applyInitialEffortOption}: when the stored per-session effort is not in
     * the model's supported list, clear it and fire exactly one INFO status event naming the model. Nothing
     * is cleared (and no event fires) when the effort is unset or supported.
     */
    void applyInitialEffortOption(String activeModel, List<String> supported) {
        String storedEffort;
        synchronized (this) {
            if (currentSession == null || !(currentSession.settings() instanceof CodexSessionSettings cs)) {
                return;
            }
            storedEffort = cs.effort();
        }
        if (storedEffort == null || storedEffort.isBlank()) {
            return;
        }
        if (!supported.isEmpty() && supported.contains(storedEffort)) {
            return;
        }
        String reason = supported.isEmpty()
                        ? "but " + activeModel + " exposes no supported reasoning efforts from the server"
                        : "it is not one of the supported efforts for " + activeModel + "; using the model default";
        synchronized (this) {
            if (currentSession != null && currentSession.settings() instanceof CodexSessionSettings cs) {
                cs.setEffort(null);
            }
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                "Reasoning effort \"" + storedEffort + "\" cleared — " + reason));
    }

    /**
     * The per-session reasoning-effort override to send with this turn, read from the live
     * {@code CodexSessionSettings}; null means "omit the field" (model default).
     */
    private String currentEffortOverride() {
        synchronized (this) {
            if (currentSession != null && currentSession.settings() instanceof CodexSessionSettings cs) {
                return cs.effort();
            }
        }
        return null;
    }

    /**
     * Per-invocation {@code -c} overrides that register the plugin's MCP endpoint with Codex for this one
     * process — never written to {@code ~/.codex/config.toml} ({@code -c} is TOML-parsed and per-spawn, which
     * is what avoids the cross-session credential collision a shared config file would create).
     *
     * <p>
     * {@code default_tools_approval_mode} avoids double-gating: this plugin already gates every mutating tool
     * itself — {@code ApplyEdit}/{@code
     * WriteFile} through the diff panel, {@code DeleteFile}/{@code CopyFile}/ {@code MoveFile} through the
     * confirm panel — so a Codex-side prompt on top asks the user twice for one action, and for a read-only
     * tool like {@code GetInstructions} it asks about nothing at all.
     *
     * <p>
     * The accepted values are {@code auto}, {@code prompt}, {@code writes} and {@code approve} (confirmed by
     * feeding the binary a bad value and reading the variants back out of the deserialiser). This was
     * {@code "auto"}, and a live run showed Codex still prompting for every single tool call, including
     * read-only ones — {@code auto} appears to decide from per-tool metadata, and these tools carry no
     * read-only annotations for it to go on. {@code approve} is the "already approved, do not ask" end of
     * that axis.
     *
     * <p>
     * Safe because it does not widen what Codex may do: it only stops Codex asking a second time about
     * actions this plugin already gates. Anything that mutates still stops at the diff or confirm panel.
     *
     * <p>
     * NOT yet confirmed live — verify that tool calls stop prompting, and that a file edit still raises the
     * diff panel. If prompts persist, the next thing to check is whether the server-side
     * {@code mcpServer/elicitation/request} is raised independently of this setting, in which case the answer
     * is to annotate the read-only tools rather than to change this value again.
     *
     * <p>
     * No header-based credentials are added here (unlike an earlier
     * {@code http_headers}/{@code env_http_headers} sketch) — {@code McpHookServer}'s {@code tools/call}
     * handler authenticates from {@code arguments.sessionId}/{@code arguments.secretKey} only, never from
     * HTTP headers, and those travel to Codex the same way they do for every other AI type: prepended to the
     * prompt text by {@code ContextProvider.buildIdentityBlock()}, gated on {@code AiTypeEnum.CODEX}'s
     * {@code CREDENTIALS} mcpOption (already set).
     */
    static List<String> buildMcpConfigArgs(String mcpEndpointUrl) {
        if (mcpEndpointUrl == null || mcpEndpointUrl.isBlank()) {
            return List.of();
        }
        // Codex's mcp_servers.<id>.tool_timeout_sec setting is in seconds.
        long toolTimeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(
                CodexTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis());
        return List.of(
                "-c", "mcp_servers." + MCP_SERVER_NAME + ".url=\"" + mcpEndpointUrl + "\"",
                "-c", "mcp_servers." + MCP_SERVER_NAME + ".default_tools_approval_mode=\"approve\"",
                "-c", "mcp_servers." + MCP_SERVER_NAME + ".tool_timeout_sec=" + toolTimeoutSeconds);
    }

    private final List<String> recentStderr = new CopyOnWriteArrayList<>();
    private volatile CodexJsonRpcClient client;
    private volatile CodexAppServerHandler appServerHandler;
    private volatile String threadId;
    private volatile String currentTurnId;
    private volatile CodexAiMcpRegistrar registrar;
    private CodexAiSession codexAiSession;
    /**
     * Set when {@link #interrupt} runs before {@code turn/start}'s response has delivered
     * {@link #currentTurnId} — {@code turn/interrupt} needs both ids (schema-confirmed) and cannot be sent
     * yet. {@link #sendTurn}'s response continuation checks this and fires the deferred interrupt the instant
     * the turn id becomes known, instead of the request silently going nowhere.
     */
    private volatile boolean interruptRequested;

    /**
     * One in-flight {@code thread/compact/start}. Codex runs a compaction as a TURN on the thread, so its
     * notifications arrive on the same stream as a real turn's; this identifies which of them belong to the
     * compaction so they can be kept away from the UI. {@link #done} completes on that turn's
     * {@code turn/completed}, which is what closes the work.
     */
    static final class CompactionTurn {

        final CompletableFuture<Void> done = new CompletableFuture<>();
        /**
         * The id from the {@code turn/started} that followed the request; null until it arrives.
         */
        volatile String turnId;
    }

    /**
     * The compaction turn whose notifications must be swallowed. Set when {@code thread/compact/start} is
     * sent and cleared only by that turn's own {@code turn/completed} (or a failed request, or the session
     * ending) — deliberately NOT when the work closes, so a compaction that outlives the work's timeout is
     * still kept from the UI as a real turn end.
     */
    private volatile CompactionTurn compaction;

    /**
     * The process whose in-flight work losing the stream closed first, so the process-exit that follows knows
     * the session already got its one closing status and must not add an EXITED. Identity-compared in
     * {@link #handleProcessExit}, so a token left over from a superseded process can never suppress the
     * EXITED of a later one. Guarded by {@code this}.
     */
    private Process workClosedByDisconnectOf;
    /**
     * The turn a handshake in flight was started for: set by {@link #submitPrompt} when it hands the prompt
     * to the handshake thread, cleared by whatever ends that turn first — Stop, {@link #stop()}, or an exit
     * that reports EXITED. The handshake only sends the prompt, reports FAILED or clears {@code processing}
     * while its turn is still this one, so a handshake that outlived its turn can neither start a turn the
     * user stopped, add a second closing status, nor clear the {@code processing} of a newer turn. Compared
     * by identity; guarded by {@code this}.
     */
    Object handshakeTurn;
    /**
     * Set when Stop cancelled a turn whose {@code turn/completed} has not arrived yet. The UI is unlocked at
     * that point, but the turn is still winding down (or has not even reported {@code turn/started}), and a
     * compaction armed now would claim that dead turn as its own. Cleared by any {@code turn/completed} and
     * whenever the connection goes away. Guarded by {@code this}.
     */
    private boolean stoppedTurnWindingDown;
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
     * Spawns the codex process and performs the handshake. Always called on a background thread — blocks up
     * to 30 s per request. The instance monitor is held only for brief state writes, never across the
     * blocking waits, mirroring {@code OpenCodeAiProcessManager.spawnAndHandshake}.
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

        // Superseding an already-live client/process pair must shut that pair
        // down deterministically HERE. Once currentProcess/client are overwritten,
        // both of the predecessor's only closers are disabled by the supersede
        // itself: handleProcessExit dead-ends at its stale-exit guard
        // (currentProcess != dead), and onHandlerDisconnected fired from the
        // predecessor reader's stream-EOF closes whatever the client field points
        // at NOW — the new client, not the one whose process really died. Orphaned
        // that way, the superseded client leaks its codex-notify / codex-dispatch
        // thread pools forever (CodexJsonRpcClientTest.closeShutsBothExecutors).
        CodexJsonRpcClient superseded;
        Process supersededProcess;
        synchronized (this) {
            if (!running) {
                p.destroyForcibly();
                throw new IOException("stop() called before handshake began");
            }
            superseded = client;
            supersededProcess = currentProcess;
            client = null;
            appServerHandler = null;
            currentProcess = p;
        }
        if (superseded != null) {
            superseded.close();
        }
        if (supersededProcess != null && supersededProcess.isAlive()) {
            supersededProcess.destroy();
        }

        startStderrDrainer(p);
        p.onExit().thenRun(() -> handleProcessExit(p));

        CodexAppServerHandler handler = new CodexAppServerHandler(sessionId, listener, this::onHandlerDisconnected);
        CodexJsonRpcClient c = new CodexJsonRpcClient(p.getOutputStream(), p.getInputStream(),
                this::onNotification, handler::onServerRequest, handler::onDisconnected);

        String resumeId = pendingResumeThreadId;
        JsonObject threadResult;
        try {
            c.sendRequest("initialize", buildInitializeParams(CLIENT_NAME, CLIENT_TITLE, Installer.VERSION))
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

        beforeHandshakePublish(c);
        if (c.isStreamEnded()) {
            // Codex's output has ended: either it crashed (it closes its output as it dies, so the exit is
            // moments away) or it is hung. Give a crash the chance to show as one before deciding, outside the
            // lock, which handleProcessExit needs.
            awaitExit(p);
        }
        boolean exitedUnreported = false;
        synchronized (this) {
            if (!running) {
                c.close();
                p.destroyForcibly();
                if (currentProcess == p) {
                    currentProcess = null;
                }
                throw new IOException("stop() called during handshake");
            }
            if (currentProcess != p) {
                // The process died between answering thread/start and this publish, and handleProcessExit has
                // already run for it — while client was still null, so it had nothing to close. Publishing c
                // now would hand every later prompt a dead connection (submitPrompt only re-handshakes when
                // client is null) and leak c's executors, which only close() shuts down.
                c.close();
                p.destroyForcibly();
                throw new IOException("Codex exited during start-up");
            }
            if (c.isStreamEnded()) {
                if (!p.isAlive()) {
                    // It crashed, and its exit has not been handled yet. Report it as the crash it is — EXITED with
                    // its code and stderr — by running the exit handling now, below, rather than a FAILED.
                    exitedUnreported = true;
                }
                else {
                    // Hung: output gone, process still running, so nothing can ever be read from c and no exit
                    // will come to say so. Kill it and detach first, so its exit arrives as stale and adds no
                    // EXITED: the handshake's own FAILED is the turn's one closer.
                    currentProcess = null;
                    c.close();
                    p.destroyForcibly();
                    throw new IOException("Codex stopped responding during start-up");
                }
            }
            if (!exitedUnreported) {
                threadId = id;
                pendingResumeThreadId = null;
                if (currentSession != null && currentSession.settings() instanceof CodexSessionSettings cs) {
                    cs.setThreadId(id);
                }
                appServerHandler = handler;
                client = c;
            }
        }
        if (exitedUnreported) {
            // handleProcessExit reports the crash once and ends the turn (clearing handshakeTurn when it reports
            // EXITED), so the catch this throw reaches adds nothing. Its own onExit callback, when it runs, finds the
            // process no longer current and does nothing.
            c.close();
            handleProcessExit(p);
            throw new IOException("Codex exited during start-up");
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
        // Reasoning-effort capability probe: model/list carries each
        // model's supportedReasoningEfforts + defaultReasoningEffort; thread/start
        // echoed the live reasoningEffort for seeding the info bar.
        //
        // ORDERING INVARIANT: fireReasoningEffortEvent applies the clear (via
        // applyInitialEffortOption) BEFORE cb.run() below, which calls
        // host.updateSessionSettings and persists whatever the settings object
        // holds at that moment. If this ordering is ever reversed, the clear only
        // ever updates the in-memory field, never reaches persisted settings, and
        // the same stale value re-fires the same event on every future session
        // start (see OpenCodeAiProcessManager.applyInitialModeIfNeeded, lines
        // 536-541).
        String echoEffort = extractReasoningEffort(threadResult);
        fireReasoningEffortEvent(c, actualModel != null && !actualModel.isBlank() ? actualModel : model, echoEffort);
        Runnable cb = onSessionEstablished;
        if (cb != null) {
            cb.run();
        }
    }

    /**
     * How long a handshake whose connection lost its stream waits for the process to exit before treating it
     * as hung. A crashing process closes its output as it dies, so its exit follows within milliseconds; this
     * only has to outlast that.
     */
    private static final long STREAM_END_EXIT_GRACE_MILLIS = 2_000L;

    private static void awaitExit(Process p) {
        try {
            p.waitFor(STREAM_END_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs on the handshake thread after the thread id is known and before the connection is published.
     * No-op; the window it marks is the one the process can die in, so a test can hold the handshake here and
     * let the exit win deterministically.
     */
    void beforeHandshakePublish(CodexJsonRpcClient c) {
    }

    @Override
    public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        String refusal = submitPrompt(text, workingDir, projectDirs);
        if (refusal != null) {
            // The UI has already entered busy for this prompt and only calls sendPrompt once the previous
            // turn's closer arrived, so a backend still "processing" is unwinding a turn the UI closed. Every
            // refusal therefore closes the turn it opened: INFO says why, TurnCompleteEvent ends it.
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, refusal));
            listener.onAiProcessEvent(new TurnCompleteEvent());
        }
    }

    /**
     * @return why the prompt was refused, or null when it was accepted
     */
    private synchronized String submitPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff) {
            return "Review the pending diff before sending another message";
        }
        if (!running) {
            return "Codex is not running — start the session before sending a message";
        }
        if (processing) {
            return "Codex is still working on the previous message";
        }
        if (isWorkInFlight()) {
            return "Codex is busy with another operation — try again when it finishes";
        }
        CompactionTurn stale = compaction;
        if (stale != null && stale.turnId == null) {
            // A timed-out compaction whose turn never started: don't let it swallow this prompt's turn/started.
            compaction = null;
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
            Object turn = new Object();
            handshakeTurn = turn;
            final File wd = effectiveWorkDir;
            new Thread(() -> handshakeAndSend(text, wd, turn), "codex-handshake").start();
            return null;
        }
        sendTurn(text);
        return null;
    }

    /**
     * Background-thread entry point when no app-server connection exists yet. Calls
     * {@link #spawnAndHandshake} (which blocks up to 90 s), then hands the prompt to {@link #sendTurn} once
     * the client is live — holding {@code
     * processing} true across the hand-off so an EDT {@link #sendPrompt} landing in between cannot slip past
     * its guard and start a duplicate turn/handshake. Runs entirely outside the instance monitor during the
     * blocking wait.
     */
    private void handshakeAndSend(String text, File workDir, Object turn) {
        try {
            spawnAndHandshake(workDir);
        }
        catch (Exception e) {
            boolean stillOurTurn;
            synchronized (this) {
                stillOurTurn = handshakeTurn == turn;
                if (stillOurTurn) {
                    handshakeTurn = null;
                    processing = false;
                }
            }
            // Not our turn any more: Stop, stop() or the exit already ended it, and processing may now
            // belong to a newer turn — report nothing and leave it alone.
            if (stillOurTurn) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(e.getMessage())));
            }
            return;
        }
        deliverAfterHandshake(text, turn);
    }

    /**
     * Post-handshake delivery of the prompt queued by {@link #sendPrompt}, extracted from
     * {@link #handshakeAndSend} so tests can drive the hand-off without spawning a real CLI. Keep
     * {@code processing} true through the hand-off below: sendTurn rearms it, so only paths that never reach
     * sendTurn clear it — exactly once, under the monitor. Clearing it unconditionally here reopened a window
     * in which an EDT sendPrompt saw !processing and raced this thread with a second submit.
     * <p>
     * Runs entirely under the monitor so a Stop cannot land between the turn check and {@code sendTurn}.
     */
    synchronized void deliverAfterHandshake(String text, Object turn) {
        if (handshakeTurn != turn) {
            // The turn ended while the handshake ran — Stop, stop() or an exit already gave it its closing
            // status. Sending it now would start a turn the user stopped.
            return;
        }
        handshakeTurn = null;
        if (!running || pendingDiff) {
            processing = false; // stop()/diff panel won the race; nobody else will rearm
            return;
        }
        // Client is now established; deliver through the normal turn path. Deliberately
        // sendTurn(), not sendPrompt(): with processing still held true, sendPrompt's own
        // guard would reject the re-entry and silently drop the prompt.
        sendTurn(text);
    }

    synchronized void sendTurn(String text) {
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
        c.sendRequest("turn/start", buildTurnStartParams(tid, text, currentEffortOverride()))
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

    /**
     * Compacts the thread through the shared busy/ready contract. {@code thread/compact/start} answers
     * {@code {}} and the compaction then runs as a turn; that turn's {@code turn/completed} closes the work —
     * READY on success, FAILED otherwise — and none of its turn notifications reach the UI
     * ({@link #consumeCompactionNotification}), only the token-usage update, so the context gauge tracks the
     * shrink.
     *
     * @return false if other non-turn work is already in flight, in which case nothing was reported
     */
    public boolean compact() {
        return compact(DEFAULT_WORK_TIMEOUT_MILLIS);
    }

    /**
     * True between Stop on a turn and that turn's {@code turn/completed}: the UI is already unlocked but the
     * app-server is still unwinding the turn, so a compaction started now would swallow its late
     * notifications.
     */
    synchronized boolean isStoppedTurnWindingDown() {
        return stoppedTurnWindingDown;
    }

    boolean compact(long timeoutMillis) {
        CompactionTurn turn = new CompactionTurn();
        return runWork("Compacting conversation...", false, timeoutMillis,
                () -> startCompaction(turn),
                ignored -> new StatusEvent(StatusEventTypeEnum.READY, "Conversation compacted"),
                error -> new StatusEvent(StatusEventTypeEnum.FAILED, "Compact failed: "
                                                                     + (error.getMessage() != null ? error.getMessage() : error.toString())));
    }

    private CompletableFuture<Void> startCompaction(CompactionTurn turn) {
        CodexJsonRpcClient c;
        String tid;
        synchronized (this) {
            c = client;
            tid = threadId;
            if (!running || c == null || tid == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Codex session is not active"));
            }
            if (processing) {
                return CompletableFuture.failedFuture(new IllegalStateException("a turn is in progress"));
            }
            if (stoppedTurnWindingDown) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Wait for Codex to finish the turn you stopped, then compact again"));
            }
            compaction = turn;
        }
        c.sendRequest("thread/compact/start", buildThreadCompactStartParams(tid))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        endCompaction(turn);
                        turn.done.completeExceptionally(ex);
                    }
                });
        return turn.done;
    }

    private synchronized void endCompaction(CompactionTurn turn) {
        if (compaction == turn) {
            compaction = null;
        }
    }

    /**
     * Decides whether a notification belongs to the live compaction turn and must therefore be kept from the
     * UI. Returns true when it was consumed here. Ownership is by turn id, learned from the first
     * {@code turn/started} after the request: a {@code turn/completed} of some earlier turn (a cancelled one
     * winding down) is not ours and must neither close the compaction nor be swallowed. Token usage and rate
     * limits are never consumed — the gauge must follow the shrink.
     */
    private boolean consumeCompactionNotification(CompactionTurn turn, String method, JsonObject params) {
        String notificationTurnId = notificationTurnId(params);
        if (CodexAppServerHandler.METHOD_TURN_STARTED.equals(method)) {
            if (turn.turnId == null && notificationTurnId != null) {
                turn.turnId = notificationTurnId;
                return true;
            }
            return notificationTurnId != null && notificationTurnId.equals(turn.turnId);
        }
        String ours = turn.turnId;
        if (ours == null || (notificationTurnId != null && !ours.equals(notificationTurnId))) {
            return false;
        }
        switch (method) {
            case CodexAppServerHandler.METHOD_AGENT_MESSAGE_DELTA:
            case CodexAppServerHandler.METHOD_ITEM_STARTED:
                return true;
            case CodexAppServerHandler.METHOD_TURN_COMPLETED:
                finishCompaction(turn, params);
                endCompaction(turn);
                return true;
            default:
                return false;
        }
    }

    private static void finishCompaction(CompactionTurn turn, JsonObject params) {
        String status = CodexAppServerHandler.extractTurnStatus(params);
        if ("failed".equals(status)) {
            turn.done.completeExceptionally(
                    new IllegalStateException(CodexAppServerHandler.buildFailedMessage(params)));
        }
        else if ("interrupted".equals(status)) {
            turn.done.completeExceptionally(new IllegalStateException("the compaction was interrupted"));
        }
        else {
            turn.done.complete(null);
        }
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
            stoppedTurnWindingDown = true;
            handshakeTurn = null; // STOPPED below closes it; a handshake still running must not send it
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
     * GithubCopilotProcessManager}'s {@code session.send(..., "immediate")} approach, not Claude's
     * turn-interrupting one, because Codex's app-server exposes steering as its own method rather than a mode
     * on an in-flight send. Only attempted while a turn is actually in flight and its turn id is known; both
     * a genuinely idle session and a refused steer (e.g. {@code ActiveTurnNotSteerable}) fall back
     * identically to doing nothing further — the message is not lost, it simply arrives later via the normal
     * inbox flush. Never escalates to Cancel: interrupting the user's turn to deliver a notice would be worse
     * than delivering it late.
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
        // A compaction still running when the session stops must not leave the UI locked: its closing
        // FAILED comes from here, never from a turn/completed that will not arrive.
        compaction = null;
        failWorkInFlight("Codex stopped before the compaction finished");

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
        stoppedTurnWindingDown = false;
        cancelledByUser = true;
        interruptRequested = false;
        // Ends the turn without a closing status of its own, deliberately: stop() runs either from start(),
        // whose READY or FAILED then closes it, or when the session closes and nothing is listening. A
        // handshake still running for it must stay silent rather than report a late FAILED.
        handshakeTurn = null;

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

    void onNotification(String method, JsonObject params) {
        CompactionTurn compacting = compaction;
        if (compacting != null && consumeCompactionNotification(compacting, method, params)) {
            return;
        }
        if (CodexAppServerHandler.METHOD_TURN_COMPLETED.equals(method)) {
            synchronized (this) {
                processing = false;
                stoppedTurnWindingDown = false;
            }
        }
        CodexAppServerHandler handler = appServerHandler;
        if (handler != null) {
            handler.onNotification(method, params);
        }
    }

    /**
     * Fires on the reader thread's stream-EOF disconnect signal, which can arrive before or after
     * {@link Process#onExit()} — {@link #handleProcessExit} is the authoritative source for the
     * {@code EXITED} status event and exit code, but this must independently clear
     * {@code client}/{@code appServerHandler} /{@code threadId} too. Without that, a crash detected here but
     * not yet by {@code onExit} leaves {@code client} non-null, so the next {@code sendPrompt} takes the
     * {@code sendTurn} path against a dead connection instead of re-handshaking — the busy-forever bug this
     * fixes. Deliberately does NOT null {@code currentProcess}: that stays {@link #handleProcessExit}'s job
     * so its own staleness guard (`currentProcess != dead`) keeps working.
     */
    void onHandlerDisconnected() {
        boolean suppress;
        CodexJsonRpcClient orphaned;
        synchronized (this) {
            processing = false;
            stoppedTurnWindingDown = false;
            compaction = null;
            orphaned = client;
            client = null;
            appServerHandler = null;
            threadId = null;
            currentTurnId = null;
            interruptRequested = false;
            suppress = cancelledByUser;
            // The stream is gone, so the compaction's turn/completed can never arrive. Closed under the lock
            // handleProcessExit also takes, so the exit sees either this close plus its token, or has already
            // closed the work itself: never a gap in which both report.
            if (failWorkInFlight("Codex disconnected before the compaction finished")) {
                workClosedByDisconnectOf = currentProcess;
            }
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
     * {@link Process#onExit()} callback — the only reliable signal that the subprocess itself died (as
     * opposed to the reader thread merely losing its stream, which {@link #onHandlerDisconnected} handles).
     * Mirrors {@code OpenCodeAiProcessManager.handleProcessExit}: reports {@code EXITED} with the exit code
     * and recent stderr so a crash is visible instead of leaving the session looking READY with no message at
     * all.
     */
    void handleProcessExit(Process dead) {
        boolean suppress;
        boolean closedWork;
        int code;
        boolean reportExit;
        CodexJsonRpcClient orphaned;
        synchronized (this) {
            if (currentProcess != dead) {
                return; // stale exit from a superseded process
            }
            processing = false;
            stoppedTurnWindingDown = false;
            compaction = null;
            currentProcess = null;
            orphaned = client;
            client = null;
            appServerHandler = null;
            threadId = null;
            currentTurnId = null;
            interruptRequested = false;
            suppress = cancelledByUser;
            // After the stale-exit guard so a superseded process's exit cannot fail new work. One closer only:
            // work closed here or by the disconnect (which holds this lock while it closes) already told the UI
            // the session is over, so a second EXITED would be a second closing status.
            code = dead.exitValue();
            closedWork = failWorkInFlight("Codex exited (code " + code + ") during compaction")
                         || workClosedByDisconnectOf == dead;
            workClosedByDisconnectOf = null;
            reportExit = !suppress && code != 0 && !closedWork;
            if (reportExit) {
                handshakeTurn = null; // EXITED below closes it
            }
        }

        // Same leak this method must not reintroduce even if onHandlerDisconnected
        // somehow fires after this (e.g. SIGKILL) — see its javadoc. close() is
        // idempotent, so closing an already-closed client here is a safe no-op.
        if (orphaned != null) {
            orphaned.close();
        }
        if (reportExit) {
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
                        LOG.log(Level.WARNING, "codex stderr: {0}", McpHookServerUtil.redactAllSecrets(line));
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
