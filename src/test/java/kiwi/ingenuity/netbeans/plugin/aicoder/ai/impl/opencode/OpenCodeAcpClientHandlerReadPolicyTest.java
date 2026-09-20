package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent.Refusal;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A read is a policy decision, not a conversation: OpenCode's request to read a path is allowed or refused by the rule
 * {@code GetFileContent} applies, with no event raised and nothing shown to the user. Anything not confidently a read
 * keeps asking, and a mutation keeps its diff review.
 *
 * <p>
 * The {@code external_directory} ask arrives as {@code kind:"other"} and names no tool, so it is only treated as a read
 * when its {@code toolCallId} traces back to an earlier {@code tool_call} of kind {@code read}/{@code search}.
 */
class OpenCodeAcpClientHandlerReadPolicyTest {

    private static final String OUTSIDE_FILE = "/Users/chris/Downloads/report/summary.txt";
    private static final String OUTSIDE_DIR = "/Users/chris/Downloads/report";

    private static OpenCodeAcpClientHandler handler(List<AiProcessEvent> fired, Predicate<String> own,
                                                    Predicate<String> read) {
        return new OpenCodeAcpClientHandler(fired::add, () -> {
                                    }, null, new OpenCodeAcpClientHandler.SessionFileScope(own, read));
    }

    /**
     * The {@code tool_call} update OpenCode sends when a tool starts, which is where the kind of a call id is learned.
     */
    private static void announceToolCall(OpenCodeAcpClientHandler handler, String callId, String kind, String status) {
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "tool_call");
        update.addProperty("toolCallId", callId);
        update.addProperty("title", kind);
        update.addProperty("kind", kind);
        update.addProperty("status", status);
        handler.onSessionUpdate("ses_x", update);
    }

    private static JsonArray locations(String... paths) {
        JsonArray array = new JsonArray();
        for (String path : paths) {
            JsonObject location = new JsonObject();
            location.addProperty("path", path);
            array.add(location);
        }
        return array;
    }

    /**
     * What OpenCode's ACP agent sends for an {@code external_directory} permission: kind "other", parentDir as title,
     * file and directory as locations, the metadata as rawInput, the id of the tool call that raised it.
     */
    private static JsonObject externalDirectory(String callId, String filepath, String parentDir) {
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("filepath", filepath);
        rawInput.addProperty("parentDir", parentDir);
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("toolCallId", callId);
        toolCall.addProperty("title", parentDir);
        toolCall.addProperty("kind", "other");
        toolCall.addProperty("status", "pending");
        toolCall.add("locations", locations(filepath, parentDir));
        toolCall.add("rawInput", rawInput);
        return toolCall;
    }

    private static JsonObject params(JsonObject toolCall) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "ses_abc");
        params.add("toolCall", toolCall);
        return params;
    }

    private static String outcome(JsonObject result) {
        JsonObject o = result.getAsJsonObject("outcome");
        assertEquals("selected", o.get("outcome").getAsString(), "a decided request must be answered with a selection");
        return o.get("optionId").getAsString();
    }

    private static void assertNothingRaised(List<AiProcessEvent> fired) {
        assertTrue(fired.isEmpty(), "a read decided by policy must raise no event at all, but raised: " + fired);
    }

    // ---- The decision ----
    @Test
    void anExternalDirectoryReadTheScopeAllowsIsAnsweredOnceWithNoEventAtAll() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = handler(fired, p -> false, p -> true);
        announceToolCall(handler, "call-r", "read", "pending");
        fired.removeIf(e -> e instanceof ToolUseEvent);

        CompletableFuture<JsonObject> future = handler.onRequestPermission(
                params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR)));

        assertNothingRaised(fired);
        assertTrue(future.isDone(), "OpenCode must be answered at once, never left waiting");
        assertEquals("once", outcome(future.get()));
    }

    @Test
    void anExternalDirectoryReadTheScopeRefusesIsRejectedCleanlyWithNoEventAtAll() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = handler(fired, p -> false, p -> false);
        announceToolCall(handler, "call-r", "read", "pending");
        fired.removeIf(e -> e instanceof ToolUseEvent);

        CompletableFuture<JsonObject> future = handler.onRequestPermission(
                params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR)));

        assertNothingRaised(fired);
        assertTrue(future.isDone(), "a refusal must be delivered, not left pending");
        assertFalse(future.isCompletedExceptionally());
        assertEquals("reject", outcome(future.get()), "the same clean reply a user's No produces");
    }

    @Test
    void aDirectReadKindRequestIsDecidedByTheScopeToo() throws Exception {
        for (boolean allowed : new boolean[]{true, false}) {
            List<AiProcessEvent> fired = new ArrayList<>();
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("kind", "read");
            toolCall.addProperty("title", "summary.txt");
            toolCall.add("locations", locations(OUTSIDE_FILE));

            CompletableFuture<JsonObject> future = handler(fired, p -> false, p -> allowed)
                    .onRequestPermission(params(toolCall));

            assertNothingRaised(fired);
            assertEquals(allowed ? "once" : "reject", outcome(future.get()));
        }
    }

    @Test
    void aSearchKindRequestIsDecidedByTheScopeToo() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("kind", "search");
        toolCall.addProperty("title", "TODO");
        toolCall.add("locations", locations(OUTSIDE_DIR));

        CompletableFuture<JsonObject> future = handler(fired, p -> false, p -> false)
                .onRequestPermission(params(toolCall));

        assertNothingRaised(fired);
        assertEquals("reject", outcome(future.get()));
    }

    @Test
    void everyNamedPathMustPassAndOneRefusedPathRefusesTheWholeRequest() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        // The file is readable, its parent directory is not: GetFileContent would refuse the directory, so the request
        // is refused even though the first path alone would have passed.
        OpenCodeAcpClientHandler handler = handler(fired, p -> false, p -> p.equals(OUTSIDE_FILE));
        announceToolCall(handler, "call-r", "read", "pending");
        fired.removeIf(e -> e instanceof ToolUseEvent);

        CompletableFuture<JsonObject> future = handler.onRequestPermission(
                params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR)));

        assertNothingRaised(fired);
        assertEquals("reject", outcome(future.get()));
    }

    // ---- What is NOT a confident read keeps asking, and the scope is never consulted for it ----
    private static void assertAsksAndNeverConsultsTheScope(JsonObject toolCall, String announcedKind) {
        List<AiProcessEvent> fired = new ArrayList<>();
        AtomicInteger consulted = new AtomicInteger();
        OpenCodeAcpClientHandler handler = handler(fired, p -> false, p -> {
                                               consulted.incrementAndGet();
                                               return true;
                                           });
        if (announcedKind != null) {
            announceToolCall(handler, "call-x", announcedKind, "pending");
            fired.removeIf(e -> e instanceof ToolUseEvent);
        }

        CompletableFuture<JsonObject> future = handler.onRequestPermission(params(toolCall));

        assertFalse(future.isDone(), "must wait for the user");
        assertEquals(1, fired.size());
        assertInstanceOf(ConfirmEvent.class, fired.get(0));
        assertEquals(0, consulted.get(), "the read rule must not decide something that is not confidently a read");
    }

    @Test
    void anExternalDirectoryAskRaisedByAnEditIsNotDecidedAsARead() {
        assertAsksAndNeverConsultsTheScope(externalDirectory("call-x", OUTSIDE_FILE, OUTSIDE_DIR), "edit");
    }

    @Test
    void anExternalDirectoryAskRaisedByACommandIsNotDecidedAsARead() {
        assertAsksAndNeverConsultsTheScope(externalDirectory("call-x", OUTSIDE_FILE, OUTSIDE_DIR), "execute");
    }

    @Test
    void anExternalDirectoryAskWhoseToolCallWasNeverSeenIsNotDecidedAsARead() {
        assertAsksAndNeverConsultsTheScope(externalDirectory("call-x", OUTSIDE_FILE, OUTSIDE_DIR), null);
    }

    @Test
    void aCompletedToolCallIsForgottenSoALaterAskIsNotTreatedAsARead() {
        List<AiProcessEvent> fired = new ArrayList<>();
        AtomicInteger consulted = new AtomicInteger();
        OpenCodeAcpClientHandler handler = handler(fired, p -> false, p -> {
                                               consulted.incrementAndGet();
                                               return true;
                                           });
        announceToolCall(handler, "call-x", "read", "pending");
        JsonObject done = new JsonObject();
        done.addProperty("sessionUpdate", "tool_call_update");
        done.addProperty("toolCallId", "call-x");
        done.addProperty("status", "completed");
        handler.onSessionUpdate("ses_x", done);
        fired.removeIf(e -> e instanceof ToolUseEvent);

        handler.onRequestPermission(params(externalDirectory("call-x", OUTSIDE_FILE, OUTSIDE_DIR)));

        assertEquals(1, fired.size());
        assertInstanceOf(ConfirmEvent.class, fired.get(0));
        assertEquals(0, consulted.get());
    }

    @Test
    void aReadTheScopeCannotDecideBecauseItFailsFallsBackToAsking() {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = handler(fired, p -> false, p -> {
                                               throw new IllegalStateException("registry unavailable");
                                           });
        announceToolCall(handler, "call-r", "read", "pending");
        fired.removeIf(e -> e instanceof ToolUseEvent);

        CompletableFuture<JsonObject> future = handler.onRequestPermission(
                params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR)));

        assertFalse(future.isDone(), "when the decision cannot be made the user is asked, never silently answered");
        assertInstanceOf(ConfirmEvent.class, fired.get(0));
    }

    @Test
    void aPlainPredicateCarriesNoReadPolicySoAReadIsStillPutToTheUser() {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
                                                                }, null, path -> false);
        announceToolCall(handler, "call-r", "read", "pending");
        fired.removeIf(e -> e instanceof ToolUseEvent);

        CompletableFuture<JsonObject> future = handler.onRequestPermission(
                params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR)));

        assertFalse(future.isDone());
        assertInstanceOf(ConfirmEvent.class, fired.get(0));
    }

    // ---- Writes are untouched ----
    @Test
    void aMutationIsNeverDecidedAsAReadEvenWhenTheScopeWouldAllowIt() {
        List<AiProcessEvent> fired = new ArrayList<>();
        AtomicInteger consulted = new AtomicInteger();
        OpenCodeAcpClientHandler handler = handler(fired, p -> false, p -> {
                                               consulted.incrementAndGet();
                                               return true;
                                           });
        JsonObject content = new JsonObject();
        content.addProperty("type", "diff");
        content.addProperty("path", "/proj/Foo.java");
        content.addProperty("oldText", "a\n");
        content.addProperty("newText", "b\n");
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("kind", "edit");
        toolCall.addProperty("title", "/proj/Foo.java");
        toolCall.add("locations", locations("/proj/Foo.java"));
        toolCall.add("content", contentArray);

        handler.onRequestPermission(params(toolCall));

        assertEquals(1, fired.size());
        PermissionEvent pe = assertInstanceOf(PermissionEvent.class, fired.get(0));
        assertEquals("Write", pe.toolName());
        assertEquals("b\n", pe.writeContent());
        assertEquals(0, consulted.get());
    }

    @Test
    void aChangeProposedOnAnOtherKindRequestFromAReadToolCallIsStillAMutation() {
        List<AiProcessEvent> fired = new ArrayList<>();
        AtomicInteger consulted = new AtomicInteger();
        OpenCodeAcpClientHandler handler = handler(fired, p -> false, p -> {
                                               consulted.incrementAndGet();
                                               return true;
                                           });
        announceToolCall(handler, "call-r", "read", "pending");
        fired.removeIf(e -> e instanceof ToolUseEvent);
        JsonObject toolCall = externalDirectory("call-r", "/proj/Foo.java", "/proj");
        toolCall.getAsJsonObject("rawInput").addProperty("diff", "@@ -1 +1 @@\n-a\n+b\n");

        handler.onRequestPermission(params(toolCall));

        assertInstanceOf(PermissionEvent.class, fired.get(0), "evidence of a change outranks everything else");
        assertEquals(0, consulted.get());
    }

    // ---- A read never enters the approval gate, so auto-accept plays no part in it ----
    /**
     * Stands in for the UI end of the approval gate (AiTopComponent): every approval-type event that reaches it is
     * recorded and counts as the gate having consulted the auto-accept setting, and with auto-accept on it answers
     * automatically exactly as the real gate does for an event that does not demand explicit approval. If a read
     * touched the gate at all, this would see it.
     */
    private static final class FakeApprovalGate implements AiProcessEventListener {

        private final boolean autoAccept;
        private final List<AiProcessEvent> approvalEvents = new ArrayList<>();
        private final AtomicInteger autoAcceptConsulted = new AtomicInteger();

        FakeApprovalGate(boolean autoAccept) {
            this.autoAccept = autoAccept;
        }

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            if (event instanceof ConfirmEvent ce) {
                approvalEvents.add(event);
                autoAcceptConsulted.incrementAndGet();
                if (autoAccept && !ce.requireExplicitApproval()) {
                    ce.response().complete(PermissionDecision.allowed());
                }
            }
            else if (event instanceof PermissionEvent pe) {
                approvalEvents.add(event);
                autoAcceptConsulted.incrementAndGet();
                if (autoAccept) {
                    pe.response().complete(PermissionDecision.allowed());
                }
            }
        }
    }

    private static CompletableFuture<JsonObject> requestReadThrough(FakeApprovalGate gate, boolean inScope) {
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(gate, () -> {
                                                                }, null,
                                                                        new OpenCodeAcpClientHandler.SessionFileScope(p -> false, p -> inScope));
        announceToolCall(handler, "call-r", "read", "pending");
        return handler.onRequestPermission(params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR)));
    }

    @Test
    void aReadIsDecidedIdenticallyWithAutoAcceptOnAndOffAndNeverTouchesTheApprovalGate() throws Exception {
        for (boolean inScope : new boolean[]{true, false}) {
            String expected = inScope ? "once" : "reject";
            for (boolean autoAccept : new boolean[]{true, false}) {
                FakeApprovalGate gate = new FakeApprovalGate(autoAccept);

                CompletableFuture<JsonObject> future = requestReadThrough(gate, inScope);

                String where = "inScope=" + inScope + ", autoAccept=" + autoAccept;
                assertTrue(future.isDone(), "answered without waiting on anyone: " + where);
                assertEquals(expected, outcome(future.get()), "the outcome is the scope's alone: " + where);
                assertTrue(gate.approvalEvents.isEmpty(), "no approval event may be raised: " + where);
                assertEquals(0, gate.autoAcceptConsulted.get(),
                             "the auto-accept setting must never be consulted for a read: " + where);
            }
        }
    }

    @Test
    void theHarnessDoesSeeTheApprovalGateWhenARequestGenuinelyEntersIt() throws Exception {
        // Control for the test above: an ask that is NOT confidently a read goes through the gate, and there the
        // auto-accept setting DOES change the outcome. If this harness could not tell, the four-case test would prove
        // nothing.
        for (boolean autoAccept : new boolean[]{true, false}) {
            FakeApprovalGate gate = new FakeApprovalGate(autoAccept);
            OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(gate, () -> {
                                                                    }, null,
                                                                            new OpenCodeAcpClientHandler.SessionFileScope(p -> false, p -> true));

            // Never announced as a read, so it cannot be traced to one.
            CompletableFuture<JsonObject> future = handler.onRequestPermission(
                    params(externalDirectory("call-unknown", OUTSIDE_FILE, OUTSIDE_DIR)));

            assertEquals(1, gate.approvalEvents.size(), "autoAccept=" + autoAccept);
            assertEquals(1, gate.autoAcceptConsulted.get());
            assertEquals(autoAccept, future.isDone(),
                         "auto-accept answers it; with auto-accept off it waits for the user");
        }
    }

    // ---- The refusal reason in the log is GetFileContent's own ----
    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    /**
     * Runs {@code action} with the handler's logger captured and the JSON debug setting forced, restoring both, and
     * returns every message the handler logged, formatted.
     */
    private static List<String> handlerLogDuring(boolean debugJson, ThrowingRunnable action) throws Exception {
        Logger logger = Logger.getLogger(OpenCodeAcpClientHandler.class.getName());
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(new SimpleFormatter().formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Level previousLevel = logger.getLevel();
        boolean previousDebug = PluginSettings.isDebugJson();
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        PluginSettings.setDebugJson(debugJson);
        try {
            action.run();
        }
        finally {
            PluginSettings.setDebugJson(previousDebug);
            logger.setLevel(previousLevel);
            logger.removeHandler(capture);
        }
        return messages;
    }

    private static String readRequestLine(List<String> messages) {
        return messages.stream().filter(m -> m.startsWith("OpenCode read request")).findFirst().orElse(null);
    }

    @Test
    void theRefusalReasonInTheLogIsTheOneGetFileContentReturnsForThatPath(@TempDir Path projectDir) throws Exception {
        String sessionId = "read-policy-" + UUID.randomUUID();
        Path history = new SessionPersistenceManager().historyPath(sessionId);
        McpHookServer server = new McpHookServer(0);
        server.init();
        try {
            server.registerSession(sessionId, AiTypeEnum.OPENCODE, List.<File>of(projectDir.toFile()), true);
            OpenCodeAcpClientHandler.SessionFileScope scope = OpenCodeAcpClientHandler.sessionFileScope(() -> server, sessionId);

            // The source itself: identical to what GetFileContentTool.handle returns for the same path, and not one fixed
            // string — the conversation-history tree has its own wording.
            String outsideReason = McpHookServer.fileAccessDeniedMessage(server, sessionId, OUTSIDE_FILE);
            String historyReason = McpHookServer.fileAccessDeniedMessage(server, sessionId, history.toString());
            assertEquals(outsideReason, scope.refusalReason(OUTSIDE_FILE));
            assertEquals(historyReason, scope.refusalReason(history.toString()));
            assertFalse(outsideReason.equals(historyReason), "the shared builder gives a path-specific reason");

            for (String[] refused : new String[][]{{OUTSIDE_FILE, outsideReason}, {history.toString(), historyReason}}) {
                List<AiProcessEvent> fired = new ArrayList<>();
                OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
                                                                        }, null, scope);
                announceToolCall(handler, "call-r", "read", "pending");
                fired.removeIf(e -> e instanceof ToolUseEvent);
                JsonObject[] reply = new JsonObject[1];

                List<String> log = handlerLogDuring(true, () -> reply[0] = handler.onRequestPermission(
                                                    params(externalDirectory("call-r", refused[0], OUTSIDE_DIR))).get());

                assertEquals("reject", outcome(reply[0]), "the outcome we return is unchanged");
                assertNothingRaised(fired);
                String line = readRequestLine(log);
                assertTrue(line != null && line.contains("denied"), "the refusal is logged: " + log);
                assertTrue(line.contains(refused[1]),
                           "the logged reason must be the shared source's text for that path: " + line);
            }
        }
        finally {
            server.stop();
        }
    }

    @Test
    void aFailingReasonSourceStillRefusesCleanlyAndLogsThePlainLine() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
                                                                }, null,
                                                                        new OpenCodeAcpClientHandler.SessionFileScope(p -> false, p -> false, p -> {
                                                                                                                  throw new IllegalStateException("reason unavailable");
                                                                                                              }));
        announceToolCall(handler, "call-r", "read", "pending");
        fired.removeIf(e -> e instanceof ToolUseEvent);
        JsonObject[] reply = new JsonObject[1];

        List<String> log = handlerLogDuring(true, () -> reply[0] = handler.onRequestPermission(
                                            params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR))).get());

        assertEquals("reject", outcome(reply[0]), "building the reason must never break the refusal");
        assertNothingRaised(fired);
        String line = readRequestLine(log);
        assertTrue(line != null && line.contains("denied"), "the plain line is still logged: " + log);
        assertFalse(line.contains("Reason:"), "no reason is appended when it cannot be built: " + line);
    }

    @Test
    void anAllowedReadLogsNoReasonAndNothingIsLoggedWithoutDebugJson() throws Exception {
        AtomicInteger reasonsBuilt = new AtomicInteger();
        for (boolean debug : new boolean[]{true, false}) {
            for (boolean inScope : new boolean[]{true, false}) {
                List<AiProcessEvent> fired = new ArrayList<>();
                OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
                                                                        }, null,
                                                                                new OpenCodeAcpClientHandler.SessionFileScope(p -> false, p -> inScope, p -> {
                                                                                                                          reasonsBuilt.incrementAndGet();
                                                                                                                          return "the reason";
                                                                                                                      }));
                announceToolCall(handler, "call-r", "read", "pending");
                fired.removeIf(e -> e instanceof ToolUseEvent);
                JsonObject[] reply = new JsonObject[1];

                List<String> log = handlerLogDuring(debug, () -> reply[0] = handler.onRequestPermission(
                                                    params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR))).get());

                assertEquals(inScope ? "once" : "reject", outcome(reply[0]), "the outcome never depends on logging");
                String line = readRequestLine(log);
                if (!debug) {
                    assertNull(line, "a routine policy decision is only logged under the JSON-debug setting: " + log);
                }
                else if (inScope) {
                    assertTrue(line != null && line.contains("allowed") && !line.contains("Reason:"), String.valueOf(line));
                }
                else {
                    assertTrue(line != null && line.contains("Reason: the reason"), String.valueOf(line));
                }
            }
        }
        // Built once per REFUSAL (two of the four cases), never for an allowed read, and shared by the log line and the
        // end-of-turn notice. It used to be built only when the line would be written; the notice needs it always.
        assertEquals(2, reasonsBuilt.get(),
                     "the reason is built once per refusal, whether or not it is logged, and never for an allowed read");
    }

    // ---- Refusals are remembered for the end of the turn, verbatim ----
    private static OpenCodeAcpClientHandler handlerWithReason(List<AiProcessEvent> fired, boolean allowed,
                                                              java.util.function.Function<String, String> reason) {
        return new OpenCodeAcpClientHandler(fired::add, () -> {
                                    }, null, new OpenCodeAcpClientHandler.SessionFileScope(p -> false, p -> allowed, reason));
    }

    private static void requestRead(OpenCodeAcpClientHandler handler, String callId, String file) throws Exception {
        announceToolCall(handler, callId, "read", "pending");
        handler.onRequestPermission(params(externalDirectory(callId, file, OUTSIDE_DIR))).get();
    }

    @Test
    void aRefusedReadIsRememberedWithTheSharedTextAndAnAllowedReadRemembersNothing() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler refusing = handlerWithReason(fired, false, p -> "Access denied: " + p + " (shared text)");
        requestRead(refusing, "call-1", OUTSIDE_FILE);

        assertEquals(List.of(new Refusal(OUTSIDE_FILE, "Access denied: " + OUTSIDE_FILE + " (shared text)")),
                     refusing.consumeTurnRefusals(), "the path is recorded with the text, not left to the text to echo");

        OpenCodeAcpClientHandler allowing = handlerWithReason(fired, true, p -> "never used");
        requestRead(allowing, "call-2", OUTSIDE_FILE);

        assertTrue(allowing.consumeTurnRefusals().isEmpty(), "an allowed read is nothing to report");
    }

    @Test
    void consumingTakesAndForgetsSoARefusalIsNeverReportedOnALaterTurn() throws Exception {
        OpenCodeAcpClientHandler handler = handlerWithReason(new ArrayList<>(), false, p -> "refused " + p);
        requestRead(handler, "call-1", OUTSIDE_FILE);

        assertEquals(1, handler.consumeTurnRefusals().size());
        assertTrue(handler.consumeTurnRefusals().isEmpty(), "taken once, gone");

        requestRead(handler, "call-2", OUTSIDE_FILE + ".2");
        handler.clearTurnRefusals();
        assertTrue(handler.consumeTurnRefusals().isEmpty(), "a new turn starts with none");
    }

    @Test
    void theSameRefusalIsKeptOnceAndTheListIsBounded() throws Exception {
        OpenCodeAcpClientHandler handler = handlerWithReason(new ArrayList<>(), false, p -> "refused " + p);
        for (int i = 0; i < 3; i++) {
            requestRead(handler, "same-" + i, OUTSIDE_FILE);
        }
        assertEquals(List.of(new Refusal(OUTSIDE_FILE, "refused " + OUTSIDE_FILE)), handler.consumeTurnRefusals(),
                     "a repeat of the same refusal collapses into one entry");

        for (int i = 0; i < 20; i++) {
            requestRead(handler, "distinct-" + i, OUTSIDE_FILE + "." + i);
        }
        List<Refusal> kept = handler.consumeTurnRefusals();
        assertEquals(8, kept.size(), "a burst cannot grow the notice without limit");
        assertEquals(new Refusal(OUTSIDE_FILE + ".0", "refused " + OUTSIDE_FILE + ".0"), kept.get(0),
                     "the first refusals are the ones kept, in order");
    }

    @Test
    void aRefusalWhoseTextCannotBeBuiltIsStillRefusedButHasNothingToReport() throws Exception {
        OpenCodeAcpClientHandler failing = handlerWithReason(new ArrayList<>(), false, p -> {
                                                         throw new IllegalStateException("reason unavailable");
                                                     });
        OpenCodeAcpClientHandler blank = handlerWithReason(new ArrayList<>(), false, p -> "  ");
        OpenCodeAcpClientHandler none = new OpenCodeAcpClientHandler(new ArrayList<AiProcessEvent>()::add, () -> {
                                                             }, null, new OpenCodeAcpClientHandler.SessionFileScope(p -> false, p -> false));

        for (OpenCodeAcpClientHandler handler : List.of(failing, blank, none)) {
            announceToolCall(handler, "call-r", "read", "pending");
            JsonObject reply = handler.onRequestPermission(params(externalDirectory("call-r", OUTSIDE_FILE, OUTSIDE_DIR))).get();

            assertEquals("reject", outcome(reply), "the refusal itself is unaffected");
            assertTrue(handler.consumeTurnRefusals().isEmpty(),
                       "the notice quotes the shared text and has nothing else to say without it");
        }
    }

    @Test
    void aRequestThatIsNotAConfidentReadRecordsNothing() throws Exception {
        OpenCodeAcpClientHandler handler = handlerWithReason(new ArrayList<>(), false, p -> "refused " + p);
        // A command's external_directory ask is not a read: it is put to the user, never refused by the read rule.
        announceToolCall(handler, "call-c", "execute", "pending");
        handler.onRequestPermission(params(externalDirectory("call-c", OUTSIDE_FILE, OUTSIDE_DIR)));

        assertTrue(handler.consumeTurnRefusals().isEmpty());
    }

    /**
     * The parity guarantee: what the notice quotes is EXACTLY what GetFileContent returns for that path, from the same
     * shared builder, and it is path-specific rather than one fixed string. A later change to the refusal wording moves
     * both paths together or fails here.
     */
    @Test
    void theNoticeQuotesExactlyWhatGetFileContentReturnsForThatPath(@TempDir Path projectDir) throws Exception {
        String sessionId = "read-policy-" + UUID.randomUUID();
        Path history = new SessionPersistenceManager().historyPath(sessionId);
        McpHookServer server = new McpHookServer(0);
        server.init();
        try {
            server.registerSession(sessionId, AiTypeEnum.OPENCODE, List.<File>of(projectDir.toFile()), true);
            OpenCodeAcpClientHandler.SessionFileScope scope = OpenCodeAcpClientHandler.sessionFileScope(() -> server, sessionId);
            String outside = McpHookServer.fileAccessDeniedMessage(server, sessionId, OUTSIDE_FILE);
            String historyText = McpHookServer.fileAccessDeniedMessage(server, sessionId, history.toString());
            assertFalse(outside.equals(historyText), "the shared builder gives a path-specific message");

            OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(new ArrayList<AiProcessEvent>()::add, () -> {
                                                                    }, null, scope);
            requestRead(handler, "call-1", OUTSIDE_FILE);
            requestRead(handler, "call-2", history.toString());
            List<Refusal> refusals = handler.consumeTurnRefusals();

            // Two refused paths in one turn: each recorded with ITS path and ITS text, verbatim, in the order refused.
            assertEquals(List.of(new Refusal(OUTSIDE_FILE, outside), new Refusal(history.toString(), historyText)),
                         refusals, "recorded verbatim, one per refused path, in order");
            String[] lines = PolicyRefusalEvent.compose(refusals).split("\n");
            assertEquals(PolicyRefusalEvent.entry(new Refusal(OUTSIDE_FILE, outside)), lines[0]);
            assertEquals(PolicyRefusalEvent.entry(new Refusal(history.toString(), historyText)), lines[1]);
            assertTrue(lines[0].contains(OUTSIDE_FILE) && lines[0].contains(outside), "the first file with its own reason");
            assertTrue(lines[1].contains(history.toString()) && lines[1].contains(historyText),
                       "the second file with its own reason");
            assertFalse(lines[0].contains(historyText) || lines[1].contains(outside), "and the reasons never cross");
        }
        finally {
            server.stop();
        }
    }

    // ---- The rule is GetFileContent's own ----
    private static String pluginConfigTree(String sessionId) throws Exception {
        return PluginUtil.getPluginConfigDir().resolve(AiTypeEnum.OPENCODE.key()).resolve(sessionId).toString();
    }

    @Test
    void theHandlersDecisionIsExactlyWhatGetFileContentAsksTheServer(@TempDir Path projectDir, @TempDir Path elsewhere)
            throws Exception {
        String sessionId = "read-policy-" + UUID.randomUUID();
        Path inside = Files.writeString(projectDir.resolve("Inside.java"), "class Inside {}");
        Path outside = Files.writeString(elsewhere.resolve("Outside.txt"), "outside");
        Path history = new SessionPersistenceManager().historyPath(sessionId);
        String ownSpool = pluginConfigTree(sessionId) + "/tmp/tool_results/git-diff-x.log";
        List<String> paths = List.of(inside.toString(), outside.toString(), ownSpool, history.toString(),
                                     "/etc/hostname", projectDir.toString());

        McpHookServer server = new McpHookServer(0);
        server.init();
        try {
            for (boolean restrict : new boolean[]{true, false}) {
                server.registerSession(sessionId, AiTypeEnum.OPENCODE, List.<File>of(projectDir.toFile()), restrict);
                OpenCodeAcpClientHandler.SessionFileScope scope
                        = OpenCodeAcpClientHandler.sessionFileScope(() -> server, sessionId);
                for (String path : paths) {
                    // GetFileContentTool.handle serves the file iff server.isFileAccessible(sessionId, path).
                    assertEquals(server.isFileAccessible(sessionId, path), scope.isReadAllowed(path),
                                 "must agree with GetFileContent for " + path + " (restrict=" + restrict + ")");
                }
            }
        }
        finally {
            server.stop();
        }
    }

    @Test
    void theRestrictToProjectSettingDecidesWhetherAnExternalReadIsAllowed(@TempDir Path projectDir) throws Exception {
        String sessionId = "read-policy-" + UUID.randomUUID();
        Path history = new SessionPersistenceManager().historyPath(sessionId);
        String ownSpool = pluginConfigTree(sessionId) + "/tmp/tool_results/git-diff-x.log";

        McpHookServer server = new McpHookServer(0);
        server.init();
        try {
            OpenCodeAcpClientHandler.SessionFileScope scope
                    = OpenCodeAcpClientHandler.sessionFileScope(() -> server, sessionId);

            server.registerSession(sessionId, AiTypeEnum.OPENCODE, List.<File>of(projectDir.toFile()), true);
            assertEquals("reject", readOf(scope, OUTSIDE_FILE, OUTSIDE_DIR),
                         "restrict ON: a file outside the project is refused, as GetFileContent refuses it");
            assertEquals("once", readOf(scope, projectDir.resolve("A.java").toString(), projectDir.toString()),
                         "restrict ON: a project file is still readable");
            assertEquals("once", readOf(scope, ownSpool, pluginConfigTree(sessionId) + "/tmp/tool_results"),
                         "restrict ON: the session's own tree is readable — the shared rule already covers what the own-tree "
                         + "exemption used to");
            assertEquals("reject", readOf(scope, history.toString(), history.getParent().toString()),
                         "the conversation-history tree is vetoed for reads whatever the setting");

            server.registerSession(sessionId, AiTypeEnum.OPENCODE, List.<File>of(projectDir.toFile()), false);
            assertEquals("once", readOf(scope, OUTSIDE_FILE, OUTSIDE_DIR),
                         "restrict OFF: an external file is readable, as GetFileContent reads it");
            assertEquals("reject", readOf(scope, history.toString(), history.getParent().toString()),
                         "restrict OFF does not lift the conversation-history veto");
        }
        finally {
            server.stop();
        }
    }

    private static String readOf(OpenCodeAcpClientHandler.SessionFileScope scope, String file, String dir) throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
                                                                }, null, scope);
        announceToolCall(handler, "call-r", "read", "pending");
        fired.removeIf(e -> e instanceof ToolUseEvent);
        CompletableFuture<JsonObject> future = handler.onRequestPermission(params(externalDirectory("call-r", file, dir)));
        assertNothingRaised(fired);
        return outcome(future.get());
    }
}
