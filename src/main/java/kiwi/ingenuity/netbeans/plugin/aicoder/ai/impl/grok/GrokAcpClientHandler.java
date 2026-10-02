package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Maps inbound Grok ACP traffic to plugin events. All methods are called on AcpConnection's dispatcher thread
 * pool, never on the reader thread.
 *
 * <p>
 * {@code session/request_permission} is routed through the shared {@link AbstractAcpClientHandler} bridge
 * into the existing {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent} + diff-panel
 * mechanism, exactly as OpenCode's handler does — Boss's call: a write goes through the diff panel, never
 * silently steered away. {@link #extractPermissionFilePath}/{@link #extractDiffNewText} on the base already
 * understand Grok's {@code rawInput: {variant, file_path, content}} shape alongside OpenCode's.
 *
 * <p>
 * MCP steering IS active for Grok ({@code AiTypeEnum.GROK}'s {@code McpSteeringSupportEnum} is
 * {@code DENY_NEEDS_FOLLOW_UP}, the same reply shape as OpenCode — a bare optionId, no message channel) —
 * read/search/fetch/think/other-kind native calls can be steered away in favour of the plugin's own MCP
 * tools, exactly as OpenCode's native calls can. The bridge must never mistake our OWN tools, reached lazily
 * through Grok's {@code search_tool}/{@code use_tool} meta-tools, for a native call to steer away — see
 * {@code AbstractAcpClientHandler.isVerifiedOurToolWrapper}'s rawInput lookup for that case.
 *
 * <p>
 * Turn usage does not arrive through {@code session/update}: Grok reports it once, in the
 * {@code session/prompt} RPC result's {@code _meta}, so {@link #onUsageUpdate} is a no-op here — see
 * {@code GrokAiProcessManager#sendTurn}.
 */
class GrokAcpClientHandler extends AbstractAcpClientHandler {

    /**
     * ACP tool kinds that only look at something — same vocabulary as OpenCode's equivalent set. Grok's
     * {@code external_directory}-style ask is unverified against a live probe for its exact kind string;
     * {@code "other"} is kept so an unrecognised kind still falls through to the same not-a-write path rather
     * than being guessed at.
     */
    private static final Set<String> ACCESS_KINDS = Set.of("read", "search", "fetch", "think", "other");

    private final BiConsumer<String, String> toolCallTracker;

    GrokAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback) {
        this(listener, disconnectCallback, null, null, null, null);
    }

    /**
     * @param toolCallTracker       receives {@code (toolCallId, status)} for every {@code tool_call}/
     *                              {@code tool_call_update} session/update. May be null (e.g. tests wiring a
     *                              handler to a bare connection).
     * @param ownSessionConfigFile  true for a path inside this plugin session's own
     *                              {@code ~/.ai-coder/{type}/{sessionId}/} tree — see
     *                              {@link AbstractAcpClientHandler#ownSessionConfigFileCheck}.
     * @param steeringIsActiveCheck optional check for whether MCP steering is active for a session. If null,
     *                              resolved via {@code SessionRegistry} at runtime, under
     *                              {@code pluginSessionId}.
     * @param pluginSessionId       the PLUGIN session UUID this session is registered under in
     *                              {@code SessionRegistry} — never Grok's own ACP session id.
     */
    GrokAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
                         BiConsumer<String, String> toolCallTracker, Predicate<String> ownSessionConfigFile,
                         Predicate<String> steeringIsActiveCheck, String pluginSessionId) {
        super(listener, disconnectCallback, Logger.getLogger(GrokAcpClientHandler.class.getName()),
                ownSessionConfigFile, steeringIsActiveCheck, pluginSessionId);
        this.toolCallTracker = toolCallTracker;
    }

    @Override
    protected String backendSteeringLogName() {
        return "grok";
    }

    @Override
    protected String backendDisplayName() {
        return "Grok";
    }

    @Override
    protected void onAgentMessageChunk(JsonObject update) {
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
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.THINKING, ""));
    }

    @Override
    protected void onToolEvent(JsonObject update) {
        String toolName = update.has(AcpJsonKeyEnum.TITLE.key()) ? update.get(AcpJsonKeyEnum.TITLE.key()).getAsString() : "";
        String kind = update.has(AcpJsonKeyEnum.KIND.key()) ? update.get(AcpJsonKeyEnum.KIND.key()).getAsString() : "";
        String filePath = extractFirstLocationPath(update);
        listener.onAiProcessEvent(new ToolUseEvent(toolName, filePath, null, null, mapToolKind(kind)));
        String toolCallId = stringOrNull(update, AcpJsonKeyEnum.TOOL_CALL_ID.key());
        String status = stringOrNull(update, AcpJsonKeyEnum.STATUS.key());
        rememberToolKind(toolCallId, kind, status);
        if (toolCallTracker != null && toolCallId != null) {
            toolCallTracker.accept(toolCallId, status);
        }
    }

    @Override
    protected void onUsageUpdate(JsonObject update) {
        // No-op: Grok reports usage once, in the session/prompt result's _meta, not via session/update.
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }

    @Override
    public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
        return handlePermissionRequest(params, ACCESS_KINDS);
    }
}
