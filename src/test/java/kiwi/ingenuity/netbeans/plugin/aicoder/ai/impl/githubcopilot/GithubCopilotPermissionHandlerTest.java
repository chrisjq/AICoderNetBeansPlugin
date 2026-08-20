package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void ourMcpServerKindApprovesWithoutRaisingAnEvent() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future
                = handler.handle(request("aicoder-nb-ki-plugin(GetFileContent)"), invocation());

        assertTrue(future.isDone());
        assertEquals("approve-once", future.get().getKind());
        assertNull(raised.get(), "our own MCP server calls must not raise a ConfirmEvent");
    }

    @Test
    void shellKindRaisesConfirmEventAndApprovedDecisionMapsToApproveOnce() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("shell(echo)"), invocation());

        assertFalse(future.isDone(), "must wait on the user rather than auto-resolving");
        ConfirmEvent ce = (ConfirmEvent) raised.get();
        assertEquals("Shell", ce.toolName());
        ce.response().complete(PermissionDecision.allowed());

        assertEquals("approve-once", future.get().getKind());
    }

    @Test
    void shellKindRejectedDecisionMapsToReject() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("shell(rm -rf /)"), invocation());

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

        handler.handle(request("shell"), invocation());

        assertTrue(raised.get() instanceof ConfirmEvent);
        assertEquals("Shell", ((ConfirmEvent) raised.get()).toolName());
    }

    @Test
    void writeKindRaisesConfirmEvent() {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        handler.handle(request("write(/tmp/x)"), invocation());

        assertTrue(raised.get() instanceof ConfirmEvent);
        assertEquals("Write", ((ConfirmEvent) raised.get()).toolName());
    }

    @Test
    void urlKindRaisesConfirmEvent() {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        handler.handle(request("url(example.com)"), invocation());

        assertTrue(raised.get() instanceof ConfirmEvent);
        assertEquals("URL", ((ConfirmEvent) raised.get()).toolName());
    }

    @Test
    void unrecognisedKindPromptsRatherThanAutoApproving() {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future
                = handler.handle(request("some-future-kind(thing)"), invocation());

        assertFalse(future.isDone(), "unknown kinds must fail closed and wait on the user, never auto-approve");
        assertTrue(raised.get() instanceof ConfirmEvent);
        // An unrecognised kind is not echoed here — the SDK enumerates no
        // request-side kinds, so it is an arbitrary server string. It still appears
        // in full in the display text.
        assertEquals("Unknown", ((ConfirmEvent) raised.get()).toolName());
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

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("shell(rm -rf /)"), invocation());
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

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("write(/tmp/x)"), invocation());

        handler.cancelPendingPermissions();

        assertTrue(future.isDone(), "the SDK must not be left waiting forever on a cancelled turn");
    }

    @Test
    void cancelPendingPermissions_thenALateUserAnswerDoesNotChangeTheOutcome() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("url(example.com)"), invocation());
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

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("shell(echo)"), invocation());
        ConfirmEvent ce = (ConfirmEvent) raised.get();
        ce.response().complete(PermissionDecision.allowed());
        assertEquals("approve-once", future.get().getKind());

        // pendingPermission was already cleared by handle()'s own .handle() continuation
        // — nothing left to cancel, and the already-resolved outcome must be untouched.
        handler.cancelPendingPermissions();

        assertEquals("approve-once", future.get().getKind());
    }
}
