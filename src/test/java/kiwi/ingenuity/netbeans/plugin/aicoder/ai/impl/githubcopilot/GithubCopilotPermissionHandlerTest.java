package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GithubCopilotPermissionHandlerTest {

    private static PermissionRequest request(String kind) {
        PermissionRequest r = new PermissionRequest();
        r.setKind(kind);
        r.setToolCallId("call-1");
        return r;
    }

    private static PermissionInvocation invocation() {
        return new PermissionInvocation().setSessionId("session-1");
    }

    /**
     * Our own MCP calls must never prompt — but they do announce themselves with
     * a ToolUseEvent, which is what sets AiTopComponent's pendingNewlineBeforeText
     * and so separates the model's narration either side of the call. Copilot
     * emitted no such event at all, and the two text blocks were appended to one
     * bubble verbatim, rendering as "...classes:Let me try...".
     */
    @Test
    void ourMcpServerKindApprovesAndAnnouncesWithoutPrompting() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future
                = handler.handle(request("aicoder-nb-ki-plugin(GetFileContent)"), invocation());

        assertTrue(future.isDone());
        assertEquals("approve-once", future.get().getKind());
        assertFalse(raised.get() instanceof ConfirmEvent,
                "our own MCP server calls must not raise a ConfirmEvent");
        ToolUseEvent tu = assertInstanceOf(ToolUseEvent.class, raised.get(),
                "the call must still be announced so the narration around it stays separated");
        assertEquals(ToolUseEvent.Kind.OTHER, tu.kind());
        assertFalse(tu.isFileModification(), "an MCP call must not trigger the diff panel");
    }

    @Test
    void shellKindRaisesConfirmEventAndApprovedDecisionMapsToApproveOnce() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("commands(echo)"), invocation());

        assertFalse(future.isDone(), "must wait on the user rather than auto-resolving");
        ConfirmEvent ce = (ConfirmEvent) raised.get();
        assertEquals("Shell", ce.toolName());
        assertTrue(ce.requireExplicitApproval());
        ce.response().complete(PermissionDecision.allowed());

        assertEquals("approve-once", future.get().getKind());
    }

    @Test
    void shellKindRejectedDecisionMapsToReject() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("commands(rm -rf /)"), invocation());

        ConfirmEvent ce = (ConfirmEvent) raised.get();
        ce.response().complete(PermissionDecision.denied("no"));

        PermissionRequestResult result = future.get();
        assertEquals("reject", result.getKind());
        assertEquals("no", result.getFeedback());
    }

    @Test
    void bareShellKindFormRoutesTheSameAsFullPatternForm() {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        handler.handle(request("commands"), invocation());

        assertTrue(raised.get() instanceof ConfirmEvent);
        assertEquals("Shell", ((ConfirmEvent) raised.get()).toolName());
    }

    @Test
    void internalKindsRejectAndRaiseSystemNotification() throws Exception {
        for (String kind : new String[]{"read", "path", "url"}) {
            AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
            GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

            PermissionRequestResult result = handler.handle(request(kind), invocation()).get();

            assertEquals("reject", result.getKind());
            String expectedTool = switch (kind) {
                case "read" ->
                    "GetFileContent";
                case "path" ->
                    "GetProjectStructure";
                default ->
                    "WebRequest";
            };
            assertTrue(result.getFeedback().contains(expectedTool));
            assertTrue(raised.get() instanceof SystemNotificationEvent);
            assertTrue(((SystemNotificationEvent) raised.get()).text().startsWith("Internal Command: "));
        }
    }

    @Test
    void unrecognisedKindRejectsWithoutConfirmEvent() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        PermissionRequestResult result = handler.handle(request("some-future-kind(thing)"), invocation()).get();

        assertEquals("reject", result.getKind());
        assertTrue(result.getFeedback().contains("GetInstructions"));
        assertTrue(raised.get() instanceof SystemNotificationEvent);
        assertTrue(((SystemNotificationEvent) raised.get()).text().startsWith("Internal Command: "));
    }

    // ---- cancelPendingPermissions ----
    @Test
    void cancelPendingPermissions_noRequestInFlight_isNoop() {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        // Must not throw when nothing is pending
        handler.cancelPendingPermissions();

        assertNull(raised.get());
    }

    @Test
    void cancelPendingPermissions_withShellPending_repliesUserNotAvailable() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("commands(rm -rf /)"), invocation());
        assertFalse(future.isDone());

        handler.cancelPendingPermissions();

        PermissionRequestResult result = future.get();
        // userNotAvailable(), NOT reject(): reject() reads to the SDK as a deliberate
        // "no" the agent may retry or explain around, and NOT noResult(): that means
        // "let another connected client answer", which does not apply here and throws
        // outright against a v2 (non-broadcast) server per the SDK's own javadoc.
        assertEquals("user-not-available", result.getKind(),
                "cancelPendingPermissions must reply userNotAvailable(), not reject() or noResult()");
    }

    @Test
    void cancelPendingPermissions_doesNotLeaveTheFuturePending() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("commands(/tmp/x)"), invocation());

        handler.cancelPendingPermissions();

        assertTrue(future.isDone(), "the SDK must not be left waiting forever on a cancelled turn");
    }

    @Test
    void cancelPendingPermissions_thenALateUserAnswerDoesNotChangeTheOutcome() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("commands(example.com)"), invocation());
        ConfirmEvent ce = (ConfirmEvent) raised.get();

        handler.cancelPendingPermissions();
        assertEquals("user-not-available", future.get().getKind());

        // The dialog is stale by the time the user answers it late — completing it now
        // must be a no-op (the future is already resolved) rather than calling back
        // into the SDK for a turn that has already been torn down.
        boolean changed = ce.response().complete(PermissionDecision.allowed());
        assertFalse(changed, "a stale confirm dialog must not be able to resolve after cancellation");
        assertEquals("user-not-available", future.get().getKind(),
                "the already-resolved outcome must not change after a late answer");
    }

    @Test
    void cancelPendingPermissions_afterAnAnsweredRequestIsNoop() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("commands(echo)"), invocation());
        ConfirmEvent ce = (ConfirmEvent) raised.get();
        ce.response().complete(PermissionDecision.allowed());
        assertEquals("approve-once", future.get().getKind());

        // pendingPermission was already cleared by handle()'s own .handle() continuation
        // — nothing left to cancel, and the already-resolved outcome must be untouched.
        handler.cancelPendingPermissions();

        assertEquals("approve-once", future.get().getKind());
    }
}
