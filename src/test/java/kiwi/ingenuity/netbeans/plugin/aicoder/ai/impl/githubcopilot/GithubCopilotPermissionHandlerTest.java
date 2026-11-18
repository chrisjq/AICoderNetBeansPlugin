package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.McpSteeringPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GithubCopilotPermissionHandlerTest {

    private static CopilotPermissionRequest request(String kind) {
        return new CopilotPermissionRequest(kind, "call-1", null);
    }

    /**
     * Our own MCP calls must never prompt — but they do announce themselves with a ToolUseEvent, which is what sets
     * AiTopComponent's pendingNewlineBeforeText and so separates the model's narration either side of the call. Copilot
     * emitted no such event at all, and the two text blocks were appended to one bubble verbatim, rendering as
     * "...classes:Let me try...".
     */
    @Test
    void ourMcpServerKindApprovesAndAnnouncesWithoutPrompting() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<CopilotPermissionDecision> future
                = handler.decide(request("aicoder-nb-ki-plugin(GetFileContent)"));

        assertTrue(future.isDone());
        assertInstanceOf(CopilotPermissionDecision.ApproveOnce.class, future.get());
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

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("commands(echo)"));

        assertFalse(future.isDone(), "must wait on the user rather than auto-resolving");
        ConfirmEvent ce = (ConfirmEvent) raised.get();
        assertEquals("Shell", ce.toolName());
        assertTrue(ce.requireExplicitApproval());
        ce.response().complete(PermissionDecision.allowed());

        assertInstanceOf(CopilotPermissionDecision.ApproveOnce.class, future.get());
    }

    @Test
    void shellKindRejectedDecisionMapsToReject() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("commands(rm -rf /)"));

        ConfirmEvent ce = (ConfirmEvent) raised.get();
        ce.response().complete(PermissionDecision.denied("no"));

        CopilotPermissionDecision.Reject result = assertInstanceOf(CopilotPermissionDecision.Reject.class, future.get());
        assertEquals("no", result.feedback());
    }

    @Test
    void bareShellKindFormRoutesTheSameAsFullPatternForm() {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        handler.decide(request("commands"));

        assertTrue(raised.get() instanceof ConfirmEvent);
        assertEquals("Shell", ((ConfirmEvent) raised.get()).toolName());
    }

    @Test
    void internalKindsRejectWithoutAnyEventOrNotification() throws Exception {
        for (String kind : new String[]{"read", "path", "url"}) {
            AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
            GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

            CopilotPermissionDecision.Reject result = assertInstanceOf(CopilotPermissionDecision.Reject.class,
                                                                       handler.decide(request(kind)).get());

            String expectedTool = switch (kind) {
                case "read" ->
                    "GetFileContent";
                case "path" ->
                    "GetProjectStructure";
                default ->
                    "WebRequest";
            };
            assertTrue(result.feedback().contains(expectedTool));
            assertNull(raised.get(),
                       "a steering refusal is kept in the tool-use log, not announced in the chat transcript");
        }
    }

    @Test
    void unrecognisedKindRejectsWithoutConfirmEvent() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CopilotPermissionDecision.Reject result = assertInstanceOf(CopilotPermissionDecision.Reject.class,
                                                                   handler.decide(request("some-future-kind(thing)")).get());

        assertTrue(result.feedback().contains("GetInstructions"));
        assertNull(raised.get(),
                   "a steering refusal is kept in the tool-use log, not announced in the chat transcript");
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

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("commands(rm -rf /)"));
        assertFalse(future.isDone());

        handler.cancelPendingPermissions();

        CopilotPermissionDecision result = future.get();
        // userNotAvailable(), NOT reject(): reject() reads to the SDK as a deliberate
        // "no" the agent may retry or explain around, and NOT noResult(): that means
        // "let another connected client answer", which does not apply here and throws
        // outright against a v2 (non-broadcast) server per the SDK's own javadoc.
        assertInstanceOf(CopilotPermissionDecision.UserNotAvailable.class, result,
                         "cancelPendingPermissions must reply userNotAvailable(), not reject() or noResult()");
    }

    @Test
    void cancelPendingPermissions_doesNotLeaveTheFuturePending() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("commands(/tmp/x)"));

        handler.cancelPendingPermissions();

        assertTrue(future.isDone(), "the SDK must not be left waiting forever on a cancelled turn");
    }

    @Test
    void cancelPendingPermissions_thenALateUserAnswerDoesNotChangeTheOutcome() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("commands(example.com)"));
        ConfirmEvent ce = (ConfirmEvent) raised.get();

        handler.cancelPendingPermissions();
        assertInstanceOf(CopilotPermissionDecision.UserNotAvailable.class, future.get());

        // The dialog is stale by the time the user answers it late — completing it now
        // must be a no-op (the future is already resolved) rather than calling back
        // into the SDK for a turn that has already been torn down.
        boolean changed = ce.response().complete(PermissionDecision.allowed());
        assertFalse(changed, "a stale confirm dialog must not be able to resolve after cancellation");
        assertInstanceOf(CopilotPermissionDecision.UserNotAvailable.class, future.get(),
                         "the already-resolved outcome must not change after a late answer");
    }

    @Test
    void cancelPendingPermissions_afterAnAnsweredRequestIsNoop() throws Exception {
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("commands(echo)"));
        ConfirmEvent ce = (ConfirmEvent) raised.get();
        ce.response().complete(PermissionDecision.allowed());
        assertInstanceOf(CopilotPermissionDecision.ApproveOnce.class, future.get());

        // pendingPermission was already cleared by decide()'s own .handle() continuation
        // — nothing left to cancel, and the already-resolved outcome must be untouched.
        handler.cancelPendingPermissions();

        assertInstanceOf(CopilotPermissionDecision.ApproveOnce.class, future.get());
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
            public AiProcessEventListener
                    getAiProcessEventListener() {
                return null;
            }

            @Override
            public Map<McpToolEnum, McpToolInterface>
                    getMcpToolHandlers() {
                return Map.of();
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

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("commands(echo)"));

        // Should be resolved immediately, not waiting for user input
        assertTrue(future.isDone(), "steering ON should auto-deny without waiting for user");

        // No event at all: the refusal is a tool-use log line, not a chat announcement.
        assertNull(raised.get(),
                   "steering ON must not announce refusals in chat (they go to the tool-use log)");

        // Reject with steering feedback
        CopilotPermissionDecision.Reject result = assertInstanceOf(CopilotPermissionDecision.Reject.class, future.get());
        String feedback = result.feedback();
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

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("commands(echo)"));

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

        CompletableFuture<CopilotPermissionDecision> future
                = handler.decide(request("aicoder-nb-ki-plugin(GetFileContent)"));

        assertTrue(future.isDone());
        assertInstanceOf(CopilotPermissionDecision.ApproveOnce.class, future.get(),
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

            CopilotPermissionDecision.Reject result = assertInstanceOf(CopilotPermissionDecision.Reject.class,
                                                                       handler.decide(request(kind)).get());

            String feedback = result.feedback();
            // INTERNAL must state refusal is automatic (not user's doing)
            assertTrue(feedback.contains("Refused automatically by this IDE"),
                       "INTERNAL must state refusal is automatic and not user decision");
            // INTERNAL must NOT mention the steering policy specifically
            assertFalse(feedback.contains("steering policy"),
                        "INTERNAL rejection is unconditional, not from the steering policy");
            assertNull(raised.get(),
                       "a steering refusal is kept in the tool-use log, not announced in the chat transcript");
        }
    }

    @Test
    void writeKindWithSteeringOnRejectsWithWriteSteerAndLogsCategory() throws Exception {
        // apply_patch arrives with kind "write". Steering ON must refuse it with the WRITE
        // steer text (the same ApplyEdit/WriteFile set OpenCode's write path uses), not the
        // generic GetInstructions text an UNKNOWN classification produced.
        boolean previous = PluginSettings.isLogToolUse();
        Logger logger = Logger.getLogger(McpHookServerUtil.class.getName());
        List<String> captured = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            PluginSettings.setLogToolUse(true);

            AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
            GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, testSessionId);

            CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("write"));

            assertTrue(future.isDone(), "steering ON must auto-deny a write without waiting for user");
            CopilotPermissionDecision.Reject result = assertInstanceOf(CopilotPermissionDecision.Reject.class,
                                                                       future.get());
            assertTrue(result.feedback().contains(McpSteeringPolicy.steeringFeedbackFor(
                    McpSteeringPolicy.Category.WRITE)),
                       "a steered write must carry the WRITE steer text, not the generic GetInstructions one");
            assertNull(raised.get(),
                       "the refusal is only a tool-use log line, never a chat event");
            assertEquals("MCP Steering refusal: backend=copilot, category=WRITE: "
                    + GithubCopilotPermissionPolicy.describeRequest("write", null),
                         captured.get(0), "the write refusal must log under category=WRITE");
        }
        finally {
            logger.removeHandler(capture);
            PluginSettings.setLogToolUse(previous);
        }
    }

    @Test
    void writeKindWithoutSteeringIsNotAutoRefusedAndRaisesConfirmEvent() throws Exception {
        // Steering OFF: a native write must stay OPTIONAL. It is not auto-denied (the
        // regression risk of an INTERNAL-style mapping) — it falls through to the
        // user confirm prompt like every other unsteered request.
        AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "unregistered-session");

        CompletableFuture<CopilotPermissionDecision> future = handler.decide(request("write"));

        assertFalse(future.isDone(), "steering OFF must not auto-resolve a write; the user must be asked");
        ConfirmEvent ce = assertInstanceOf(ConfirmEvent.class, raised.get(),
                                           "with steering off a write must raise the user confirm prompt, not an auto-denial");
        assertEquals("Write", ce.toolName());
        assertTrue(ce.requireExplicitApproval());
        ce.response().complete(PermissionDecision.denied("no"));
        CopilotPermissionDecision.Reject rejected = assertInstanceOf(CopilotPermissionDecision.Reject.class,
                                                                     future.get());
        assertEquals("no", rejected.feedback());
    }

    @Test
    void steeringRefusalsLogOneLinePerRefusalWhenToolUseLoggingIsOn() throws Exception {
        boolean previous = PluginSettings.isLogToolUse();
        Logger logger = Logger.getLogger(McpHookServerUtil.class.getName());
        List<String> captured = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            PluginSettings.setLogToolUse(true);

            // INTERNAL read/path/url and UNKNOWN each steer away from one MCP tool.
            String[] kinds = {"read", "path", "url", "some-future-kind(thing)"};
            McpSteeringPolicy.Category[] categories = {
                McpSteeringPolicy.Category.READ,
                McpSteeringPolicy.Category.PATH,
                McpSteeringPolicy.Category.URL,
                McpSteeringPolicy.Category.UNKNOWN
            };
            for (int i = 0; i < kinds.length; i++) {
                String kind = kinds[i];
                AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
                GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, "session-1");

                CopilotPermissionDecision result = handler.decide(request(kind)).get();

                assertInstanceOf(CopilotPermissionDecision.Reject.class, result);
                assertNull(raised.get(),
                           "the refusal is only a log line, never an event");
                assertEquals("MCP Steering refusal: backend=copilot, category=" + categories[i] + ": "
                        + GithubCopilotPermissionPolicy.describeRequest(kind, null),
                             captured.get(i), "one line per refusal, naming its category and what was refused");
            }
            assertEquals(4, captured.size(),
                         "exactly one line per refusal — the old SystemNotificationEvent is not additionally logged");

            // SHELL steering refusal (needs an active steering session, registered in @BeforeEach).
            AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
            GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, testSessionId);
            CopilotPermissionDecision result = handler.decide(request("commands(echo)")).get();
            assertInstanceOf(CopilotPermissionDecision.Reject.class, result);
            assertNull(raised.get(), "the shell refusal is only a log line, never an event");
            assertEquals("MCP Steering refusal: backend=copilot, category=SHELL: "
                    + GithubCopilotPermissionPolicy.describeRequest("commands(echo)", null),
                         captured.get(4), "the shell refusal logs one line, not a chat notification");
            assertEquals(5, captured.size());
        }
        finally {
            logger.removeHandler(capture);
            PluginSettings.setLogToolUse(previous);
        }
    }

    @Test
    void steeringRefusalsLogNothingWhenToolUseLoggingIsOff() throws Exception {
        boolean previous = PluginSettings.isLogToolUse();
        Logger logger = Logger.getLogger(McpHookServerUtil.class.getName());
        List<String> captured = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            PluginSettings.setLogToolUse(false);

            AtomicReference<AiProcessEvent> raised = new AtomicReference<>();
            GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::set, testSessionId);
            handler.decide(request("read")).get();
            CopilotPermissionDecision shell = handler.decide(request("commands(echo)")).get();
            assertInstanceOf(CopilotPermissionDecision.Reject.class, shell);
            assertNull(raised.get(),
                       "a steering refusal must not raise any event whether or not tool-use logging is on");

            assertTrue(captured.isEmpty(),
                       "with tool-use logging off, a steering refusal must keep out of the log");
        }
        finally {
            logger.removeHandler(capture);
            PluginSettings.setLogToolUse(previous);
        }
    }

    /**
     * A handler retired with its session cancels the dialog still open and answers every later request "user not
     * available" at once, raising nothing: a request the old reader thread delivers after the teardown must not show a
     * dialog for a dead session in the next run.
     */
    @Test
    void aClosedHandlerCancelsItsDialogAndAnswersLaterRequestsWithoutAnEvent() throws Exception {
        List<AiProcessEvent> raised = new ArrayList<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::add, "session-1");
        CompletableFuture<CopilotPermissionDecision> open = handler.decide(request("commands(echo)"));
        assertEquals(1, raised.size(), "the open request raised its dialog");

        handler.close();

        assertInstanceOf(CopilotPermissionDecision.UserNotAvailable.class, open.get(),
                         "the dialog still open is answered when the handler closes");
        for (String kind : new String[]{"commands(ls)", "write", "aicoder-nb-ki-plugin(GetFileContent)"}) {
            CompletableFuture<CopilotPermissionDecision> late = handler.decide(request(kind));
            assertTrue(late.isDone(), kind + " must be answered at once");
            assertInstanceOf(CopilotPermissionDecision.UserNotAvailable.class, late.get(), kind);
        }
        assertEquals(1, raised.size(), () -> "a closed handler must raise nothing: " + raised);
    }

    /**
     * A Stop only cancels the open dialog: the session and its handler stay, so the next turn can still ask.
     */
    @Test
    void cancellingPendingPermissionsDoesNotCloseTheHandler() {
        List<AiProcessEvent> raised = new ArrayList<>();
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(raised::add, "session-1");
        handler.decide(request("commands(echo)"));

        handler.cancelPendingPermissions();
        CompletableFuture<CopilotPermissionDecision> next = handler.decide(request("commands(ls)"));

        assertFalse(next.isDone(), "the next request must wait on the user again");
        assertInstanceOf(ConfirmEvent.class, raised.get(raised.size() - 1));
    }
}
