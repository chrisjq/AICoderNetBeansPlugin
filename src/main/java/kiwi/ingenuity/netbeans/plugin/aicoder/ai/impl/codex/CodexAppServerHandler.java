package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexRateLimitEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Maps inbound {@code app-server} traffic to plugin events (design doc §8) and
 * bridges its two approval-request kinds to the plugin's existing
 * {@link ConfirmEvent} confirm flow (§0a "Working flow for the permission
 * bridge"). Combined in one class, like {@code OpenCodeAcpClientHandler},
 * rather than split into the design doc §6 sketch of separate
 * {@code CodexStreamParser}/{@code CodexPermissionBridge} classes: the
 * fileChange approval request carries no diff of its own and depends on the
 * {@code changes[]} cached from an earlier {@code item/started} notification
 * for the same item id, so the two concerns share state and splitting them
 * would only mean passing that cache between two objects.
 *
 * <p>
 * {@link #onNotification} and {@link #onServerRequest} are invoked on
 * {@link CodexJsonRpcClient}'s notify/dispatch executors — never the reader
 * thread, never the EDT.
 */
class CodexAppServerHandler implements CodexNotificationListener, CodexServerRequestHandler, CodexConnectionListener {

    private static final Logger LOG = Logger.getLogger(CodexAppServerHandler.class.getName());

    static final String METHOD_AGENT_MESSAGE_DELTA = "item/agentMessage/delta";
    static final String METHOD_ITEM_STARTED = "item/started";
    static final String METHOD_TURN_STARTED = "turn/started";
    static final String METHOD_TURN_COMPLETED = "turn/completed";
    static final String METHOD_COMMAND_EXECUTION_APPROVAL = "item/commandExecution/requestApproval";
    static final String METHOD_FILE_CHANGE_APPROVAL = "item/fileChange/requestApproval";
    static final String METHOD_MCP_ELICITATION = "mcpServer/elicitation/request";
    static final String METHOD_THREAD_TOKEN_USAGE = "thread/tokenUsage/updated";
    static final String METHOD_ACCOUNT_RATE_LIMITS_UPDATED = "account/rateLimits/updated";

    /**
     * Extracts {@code item.changes} from an {@code item/started} notification
     * when the item is a fileChange, for caching by item id. Returns null for
     * any other item type or malformed payload.
     */
    static JsonArray extractFileChangeChanges(JsonObject params) {
        if (params == null || !params.has("item") || !params.get("item").isJsonObject()) {
            return null;
        }
        JsonObject item = params.getAsJsonObject("item");
        String type = item.has("type") && !item.get("type").isJsonNull() ? item.get("type").getAsString() : null;
        if (!"fileChange".equals(type)) {
            return null;
        }
        return item.has("changes") && item.get("changes").isJsonArray() ? item.getAsJsonArray("changes") : null;
    }

    static String extractItemId(JsonObject params) {
        if (params == null || !params.has("item") || !params.get("item").isJsonObject()) {
            return null;
        }
        JsonObject item = params.getAsJsonObject("item");
        return item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : null;
    }

    /**
     * {@code turn/started}/{@code turn/completed} both carry
     * {@code turn.status} (design doc:
     * {@code TurnStartedNotification}/{@code TurnCompletedNotification}
     * schemas). Returns null on any unexpected shape.
     */
    static String extractTurnStatus(JsonObject params) {
        if (params == null || !params.has("turn") || !params.get("turn").isJsonObject()) {
            return null;
        }
        JsonObject turn = params.getAsJsonObject("turn");
        return turn.has("status") && !turn.get("status").isJsonNull() ? turn.get("status").getAsString() : null;
    }

    static String extractTurnErrorMessage(JsonObject params) {
        if (params == null || !params.has("turn") || !params.get("turn").isJsonObject()) {
            return null;
        }
        JsonObject turn = params.getAsJsonObject("turn");
        if (!turn.has("error") || !turn.get("error").isJsonObject()) {
            return null;
        }
        JsonObject error = turn.getAsJsonObject("error");
        return error.has("message") && !error.get("message").isJsonNull() ? error.get("message").getAsString() : null;
    }

    /**
     * Extracts the {@code codexErrorInfo} discriminant from a turn error, if
     * present and a plain string (e.g.
     * {@code "unauthorized"}, {@code "contextWindowExceeded"}). Returns null
     * when absent, null in JSON, or a structured variant (object shape).
     */
    static String extractTurnCodexErrorInfo(JsonObject params) {
        if (params == null || !params.has("turn") || !params.get("turn").isJsonObject()) {
            return null;
        }
        JsonObject turn = params.getAsJsonObject("turn");
        if (!turn.has("error") || !turn.get("error").isJsonObject()) {
            return null;
        }
        JsonObject error = turn.getAsJsonObject("error");
        if (!error.has("codexErrorInfo") || !error.get("codexErrorInfo").isJsonPrimitive()) {
            return null;
        }
        return error.get("codexErrorInfo").getAsString();
    }

    /**
     * Builds a short, single-line summary of a cached fileChange's {@code
     * changes[]} for {@link ConfirmEvent#displayText()} — not the raw unified
     * diff. {@code ConfirmPanel} renders {@code displayText} as one bold HTML
     * line (design doc out of scope: no diff-panel rendering this slice), so a
     * multi-line diff dump would collapse unreadably rather than display as
     * intended.
     */
    static String summarizeFileChanges(JsonArray changes) {
        if (changes == null || changes.size() == 0) {
            return "Codex wants to modify a file";
        }
        if (changes.size() == 1 && changes.get(0).isJsonObject()) {
            JsonObject c = changes.get(0).getAsJsonObject();
            String path = c.has("path") && !c.get("path").isJsonNull() ? c.get("path").getAsString() : "a file";
            return "Codex wants to modify " + path;
        }
        return "Codex wants to modify " + changes.size() + " files";
    }

    static String firstChangedPath(JsonArray changes) {
        if (changes == null || changes.size() == 0 || !changes.get(0).isJsonObject()) {
            return null;
        }
        JsonObject c = changes.get(0).getAsJsonObject();
        return c.has("path") && !c.get("path").isJsonNull() ? c.get("path").getAsString() : null;
    }

    /**
     * {@code codexErrorInfo == "unauthorized"} is the schema-documented
     * plain-string variant, but a live probe against a real 401 (missing bearer
     * token on the Responses websocket) showed {@code codexErrorInfo} collapse
     * to the generic string {@code "other"} by the time {@code turn/completed}
     * fires — the only reliable signal left at that point is the
     * {@code error.message} text itself ("unexpected status 401 Unauthorized:
     * ..."). Check both: the schema path in case some other auth failure
     * genuinely reports it, and the message-text path for the one this project
     * has actually observed.
     */
    private static String buildFailedMessage(JsonObject params) {
        String codexErrorInfo = extractTurnCodexErrorInfo(params);
        String message = extractTurnErrorMessage(params);
        boolean isAuthFailure = "unauthorized".equals(codexErrorInfo)
                || (message != null && message.contains("401 Unauthorized"));
        if (isAuthFailure) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "codex turn failed: mapped to authentication error (codexErrorInfo={0}, message={1})",
                        new Object[]{codexErrorInfo, message});
            }
            return "Authentication required. Run 'codex login' in a terminal, then retry.";
        }
        return message != null ? message : "Turn failed";
    }

    /**
     * Maps a completed {@link PermissionDecision} future to a Codex approval
     * string. Used by both the {@code decision}-field methods (file-change,
     * command) and the {@code action}-field method (MCP elicitation); the
     * string value is identical.
     *
     * <ul>
     * <li>{@code "accept"} — user approved
     * <li>{@code "decline"} — user deliberately rejected; agent continues the
     * turn
     * <li>{@code "cancel"} — exceptional completion (panel closed, process
     * interrupted); agent interrupts the turn immediately
     * </ul>
     */
    private static String approvalDecision(PermissionDecision decision, Throwable ex) {
        if (ex != null) {
            return "cancel";
        }
        return decision != null && decision.allow() ? "accept" : "decline";
    }

    /**
     * Applies a Codex unified-diff hunk (the {@code diff} field from an
     * {@code item/started} {@code changes[]} entry) to {@code original} file
     * content. The hunk uses standard unified-diff format but carries no
     * {@code ---}/{@code +++} header lines — those are prepended here so that
     * {@link UnifiedDiffUtils#parseUnifiedDiff} can locate hunk boundaries.
     *
     * @throws PatchFailedException if the hunk does not match the file content
     * (stale read, CRLF vs LF, whitespace mismatch); callers fall back to the
     * blind {@link ConfirmEvent} path
     */
    static String applyUnifiedDiff(String original, String diffHunk) throws PatchFailedException {
        List<String> diffLines = Arrays.asList(
                ("--- original\n+++ revised\n" + diffHunk).split("\n", -1));
        Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);
        List<String> originalLines = Arrays.asList(original.split("\n", -1));
        List<String> patchedLines = DiffUtils.patch(originalLines, patch);
        return String.join("\n", patchedLines);
    }

    private final AiProcessEventListener listener;
    private final Runnable disconnectCallback;
    /**
     * item id -> changes[] cached from item/started, consumed by the matching
     * approval request.
     */
    private final ConcurrentHashMap<String, JsonArray> fileChangeCache = new ConcurrentHashMap<>();
    /**
     * Outstanding approval decision future — at most one per turn (Codex
     * approvals are sequential: each blocks the turn until answered). Written
     * by the three raise* methods; read by {@link #cancelPendingPermissions()}
     * on stop/interrupt.
     */
    private volatile CompletableFuture<PermissionDecision> pendingPermission;

    CodexAppServerHandler(AiProcessEventListener listener, Runnable disconnectCallback) {
        this.listener = listener;
        this.disconnectCallback = disconnectCallback;
    }

    /**
     * Cache cleared at the start of each turn — item ids are turn-scoped.
     */
    void onTurnStarting() {
        fileChangeCache.clear();
    }

    /**
     * Cancels any outstanding approval dialog when the turn is stopped or
     * interrupted. Completes the pending future exceptionally, which routes
     * through the existing {@link #approvalDecision} path and replies
     * {@code "cancel"} to Codex — immediately interrupting the turn rather than
     * leaving the dialog up and the turn wedged. Safe to call when no approval
     * is in flight.
     */
    void cancelPendingPermissions() {
        CompletableFuture<PermissionDecision> pf = pendingPermission;
        if (pf != null) {
            pendingPermission = null;
            pf.completeExceptionally(new CancellationException("turn cancelled"));
        }
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "codex notification: {0} {1}", new Object[]{method, params});
        }
        switch (method) {
            case METHOD_AGENT_MESSAGE_DELTA:
                handleAgentMessageDelta(params);
                break;
            case METHOD_ITEM_STARTED:
                handleItemStarted(params);
                break;
            case METHOD_TURN_STARTED:
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.THINKING, ""));
                break;
            case METHOD_TURN_COMPLETED:
                handleTurnCompleted(params);
                break;
            case METHOD_THREAD_TOKEN_USAGE:
                handleTokenUsageUpdated(params);
                break;
            case METHOD_ACCOUNT_RATE_LIMITS_UPDATED:
                handleRateLimitsUpdated(params);
                break;
            default:
                break; // Unrecognised notification — ignore silently, never throw.
        }
    }

    private void handleAgentMessageDelta(JsonObject params) {
        String delta = params.has("delta") && !params.get("delta").isJsonNull() ? params.get("delta").getAsString() : "";
        String turnId = params.has("turnId") && !params.get("turnId").isJsonNull() ? params.get("turnId").getAsString() : null;
        listener.onAiProcessEvent(new TextDeltaEvent(delta, turnId));
    }

    private void handleItemStarted(JsonObject params) {
        JsonArray changes = extractFileChangeChanges(params);
        if (changes == null) {
            return;
        }
        String itemId = extractItemId(params);
        if (itemId != null) {
            fileChangeCache.put(itemId, changes);
        }
    }

    private void handleTokenUsageUpdated(JsonObject params) {
        if (params == null || !params.has("tokenUsage") || !params.get("tokenUsage").isJsonObject()) {
            return;
        }
        JsonObject tokenUsage = params.getAsJsonObject("tokenUsage");
        long contextWindow = 0L;
        if (tokenUsage.has("modelContextWindow") && !tokenUsage.get("modelContextWindow").isJsonNull()) {
            contextWindow = tokenUsage.get("modelContextWindow").getAsLong();
        }
        if (!tokenUsage.has("last") || !tokenUsage.get("last").isJsonObject()) {
            return;
        }
        // `total` is the thread's lifetime token spend, which can exceed the
        // model context window many times over. `last` is the active turn's
        // context usage and is therefore comparable with modelContextWindow.
        JsonObject last = tokenUsage.getAsJsonObject("last");
        long usedTokens = last.has("totalTokens") && !last.get("totalTokens").isJsonNull()
                ? last.get("totalTokens").getAsLong() : 0L;
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "codex tokenUsage: used={0} contextWindow={1}",
                    new Object[]{usedTokens, contextWindow});
        }
        listener.onAiProcessEvent(new CodexTokenUsageEvent(usedTokens, contextWindow));
    }

    private void handleRateLimitsUpdated(JsonObject params) {
        if (params == null || !params.has("rateLimits") || !params.get("rateLimits").isJsonObject()) {
            return;
        }
        JsonObject rateLimits = params.getAsJsonObject("rateLimits");
        if (!rateLimits.has("primary") || !rateLimits.get("primary").isJsonObject()) {
            return;
        }
        JsonObject primary = rateLimits.getAsJsonObject("primary");
        if (!primary.has("usedPercent") || primary.get("usedPercent").isJsonNull()) {
            return;
        }
        double usedPercent = primary.get("usedPercent").getAsDouble();
        long windowDurationMins = primary.has("windowDurationMins") && !primary.get("windowDurationMins").isJsonNull()
                ? primary.get("windowDurationMins").getAsLong() : 0L;
        long resetsAt = primary.has("resetsAt") && !primary.get("resetsAt").isJsonNull()
                ? primary.get("resetsAt").getAsLong() : 0L;
        CodexRateLimitEvent event = new CodexRateLimitEvent(usedPercent, windowDurationMins, resetsAt);
        listener.onAiProcessEvent(event);
        CodexAiImplementation.publishRateLimit(event);
    }

    private void handleTurnCompleted(JsonObject params) {
        String status = extractTurnStatus(params);
        if ("failed".equals(status)) {
            String message = buildFailedMessage(params);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, message));
            return;
        }
        listener.onAiProcessEvent(new TurnCompleteEvent());
    }

    @Override
    public CompletableFuture<JsonObject> onServerRequest(String method, JsonObject params) {
        switch (method) {
            case METHOD_COMMAND_EXECUTION_APPROVAL:
                return handleCommandExecutionApproval(params);
            case METHOD_FILE_CHANGE_APPROVAL:
                return handleFileChangeApproval(params);
            case METHOD_MCP_ELICITATION:
                return handleMcpElicitationRequest(params);
            default:
                // Unrecognised server request — Slice 4 adds siblings (MCP tool call,
                // network access). Refuse cleanly rather than leaving Codex's turn
                // blocked forever on an answer this slice cannot give.
                return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Unhandled Codex server request: " + method));
        }
    }

    private CompletableFuture<JsonObject> handleCommandExecutionApproval(JsonObject params) {
        String reason = params.has("reason") && !params.get("reason").isJsonNull()
                ? params.get("reason").getAsString() : null;
        String command = params.has("command") && !params.get("command").isJsonNull()
                ? params.get("command").getAsString() : null;
        String displayText = reason != null ? reason
                : command != null ? "Codex wants to run: " + command
                        : "Codex wants to run a command";
        return raiseConfirmAndReply("Command", displayText, null);
    }

    private CompletableFuture<JsonObject> handleFileChangeApproval(JsonObject params) {
        String itemId = params.has("itemId") && !params.get("itemId").isJsonNull()
                ? params.get("itemId").getAsString() : null;
        // The approval request itself carries no diff (design doc §0a) — the
        // content arrived moments earlier under the same item id via item/started.
        JsonArray changes = itemId != null ? fileChangeCache.remove(itemId) : null;

        // Single-file change with a diff: upgrade to PermissionEvent so the user
        // reviews Codex edits in the same diff panel as the plugin's own edits.
        if (changes != null && changes.size() == 1 && changes.get(0).isJsonObject()) {
            JsonObject c = changes.get(0).getAsJsonObject();
            String fp = c.has("path") && !c.get("path").isJsonNull() ? c.get("path").getAsString() : null;
            String diffHunk = c.has("diff") && !c.get("diff").isJsonNull() ? c.get("diff").getAsString() : null;
            if (fp != null && diffHunk != null && !diffHunk.isBlank()) {
                try {
                    String original = Files.readString(Path.of(fp), StandardCharsets.UTF_8);
                    String proposed = applyUnifiedDiff(original, diffHunk);
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "codex fileChange approval: diff panel path for {0}", fp);
                    }
                    return raisePermissionAndReply(fp, proposed);
                }
                catch (IOException | PatchFailedException e) {
                    LOG.log(Level.WARNING,
                            "Could not apply Codex diff for {0}, falling back to confirm: {1}",
                            new Object[]{fp, e.getMessage()});
                    // Fall through to the blind ConfirmEvent path below.
                }
            }
        }

        // Fallback: multi-file change, missing diff, unreadable file, or a patch
        // that could not be applied (stale content / CRLF / whitespace mismatch).
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "codex fileChange approval: confirm path (changes={0})",
                    changes != null ? changes.size() : 0);
        }
        String displayText = summarizeFileChanges(changes);
        String filePath = firstChangedPath(changes);
        return raiseConfirmAndReply("FileChange", displayText, filePath);
    }

    /**
     * Raises a {@link ConfirmEvent} and maps the eventual
     * {@link PermissionDecision} to Codex's {@code decision} reply shape
     * (schema: FileChangeRequestApprovalResponse /
     * CommandExecutionRequestApprovalResponse). Three distinct values:
     * <ul>
     * <li>{@code "accept"} — user approved
     * <li>{@code "decline"} — user deliberately rejected; agent continues the
     * turn
     * <li>{@code "cancel"} — future completed exceptionally (panel closed,
     * process stopped); agent interrupts the turn immediately
     * </ul>
     * Does not block the calling dispatch-thread — chain completes when the
     * user answers.
     */
    private CompletableFuture<JsonObject> raiseConfirmAndReply(String toolName, String displayText, String filePath) {
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new ConfirmEvent(toolName, displayText, filePath, null, decisionFuture));
        return decisionFuture.handle((decision, ex) -> {
            JsonObject result = new JsonObject();
            result.addProperty("decision", approvalDecision(decision, ex));
            return result;
        });
    }

    /**
     * Like {@link #raiseConfirmAndReply} but raises a {@link PermissionEvent}
     * so the user sees the full Accept/Reject diff panel, identical to the
     * plugin's own {@code ApplyEdit}/{@code WriteFile} review. Same
     * {@code decision} reply shape and decline-vs-cancel semantics.
     */
    private CompletableFuture<JsonObject> raisePermissionAndReply(String filePath, String proposed) {
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new PermissionEvent("Write", filePath, null, null, proposed, decisionFuture));
        return decisionFuture.handle((decision, ex) -> {
            JsonObject result = new JsonObject();
            result.addProperty("decision", approvalDecision(decision, ex));
            return result;
        });
    }

    /**
     * Handles {@code mcpServer/elicitation/request} — gating IDE MCP tool calls
     * that Codex routes through this approval channel. Unlike
     * file-change/command approvals, the response field is {@code "action"}
     * (not {@code "decision"}), and the vocabulary has no
     * {@code acceptForSession} — only {@code accept | decline | cancel}
     * (schema: McpServerElicitationRequestResponse).
     *
     * <p>
     * NOTE: {@code persist:["session","always"]} in {@code _meta} signals
     * auto-accept intent. This is NOT wired here — the decision to expose
     * auto-accept to the user must be made deliberately (flagged for a future
     * slice).
     */
    private CompletableFuture<JsonObject> handleMcpElicitationRequest(JsonObject params) {
        String message = params.has("message") && !params.get("message").isJsonNull()
                ? params.get("message").getAsString() : null;
        String serverName = params.has("serverName") && !params.get("serverName").isJsonNull()
                ? params.get("serverName").getAsString() : null;
        String displayText = message != null ? message
                : serverName != null ? "MCP server \'" + serverName + "\' requests approval"
                        : "MCP server requests approval";
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "codex mcpServer/elicitation/request: serverName={0}", serverName);
        }
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new ConfirmEvent("McpElicitation", displayText, null, null, decisionFuture));
        return decisionFuture.handle((decision, ex) -> {
            JsonObject result = new JsonObject();
            result.addProperty("action", approvalDecision(decision, ex));
            return result;
        });
    }

    @Override
    public void onDisconnected(Exception cause) {
        disconnectCallback.run();
    }
}
