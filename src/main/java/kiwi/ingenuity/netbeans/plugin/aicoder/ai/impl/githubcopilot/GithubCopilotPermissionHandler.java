package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.McpSteeringPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionGate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

/**
 * Applies the backend permission policy to protocol-neutral Copilot requests.
 */
class GithubCopilotPermissionHandler implements CopilotPermissionGate {

    private static final Logger LOG = Logger.getLogger(GithubCopilotPermissionHandler.class.getName());
    private static final String DEFAULT_REJECT_FEEDBACK
            = "declined in the IDE — do not retry without asking the user first";

    private final AiProcessEventListener listener;
    private final String sessionId;
    private volatile CompletableFuture<PermissionDecision> pendingPermission;
    /**
     * Set by {@link #close} when the session this handler gates is gone. A request that still reaches it (the old SDK
     * reader thread delivering after the teardown) is answered "user not available" at once, without an event, so no
     * dialog for a dead session appears in the next run.
     */
    private volatile boolean closed;

    GithubCopilotPermissionHandler(AiProcessEventListener listener, String sessionId) {
        this.listener = listener;
        this.sessionId = sessionId;
    }

    @Override
    public CompletableFuture<CopilotPermissionDecision> decide(CopilotPermissionRequest request) {
        if (closed) {
            return CompletableFuture.completedFuture(CopilotPermissionDecision.userNotAvailable());
        }
        String kind = request == null ? null : request.kind();
        Map<String, Object> extensionData = request == null ? null : request.extensionData();
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO,
                    "GitHub Copilot permission request: session={0}, kind={1}, toolCallId={2}, extensionData={3}",
                    new Object[]{sessionId, kind, request == null ? null : request.toolCallId(),
                        McpHookServerUtil.redactAllSecrets(String.valueOf(extensionData))});
        }
        GithubCopilotPermissionPolicy.Category category
                = GithubCopilotPermissionPolicy.classify(kind, StringConst.PLUGIN_ID, extensionData);
        if (category == GithubCopilotPermissionPolicy.Category.MCP_OUR_SERVER) {
            Object toolTitle = extensionData == null ? null
                               : extensionData.get(GithubCopilotJsonKeyEnum.TOOL_TITLE.key());
            listener.onAiProcessEvent(new ToolUseEvent(
                    toolTitle != null ? toolTitle.toString() : kind,
                    null, null, null, ToolUseEvent.Kind.OTHER));
            return CompletableFuture.completedFuture(CopilotPermissionDecision.approveOnce());
        }
        String displayText = GithubCopilotPermissionPolicy.describeRequest(kind, extensionData);
        if (category == GithubCopilotPermissionPolicy.Category.INTERNAL
                || category == GithubCopilotPermissionPolicy.Category.UNKNOWN) {
            McpHookServerUtil.logMcpSteeringRefusal("copilot",
                                                    GithubCopilotPermissionPolicy.steeringCategoryFor(category, kind), displayText);
            return CompletableFuture.completedFuture(CopilotPermissionDecision.reject(
                    GithubCopilotPermissionPolicy.rejectFeedbackFor(category, kind, SessionRegistry.get(sessionId))));
        }
        if ((category == GithubCopilotPermissionPolicy.Category.SHELL
                || category == GithubCopilotPermissionPolicy.Category.WRITE) && steeringIsActive()) {
            McpSteeringPolicy.Category steeringCategory = category == GithubCopilotPermissionPolicy.Category.WRITE
                                                          ? McpSteeringPolicy.Category.WRITE : McpSteeringPolicy.Category.SHELL;
            McpHookServerUtil.logMcpSteeringRefusal("copilot", steeringCategory, displayText);
            return CompletableFuture.completedFuture(CopilotPermissionDecision.reject(
                    McpSteeringPolicy.steeringFeedbackFor(steeringCategory, SessionRegistry.get(sessionId))));
        }

        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        if (closed) {
            // Closed between the check above and publishing the pending dialog: close() may have missed it.
            pendingPermission = null;
            return CompletableFuture.completedFuture(CopilotPermissionDecision.userNotAvailable());
        }
        String toolName = GithubCopilotPermissionPolicy.describeToolName(category, kind, extensionData);
        listener.onAiProcessEvent(new ConfirmEvent(toolName, displayText, null, null, decisionFuture, true));
        return decisionFuture.handle((decision, ex) -> {
            pendingPermission = null;
            if (ex != null) {
                return CopilotPermissionDecision.userNotAvailable();
            }
            return decision != null && decision.allow()
                   ? CopilotPermissionDecision.approveOnce()
                   : CopilotPermissionDecision.reject(
                            decision != null && decision.message() != null
                            ? decision.message() : DEFAULT_REJECT_FEEDBACK);
        });
    }

    /**
     * Retires the handler with its session: cancels any open dialog and answers every later request "user not
     * available" without raising an event. Not used by a Stop, which only ends the turn and keeps the session (and so
     * this handler) for the next one; that is {@link #cancelPendingPermissions}.
     */
    void close() {
        closed = true;
        cancelPendingPermissions();
    }

    void cancelPendingPermissions() {
        CompletableFuture<PermissionDecision> pf = pendingPermission;
        if (pf != null) {
            pendingPermission = null;
            pf.completeExceptionally(new CancellationException("turn cancelled"));
        }
    }

    private boolean steeringIsActive() {
        AbstractAiSession session = SessionRegistry.get(sessionId);
        return session != null && session.getSettings() != null
                && session.getSettings().effectiveMcpSteering()
                && session.getType().mcpSteeringSupport().supported();
    }
}
