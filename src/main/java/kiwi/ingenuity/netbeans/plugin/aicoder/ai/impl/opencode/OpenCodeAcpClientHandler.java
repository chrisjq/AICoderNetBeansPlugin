package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Maps inbound OpenCode ACP traffic to plugin events. All methods are called on AcpConnection's dispatcher
 * thread pool, never on the reader thread.
 *
 * <p>
 * {@code session/request_permission} is routed through the shared {@link AbstractAcpClientHandler} bridge
 * into the existing {@link PermissionEvent} + diff-panel mechanism. This class supplies only what is
 * OpenCode-specific: the "/compact" text-suppression window and the per-update-kind event mapping.
 */
class OpenCodeAcpClientHandler extends AbstractAcpClientHandler {

    /**
     * ACP tool kinds that only look at something, so a request that names a path under one of them is a
     * request to ACCESS that path, not to write it. {@code other} is in this set on purpose: OpenCode's
     * {@code external_directory} permission — the one behind "the AI wants to read a file outside the
     * project" — is sent as {@code kind:"other"}, not {@code "read"} (verified against the OpenCode ACP
     * agent, whose kind mapping falls through to {@code "other"} for any permission name it does not list). A
     * request whose kind is something else entirely, or absent, keeps the pre-existing behaviour rather than
     * being guessed at.
     */
    private static final Set<String> ACCESS_KINDS = Set.of("read", "search", "fetch", "think", "other");

    public static String extractFirstLocationPath(JsonObject update) {
        return AbstractAcpClientHandler.extractFirstLocationPath(update);
    }

    public static ToolUseEvent.Kind mapToolKind(String kind) {
        return AbstractAcpClientHandler.mapToolKind(kind);
    }

    /**
     * Extracts the target file path from a {@code session/request_permission} {@code toolCall} object.
     * Priority: {@code content[0].path} → {@code locations[0].path} → {@code rawInput.filepath}.
     */
    public static String extractPermissionFilePath(JsonObject toolCall) {
        return AbstractAcpClientHandler.extractPermissionFilePath(toolCall);
    }

    /**
     * Extracts {@code rawInput.command} from a {@code toolCall} — the shell command text for an
     * {@code execute}-kind permission request (live-probed shape: {@code
     * rawInput:{"command":"echo hi"}}, empty {@code locations}, no {@code content}).
     */
    public static String extractRawInputCommand(JsonObject toolCall) {
        return AbstractAcpClientHandler.extractRawInputCommand(toolCall);
    }

    /**
     * Extracts {@code content[0].newText} when {@code content[0].type == "diff"} — the full proposed file
     * content ACP sends for an edit permission request. Returns null for any other shape (non-diff content,
     * missing content, etc.).
     */
    public static String extractDiffNewText(JsonObject toolCall) {
        return AbstractAcpClientHandler.extractDiffNewText(toolCall);
    }

    public static List<String> extractAllPermissionPaths(JsonObject toolCall) {
        return AbstractAcpClientHandler.extractAllPermissionPaths(toolCall);
    }

    /**
     * True only when the request is positively a change: a mutating kind, or a diff proposed in
     * {@code content} or {@code rawInput.diff} whatever the kind claims. Absence of a diff is NOT treated as
     * evidence of a read — that is decided by kind — so an unrecognised kind keeps the existing behaviour
     * instead of being reclassified on a guess.
     */
    public static boolean isMutationRequest(String kind, JsonObject toolCall) {
        return AbstractAcpClientHandler.isMutationRequest(kind, toolCall);
    }

    /**
     * Name shown to the user for a request to look at a path: the verb that matches the kind, never "Write". {@code
     * other} — where an {@code external_directory} ask lands — is "Access", because the request says the AI
     * wants to reach outside the project, not which tool it will use once it can.
     */
    public static String accessToolName(String kind) {
        return AbstractAcpClientHandler.accessToolName(kind);
    }

    /**
     * What the confirm prompt says is being approved. A search names its pattern (OpenCode puts it in
     * {@code title}) as well as where it will look; everything else is just the path.
     */
    public static String accessDisplayText(String kind, String title, String path) {
        return AbstractAcpClientHandler.accessDisplayText(kind, title, path);
    }

    private final BiConsumer<String, String> toolCallTracker;

    /**
     * Non-zero while the process manager is running a non-turn {@code session/prompt} whose streamed-back
     * text is not part of the conversation (the compaction route — OpenCode answers a {@code "/compact"}
     * prompt by streaming the summary back as ordinary agent text, which must never leak into the
     * transcript). While set, {@code agent_message_chunk}, {@code agent_thought_chunk}, {@code tool_call} and
     * {@code tool_call_update} updates are dropped instead of surfacing as
     * {@link TextDeltaEvent}s/THINKING/ToolUseEvents, so the summary cannot leak into the transcript;
     * {@code usage_update} still flows so the context gauge tracks the shrink. Toggled by the process manager
     * around the compaction prompt.
     *
     * <p>
     * Suppression is owned by a token rather than a plain boolean so a stale compaction's late response can
     * never clear a newer compaction's suppression: {@link #beginTextSuppression} arms a fresh token and
     * {@link #endTextSuppression} clears only while it still matches.
     */
    private volatile long suppressionToken;

    private final AtomicLong suppressionTokens = new AtomicLong();

    /**
     * Arms suppression for one compaction and returns its ownership token. Tokens come from a counter, never
     * {@code nanoTime}, so two compactions can never receive the same token.
     */
    long beginTextSuppression() {
        long token = suppressionTokens.incrementAndGet();
        this.suppressionToken = token;
        return token;
    }

    /**
     * Disarms suppression owned by {@code token}. A stale token (a newer compaction already re-armed it)
     * leaves the newer suppression untouched.
     */
    void endTextSuppression(long token) {
        if (suppressionToken == token) {
            suppressionToken = 0;
        }
    }

    /**
     * Disarms suppression unconditionally — the safety net {@code sendTurn} uses so a stalled or abandoned
     * compaction can never silence a real turn.
     */
    void clearTextSuppression() {
        suppressionToken = 0;
    }

    boolean isSuppressingSessionText() {
        return suppressionToken != 0;
    }

    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback) {
        this(listener, disconnectCallback, null, null, null);
    }

    /**
     * @param toolCallTracker receives {@code (toolCallId, status)} for every {@code tool_call} and
     *                        {@code tool_call_update} session/update so the process manager can track
     *                        in-flight tool calls (for the Mail interrupt hold) without reaching into the
     *                        handler's internals. May be null when the caller is not a manager (e.g. tests
     *                        wiring a handler to a bare connection).
     */
    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
                             BiConsumer<String, String> toolCallTracker) {
        this(listener, disconnectCallback, toolCallTracker, null, null);
    }

    /**
     * @param ownSessionConfigFile true for a path inside this plugin session's own
     *                             {@code ~/.ai-coder/{type}/{sessionId}/} tree — see
     *                             {@link AbstractAcpClientHandler#ownSessionConfigFileCheck}. A permission
     *                             request whose every path passes is answered "allow once" without asking the
     *                             user, exactly as the plugin's own file tools and the Claude/Pi hook treat
     *                             that tree. Null means "nothing is exempt": every path-bearing request is
     *                             put to the user, as before this parameter existed.
     */
    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
                             BiConsumer<String, String> toolCallTracker, Predicate<String> ownSessionConfigFile) {
        this(listener, disconnectCallback, toolCallTracker, ownSessionConfigFile, null);
    }

    /**
     * @param ownSessionConfigFile  true for a path inside this plugin session's own
     *                              {@code ~/.ai-coder/{type}/{sessionId}/} tree
     * @param steeringIsActiveCheck optional check for whether MCP steering is active for a session (by
     *                              sessionId). If null, steering is determined via {@link SessionRegistry} at
     *                              runtime, under the plugin session id passed as {@code pluginSessionId}.
     */
    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
                             BiConsumer<String, String> toolCallTracker, Predicate<String> ownSessionConfigFile,
                             Predicate<String> steeringIsActiveCheck) {
        this(listener, disconnectCallback, toolCallTracker, ownSessionConfigFile, steeringIsActiveCheck, null);
    }

    /**
     * @param ownSessionConfigFile  true for a path inside this plugin session's own
     *                              {@code ~/.ai-coder/{type}/{sessionId}/} tree
     * @param steeringIsActiveCheck optional check for whether MCP steering is active for a session (by
     *                              sessionId). If null, steering is determined via {@link SessionRegistry} at
     *                              runtime, under {@code pluginSessionId}.
     * @param pluginSessionId       the PLUGIN session UUID the plugin session is registered under in
     *                              {@link SessionRegistry} — see the base class field. Null for callers with
     *                              no plugin session (older callers, plain tests); steering then resolves
     *                              through {@code steeringIsActiveCheck} if given, else off.
     */
    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
                             BiConsumer<String, String> toolCallTracker, Predicate<String> ownSessionConfigFile,
                             Predicate<String> steeringIsActiveCheck, String pluginSessionId) {
        super(listener, disconnectCallback, Logger.getLogger(OpenCodeAcpClientHandler.class.getName()),
                ownSessionConfigFile, steeringIsActiveCheck, pluginSessionId);
        this.toolCallTracker = toolCallTracker;
    }

    @Override
    protected String backendSteeringLogName() {
        return "opencode";
    }

    @Override
    protected String backendDisplayName() {
        return "OpenCode";
    }

    @Override
    protected void onAgentMessageChunk(JsonObject update) {
        if (isSuppressingSessionText()) {
            return;
        }
        String messageId = update.has(AcpJsonKeyEnum.MESSAGE_ID.key()) ? update.get(AcpJsonKeyEnum.MESSAGE_ID.key()).getAsString() : null;
        String text = "";
        if (update.has(AcpJsonKeyEnum.CONTENT.key()) && update.get(AcpJsonKeyEnum.CONTENT.key()).isJsonObject()) {
            JsonObject content = update.getAsJsonObject(AcpJsonKeyEnum.CONTENT.key());
            if (content.has(AcpJsonKeyEnum.TEXT.key())) {
                text = content.get(AcpJsonKeyEnum.TEXT.key()).getAsString();
            }
        }
        listener.onAiProcessEvent(new TextDeltaEvent(text, messageId));
    }

    @Override
    protected void onAgentThoughtChunk(JsonObject update) {
        if (!isSuppressingSessionText()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.THINKING, ""));
        }
    }

    @Override
    protected void onToolEvent(JsonObject update) {
        if (isSuppressingSessionText()) {
            // during a compaction the agent's own tool visibility is no part of the conversation either
            return;
        }
        String toolName = update.has(AcpJsonKeyEnum.TITLE.key()) ? update.get(AcpJsonKeyEnum.TITLE.key()).getAsString() : "";
        String kind = update.has(AcpJsonKeyEnum.KIND.key()) ? update.get(AcpJsonKeyEnum.KIND.key()).getAsString() : "";
        String filePath = extractFirstLocationPath(update);
        listener.onAiProcessEvent(new ToolUseEvent(toolName, filePath, null, null, mapToolKind(kind)));
        String toolCallId = stringOrNull(update, AcpJsonKeyEnum.TOOL_CALL_ID.key());
        String status = stringOrNull(update, AcpJsonKeyEnum.STATUS.key());
        rememberToolKind(toolCallId, kind, status);
        // Forward the lifecycle signal so the manager can hold a Mail interrupt while a call
        // is in flight. The status is null only when the field is absent, in which case the
        // manager treats the update as not changing the count.
        if (toolCallTracker != null && toolCallId != null) {
            toolCallTracker.accept(toolCallId, status);
        }
    }

    @Override
    protected void onUsageUpdate(JsonObject update) {
        int used = update.has(AcpJsonKeyEnum.USED.key()) ? update.get(AcpJsonKeyEnum.USED.key()).getAsInt() : 0;
        int size = update.has(AcpJsonKeyEnum.SIZE.key()) ? update.get(AcpJsonKeyEnum.SIZE.key()).getAsInt() : 0;
        listener.onAiProcessEvent(new OpenCodeUsageEvent(used, size));
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }

    @Override
    public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
        return handlePermissionRequest(params, ACCESS_KINDS);
    }
}
