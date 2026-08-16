package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpSessionUpdateEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Maps inbound ACP traffic to plugin events. All methods are called on
 * AcpConnection's dispatcher thread pool, never on the reader thread.
 *
 * <p>
 * {@code session/request_permission} is routed through the existing
 * {@link PermissionEvent} + diff-panel mechanism. We reply with the chosen
 * optionId once the user accepts or rejects in the panel.
 */
class OpenCodeAcpClientHandler implements AcpClientHandler {

    private static final Logger LOG = Logger.getLogger(OpenCodeAcpClientHandler.class.getName());

    static String extractFirstLocationPath(JsonObject update) {
        if (!update.has("locations") || !update.get("locations").isJsonArray()) {
            return null;
        }
        JsonArray locations = update.getAsJsonArray("locations");
        if (locations.size() == 0 || !locations.get(0).isJsonObject()) {
            return null;
        }
        JsonObject loc = locations.get(0).getAsJsonObject();
        return loc.has("path") ? loc.get("path").getAsString() : null;
    }

    static ToolUseEvent.Kind mapToolKind(String kind) {
        if ("write".equals(kind)) {
            return ToolUseEvent.Kind.WRITE;
        }
        if ("edit".equals(kind) || "patch".equals(kind)) {
            return ToolUseEvent.Kind.EDIT;
        }
        return ToolUseEvent.Kind.OTHER;
    }

    /**
     * Extracts the target file path from a {@code session/request_permission}
     * {@code toolCall} object. Priority: {@code content[0].path} →
     * {@code locations[0].path} → {@code rawInput.filepath}.
     */
    static String extractPermissionFilePath(JsonObject toolCall) {
        if (toolCall == null) {
            return null;
        }
        // 1. content[0].path
        if (toolCall.has("content") && toolCall.get("content").isJsonArray()) {
            JsonArray content = toolCall.getAsJsonArray("content");
            if (content.size() > 0 && content.get(0).isJsonObject()) {
                JsonObject c0 = content.get(0).getAsJsonObject();
                if (c0.has("path") && !c0.get("path").isJsonNull()) {
                    return c0.get("path").getAsString();
                }
            }
        }
        // 2. locations[0].path
        String locPath = extractFirstLocationPath(toolCall);
        if (locPath != null) {
            return locPath;
        }
        // 3. rawInput.filepath
        if (toolCall.has("rawInput") && toolCall.get("rawInput").isJsonObject()) {
            JsonObject rawInput = toolCall.getAsJsonObject("rawInput");
            if (rawInput.has("filepath") && !rawInput.get("filepath").isJsonNull()) {
                return rawInput.get("filepath").getAsString();
            }
        }
        return null;
    }

    private final AiProcessEventListener listener;
    private final Runnable disconnectCallback;
    private volatile CompletableFuture<PermissionDecision> pendingPermission = null;

    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback) {
        this.listener = listener;
        this.disconnectCallback = disconnectCallback;
    }

    @Override
    public void onSessionUpdate(String sessionId, JsonObject update) {
        String raw = update.has("sessionUpdate") ? update.get("sessionUpdate").getAsString() : null;
        AcpSessionUpdateEnum type = AcpSessionUpdateEnum.fromWire(raw);
        if (type == null) {
            return; // Unknown sessionUpdate value — ignore silently, never throw
        }
        switch (type) {
            case AGENT_MESSAGE_CHUNK:
                handleAgentMessageChunk(update);
                break;
            case AGENT_THOUGHT_CHUNK:
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.THINKING, ""));
                break;
            case TOOL_CALL:
            case TOOL_CALL_UPDATE:
                // locations and rawInput may be empty on the initial tool_call (§12)
                handleToolEvent(update);
                break;
            case USAGE_UPDATE:
                handleUsageUpdate(update);
                break;
            default:
                break;
        }
    }

    private void handleUsageUpdate(JsonObject update) {
        int used = update.has("used") ? update.get("used").getAsInt() : 0;
        int size = update.has("size") ? update.get("size").getAsInt() : 0;
        listener.onAiProcessEvent(new OpenCodeUsageEvent(used, size));
    }

    private void handleAgentMessageChunk(JsonObject update) {
        String messageId = update.has("messageId") ? update.get("messageId").getAsString() : null;
        String text = "";
        if (update.has("content") && update.get("content").isJsonObject()) {
            JsonObject content = update.getAsJsonObject("content");
            if (content.has("text")) {
                text = content.get("text").getAsString();
            }
        }
        listener.onAiProcessEvent(new TextDeltaEvent(text, messageId));
    }

    private void handleToolEvent(JsonObject update) {
        String toolName = update.has("title") ? update.get("title").getAsString() : "";
        String kind = update.has("kind") ? update.get("kind").getAsString() : "";
        String filePath = extractFirstLocationPath(update);
        listener.onAiProcessEvent(new ToolUseEvent(toolName, filePath, null, null, mapToolKind(kind)));
    }

    @Override
    public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
        JsonObject toolCall = params.has("toolCall") && params.get("toolCall").isJsonObject()
                ? params.getAsJsonObject("toolCall") : null;

        // Extract target file path: content[0].path → locations[0].path → rawInput.filepath
        String filePath = extractPermissionFilePath(toolCall);

        // ACP sends full file contents in newText — route through "Write", not "Edit".
        // PermissionDiffPolicy.decide("Edit",...) requires an exact oldString substring match,
        // which breaks for full-file diffs and fails outright when oldText is null (new files).
        String newText = null;
        if (toolCall != null && toolCall.has("content") && toolCall.get("content").isJsonArray()) {
            JsonArray content = toolCall.getAsJsonArray("content");
            if (content.size() > 0 && content.get(0).isJsonObject()) {
                JsonObject c0 = content.get(0).getAsJsonObject();
                if ("diff".equals(c0.has("type") ? c0.get("type").getAsString() : null)) {
                    newText = c0.has("newText") ? c0.get("newText").getAsString() : null;
                }
            }
        }

        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new PermissionEvent("Write", filePath, null, null, newText, decisionFuture));

        // Map the PermissionDecision back to the ACP wire response.
        //
        // NOTE: We always reply "once" for an allow, never "always". The diff panel's
        // auto-accept path calls PermissionDecision.allowed() directly, so we cannot
        // distinguish it from an explicit user click. Replying "always" would configure
        // OpenCode to skip future permission requests permanently — a side-effect the
        // user did not request from the auto-accept toggle.
        //
        // NOTE: Unlike the Claude/MCP path (which applies the edit itself and then sends
        // "deny" to prevent a double-write), here answering "once" lets OpenCode perform
        // the write. We MUST NOT apply the edit ourselves — doing so would double-apply it.
        return decisionFuture.handle((decision, ex) -> {
            pendingPermission = null;
            JsonObject result = new JsonObject();
            if (ex != null || decision == null) {
                // Turn was cancelled while the permission dialog was open
                JsonObject outcome = new JsonObject();
                outcome.addProperty("outcome", "cancelled");
                result.add("outcome", outcome);
            }
            else {
                JsonObject outcome = new JsonObject();
                outcome.addProperty("outcome", "selected");
                outcome.addProperty("optionId", decision.allow() ? "once" : "reject");
                result.add("outcome", outcome);
            }
            return result;
        });
    }

    /**
     * Cancels any in-flight permission dialog by completing its future
     * exceptionally. Called by the process manager on turn cancel and stop. The
     * {@code handle} in {@link #onRequestPermission} maps this to
     * {@code {"outcome":{"outcome":"cancelled"}}} back to OpenCode.
     */
    void cancelPendingPermissions() {
        CompletableFuture<PermissionDecision> pf = pendingPermission;
        if (pf != null) {
            pendingPermission = null;
            pf.completeExceptionally(new CancellationException("turn cancelled"));
        }
    }

    @Override
    public CompletableFuture<JsonObject> onWriteTextFile(JsonObject params) {
        // Slice 4: route through diff panel.
        return CompletableFuture.completedFuture(new JsonObject());
    }

    @Override
    public CompletableFuture<JsonObject> onReadTextFile(JsonObject params) {
        // Slice 4: serve from IDE.
        return CompletableFuture.completedFuture(new JsonObject());
    }

    @Override
    public void onDisconnected(Exception cause) {
        disconnectCallback.run();
    }
}
