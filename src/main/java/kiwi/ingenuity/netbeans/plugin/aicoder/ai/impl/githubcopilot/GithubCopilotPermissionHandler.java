package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Replaces {@code PermissionHandler.APPROVE_ALL}. Our own MCP server's tool
 * calls are approved immediately — they are already gated by the plugin
 * (project scope, diff panel, web/database permissions), so asking again is
 * pure noise. Everything else — shell, write, url, and anything unrecognised —
 * raises a {@link ConfirmEvent} so the user is actually asked, matching
 * OpenCode's forced {@code ask} and Codex's {@code approvalPolicy}: this is the
 * only backend that previously had no user-visible gate for shell execution at
 * all.
 *
 * <p>
 * See {@link GithubCopilotPermissionPolicy} for the kind-matching rules and why
 * they are deliberately generous rather than exact.
 */
class GithubCopilotPermissionHandler implements PermissionHandler {

    private static final Logger LOG = Logger.getLogger(GithubCopilotPermissionHandler.class.getName());

    /**
     * Sent as the {@code reject} feedback when the user declines without typing
     * a reason. Copilot prefixes its own sentence and appends ours, so a live
     * refusal reads: <em>"The user rejected this tool call. User feedback:
     * …"</em>
     * — which makes a bare "denied by user" pure repetition, wasting the one
     * channel available for telling the model what to do next. Copilot
     * classifies a rejection as recoverable, so it may retry unless told
     * otherwise; this matches the wording the file tools already use ("do not
     * retry without asking").
     */
    private static final String DEFAULT_REJECT_FEEDBACK
            = "declined in the IDE — do not retry without asking the user first";

    private final AiProcessEventListener listener;
    private final String sessionId;

    /**
     * Outstanding confirm-dialog decision future — at most one at a time
     * (Copilot's permission requests block the turn until answered, same as
     * Codex). Written by {@link #handle}; read by
     * {@link #cancelPendingPermissions()} on stop/interrupt.
     */
    private volatile CompletableFuture<PermissionDecision> pendingPermission;

    GithubCopilotPermissionHandler(AiProcessEventListener listener, String sessionId) {
        this.listener = listener;
        this.sessionId = sessionId;
    }

    @Override
    public CompletableFuture<PermissionRequestResult> handle(PermissionRequest request, PermissionInvocation invocation) {
        String kind = request.getKind();
        Map<String, Object> extensionData = request.getExtensionData();
        // Permanent, not just for bring-up: this is how the real getKind() vocabulary
        // (and whatever extensionData actually carries) gets learned over time.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO,
                    "GitHub Copilot permission request: session={0}, kind={1}, toolCallId={2}, extensionData={3}",
                    new Object[]{sessionId, kind, request.getToolCallId(), extensionData});
        }
        GithubCopilotPermissionPolicy.Category category
                = GithubCopilotPermissionPolicy.classify(kind, StringConst.PLUGIN_ID, extensionData);
        if (category == GithubCopilotPermissionPolicy.Category.MCP_OUR_SERVER) {
            // Announce the call before approving it. Copilot emitted no ToolUseEvent
            // at all, and that event is what sets AiTopComponent's
            // pendingNewlineBeforeText — the only thing that separates the narration
            // either side of a tool call. Without it the two text blocks are appended
            // to the same bubble verbatim and collide: the model ends one block
            // "...search for the process manager classes:" and opens the next with
            // "Let me try...", rendering as "classes:Let me try". The blocks carry no
            // trailing whitespace (verified against live session data), so the break
            // has to be synthesised here, at the only point that knows a tool ran.
            //
            // Kind.OTHER with a null path deliberately: these are MCP calls, so
            // isFileModification() stays false and no diff panel is raised. This only
            // restores the paragraph break; read-only tool calls remain invisible in
            // the transcript, the same as for every other backend.
            Object toolTitle = extensionData == null ? null : extensionData.get("toolTitle");
            listener.onAiProcessEvent(new ToolUseEvent(
                    toolTitle != null ? toolTitle.toString() : kind,
                    null, null, null, ToolUseEvent.Kind.OTHER));
            return CompletableFuture.completedFuture(PermissionRequestResult.approveOnce());
        }
        String displayText = GithubCopilotPermissionPolicy.describeRequest(kind, extensionData);
        if (category == GithubCopilotPermissionPolicy.Category.INTERNAL
                || category == GithubCopilotPermissionPolicy.Category.UNKNOWN) {
            listener.onAiProcessEvent(new SystemNotificationEvent("Internal Command: " + displayText));
            return CompletableFuture.completedFuture(PermissionRequestResult.reject(
                    GithubCopilotPermissionPolicy.rejectFeedbackFor(category, kind)));
        }
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        String toolName = GithubCopilotPermissionPolicy.describeToolName(category, kind, extensionData);
        listener.onAiProcessEvent(new ConfirmEvent(toolName, displayText, null, null, decisionFuture, true));
        return decisionFuture.handle((decision, ex) -> {
            pendingPermission = null;
            if (ex != null) {
                // Cancelled out from under the dialog by cancelPendingPermissions() —
                // no user is available to answer this specific request any more.
                // NOT reject(): that reads to the SDK as a deliberate "no", and the
                // agent may retry or explain around it rather than stopping. NOT
                // noResult(): that means "leave it for another connected client to
                // answer", which only applies under the SDK's v3 broadcast permission
                // model and throws IllegalStateException outright against a v2 server
                // (per SDK javadoc) — we have no other client waiting either way.
                return PermissionRequestResult.userNotAvailable();
            }
            return decision != null && decision.allow()
                    ? PermissionRequestResult.approveOnce()
                    : PermissionRequestResult.reject(
                            decision != null && decision.message() != null ? decision.message() : DEFAULT_REJECT_FEEDBACK);
        });
    }

    /**
     * Cancels any outstanding permission dialog when the turn is stopped or
     * interrupted. Completes the pending future exceptionally, which routes
     * through {@link #handle}'s {@code .handle()} continuation and replies
     * {@link PermissionRequestResult#userNotAvailable()} to Copilot instead of
     * leaving the dialog open and the turn wedged. Safe to call when no
     * permission request is in flight. Mirrors {@code
     * CodexAppServerHandler.cancelPendingPermissions()} / {@code
     * OpenCodeAcpClientHandler.cancelPendingPermissions()}.
     */
    void cancelPendingPermissions() {
        CompletableFuture<PermissionDecision> pf = pendingPermission;
        if (pf != null) {
            pendingPermission = null;
            pf.completeExceptionally(new CancellationException("turn cancelled"));
        }
    }
}
