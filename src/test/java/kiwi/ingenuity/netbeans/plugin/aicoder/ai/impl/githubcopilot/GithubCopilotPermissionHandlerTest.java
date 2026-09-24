package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.McpSteeringPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
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
     * Our own MCP calls must never prompt — but they do announce themselves with a ToolUseEvent, which is
     * what sets AiTopComponent's pendingNewlineBeforeText and so separates the model's narration either side
     * of the call. Copilot emitted no such event at all, and the two text blocks were appended to one bubble
     * verbatim, rendering as "...classes:Let me try...".
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

    // ---- MCP Steering ----
    private String testSessionId;

    @BeforeEach
    void registerSteeringEnabledSession() {
        // Register a session with MCP steering enabled for steering tests
        AiSession session = AiSession.create("steering-test-session", AiTypeEnum.GitHubCoPilot);
        session.settings().setMcpSteering(Boolean.TRUE);
        AbstractAiSession wrapper = new AbstractAiSession(session) {
            @Override
            public String getId() {
                return session.id();
            }

            @Override
            public kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener
                    getAiProcessEventListener() {
                return null;
            }

            @Override
            public java.util.Map<kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum, kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface>
                    getMcpToolHandlers() {
                return java.util.Map.of();
            }
        };
        SessionRegistry.register(wrapper);
        testSessionId = session.id();
    }

    @AfterEach
    void unregisterTestSession() {
        if (testSessionId != null) {
            SessionRegistry.unregister(testSessionId);
        }
    }

    @Test
    void shellKindWithSteeringOnRejectsWithSteeringTextAndNoConfirmEvent() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, testSessionId);

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("commands(echo)"), invocation());

        // Should be resolved immediately, not waiting for user input
        assertTrue(future.isDone(), "steering ON should auto-deny without waiting for user");

        // No ConfirmEvent raised
        assertTrue(raised.get() instanceof SystemNotificationEvent,
                "steering ON should emit SystemNotificationEvent, not ConfirmEvent");

        // Reject with steering feedback
        PermissionRequestResult result = future.get();
        assertEquals("reject", result.getKind());
        String feedback = result.getFeedback();
        assertTrue(feedback.contains("Refused automatically"), "feedback must state refusal is automatic");
        assertTrue(feedback.contains(McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.SHELL)),
                "feedback must contain steering text for SHELL");
    }

    @Test
    void shellKindWithoutRegisteredSession_raisesConfirmEventBecauseSteeringIsOff() throws Exception {
        // When SessionRegistry.get(sessionId) returns null (no session registered),
        // steering is considered off, and the existing ConfirmEvent behavior is preserved.
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "unregistered-session");

        CompletableFuture<PermissionRequestResult> future = handler.handle(request("commands(echo)"), invocation());

        assertFalse(future.isDone(), "must wait on the user rather than auto-resolving when steering is off");
        assertTrue(raised.get() instanceof ConfirmEvent,
                "shell requests must raise ConfirmEvent when steering is off (no session registered)");
        assertEquals("Shell", ((ConfirmEvent) raised.get()).toolName());
    }

    @Test
    void steeringOnDoesNotAffectMcpOurServer() throws Exception {
        // MCP_OUR_SERVER is auto-approved unconditionally, regardless of steering state.
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, testSessionId);

        CompletableFuture<PermissionRequestResult> future
                = handler.handle(request("aicoder-nb-ki-plugin(GetFileContent)"), invocation());

        assertTrue(future.isDone());
        assertEquals("approve-once", future.get().getKind(),
                "MCP_OUR_SERVER must approve without checking steering status");
        assertFalse(raised.get() instanceof ConfirmEvent,
                "MCP_OUR_SERVER must never raise a ConfirmEvent");
    }

    @Test
    void steeringOnDoesNotAffectInternalCategories() throws Exception {
        // INTERNAL categories are unconditionally auto-rejected with their own feedback,
        // never steered. Steering only affects the ConfirmEvent path.
        // INTERNAL uses the same automatic preamble as steering (user was not asked),
        // but does NOT attribute it to the steering policy specifically.
        for (String kind : new String[]{"read", "path", "url"}) {
            AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
            GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, testSessionId);

            PermissionRequestResult result = handler.handle(request(kind), invocation()).get();

            assertEquals("reject", result.getKind());
            String feedback = result.getFeedback();
            // INTERNAL must state refusal is automatic (not user's doing)
            assertTrue(feedback.contains("Refused automatically by this IDE"),
                    "INTERNAL must state refusal is automatic and not user decision");
            // INTERNAL must NOT mention the steering policy specifically
            assertFalse(feedback.contains("steering policy"),
                    "INTERNAL rejection is unconditional, not from the steering policy");
            assertTrue(raised.get() instanceof SystemNotificationEvent);
            assertTrue(((SystemNotificationEvent) raised.get()).text().startsWith("Internal Command: "));
        }
    }
}
