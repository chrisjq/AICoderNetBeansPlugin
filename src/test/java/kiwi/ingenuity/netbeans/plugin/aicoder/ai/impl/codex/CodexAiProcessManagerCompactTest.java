package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Codex compaction through the shared busy/ready contract: {@code thread/compact/start} answers {@code {}}
 * and the compaction then runs as a turn whose notifications must stay away from the UI.
 */
class CodexAiProcessManagerCompactTest {

    private static final String THREAD = "fake-thread-id";
    private static final String COMPACT_TURN = "compact-turn";
    /**
     * Sentinel notification: answer the compact request with a JSON-RPC error instead of {@code {}}.
     */
    private static final String REQUEST_FAILS = "@REQUEST_FAILS";
    /**
     * Sentinel prefix: the script pauses until the file named after it exists.
     */
    private static final String WAIT_FOR = "@WAIT_FOR ";
    private static final String HANDSHAKE_SCRIPT = "#!/bin/sh\n"
                                                   + "read -r _req1\n"
                                                   + "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"userAgent\":\"fake\",\"codexHome\":\"/tmp\"}}\\n'\n"
                                                   + "read -r _notif\n"
                                                   + "read -r _req2\n"
                                                   + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"thread\":{\"id\":\"" + THREAD + "\"},\"model\":\"fake-model\"}}\\n'\n"
                                                   + "read -r _req3\n"
                                                   + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"data\":[]}}\\n'\n";

    @BeforeEach
    void useEphemeralMcpPort() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
    }

    @AfterEach
    void releaseMcpPort() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    private static String notification(String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("method", method);
        o.add("params", params);
        return o.toString();
    }

    private static String turnNotification(String method, String turnId, String status, String errorMessage) {
        JsonObject turn = new JsonObject();
        turn.addProperty("id", turnId);
        turn.add("items", new JsonArray());
        turn.addProperty("status", status);
        if (errorMessage != null) {
            JsonObject error = new JsonObject();
            error.addProperty("message", errorMessage);
            turn.add("error", error);
        }
        JsonObject params = new JsonObject();
        params.addProperty("threadId", THREAD);
        params.add("turn", turn);
        return notification(method, params);
    }

    private static String turnStarted(String turnId) {
        return turnNotification(CodexAppServerHandler.METHOD_TURN_STARTED, turnId, "inProgress", null);
    }

    private static String turnCompleted(String turnId, String status, String errorMessage) {
        return turnNotification(CodexAppServerHandler.METHOD_TURN_COMPLETED, turnId, status, errorMessage);
    }

    private static String agentDelta(String turnId, String text) {
        JsonObject params = new JsonObject();
        params.addProperty("threadId", THREAD);
        params.addProperty("turnId", turnId);
        params.addProperty("itemId", "msg-1");
        params.addProperty("delta", text);
        return notification(CodexAppServerHandler.METHOD_AGENT_MESSAGE_DELTA, params);
    }

    private static String compactionItemStarted(String turnId) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "contextCompaction");
        item.addProperty("id", "item-1");
        JsonObject params = new JsonObject();
        params.addProperty("threadId", THREAD);
        params.addProperty("turnId", turnId);
        params.add("item", item);
        return notification(CodexAppServerHandler.METHOD_ITEM_STARTED, params);
    }

    private static String tokenUsage(String turnId, long used, long window) {
        JsonObject last = new JsonObject();
        last.addProperty("totalTokens", used);
        JsonObject usage = new JsonObject();
        usage.addProperty("modelContextWindow", window);
        usage.add("last", last);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", THREAD);
        params.addProperty("turnId", turnId);
        params.add("tokenUsage", usage);
        return notification(CodexAppServerHandler.METHOD_THREAD_TOKEN_USAGE, params);
    }

    /**
     * Handshake (ids 1-3), then insists the next request is {@code thread/compact/start} (id 4, exit 3
     * otherwise), answers it {@code {}}, emits {@code notifications} and either exits (code 1) or waits for
     * one more request, {@code touch}ing {@code extraRequestMarker} when it arrives.
     */
    private static File fakeCodexCompaction(File extraRequestMarker, boolean exitAfterNotifications,
                                            String... notifications) throws IOException {
        File script = File.createTempFile("fake-codex-compact-", ".sh");
        script.deleteOnExit();
        StringBuilder body = new StringBuilder(HANDSHAKE_SCRIPT
                                               + "read -r _req4\n"
                                               + "case \"$_req4\" in *thread/compact/start*) ;; *) exit 3 ;; esac\n");
        boolean requestFails = List.of(notifications).contains(REQUEST_FAILS);
        body.append(requestFails
                    ? "printf '{\"jsonrpc\":\"2.0\",\"id\":4,\"error\":{\"code\":-32000,\"message\":\"compact refused\"}}\\n'\n"
                    : "printf '{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}\\n'\n");
        for (String n : notifications) {
            if (n.equals(REQUEST_FAILS)) {
                continue;
            }
            if (n.startsWith(WAIT_FOR)) {
                body.append("while [ ! -f '").append(n.substring(WAIT_FOR.length())).append("' ]; do sleep 0.05; done\n");
            }
            else {
                body.append("printf '%s\\n' '").append(n).append("'\n");
            }
        }
        if (exitAfterNotifications) {
            body.append("exit 1\n");
        }
        else {
            body.append("read -r _req5\n")
                    .append("touch '").append(extraRequestMarker.getAbsolutePath()).append("'\n")
                    .append("sleep 5\n");
        }
        Files.writeString(script.toPath(), body.toString(), StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    /**
     * Handshake, then swallows the next request (the {@code turn/start}) and never answers, so the turn stays
     * running.
     */
    private static File fakeCodexTurnNeverEnds() throws IOException {
        File script = File.createTempFile("fake-codex-turn-", ".sh");
        script.deleteOnExit();
        Files.writeString(script.toPath(), HANDSHAKE_SCRIPT + "read -r _req4\nsleep 5\n", StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    /**
     * Handshake, then reads the {@code turn/start} and dies with code 1 without answering it.
     */
    private static File fakeCodexCrashesOnTurnStart() throws IOException {
        File script = File.createTempFile("fake-codex-crash-", ".sh");
        script.deleteOnExit();
        Files.writeString(script.toPath(), HANDSHAKE_SCRIPT + "read -r _req4\nexit 1\n", StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    /**
     * Handshake, accepts {@code thread/compact/start} ({@code {}}), then closes its stdout so the client
     * loses the stream while the compaction is in flight, and carries on with {@code tail} (a shell
     * fragment).
     */
    private static File fakeCodexDropsTheStreamAfterCompactIsAccepted(String tail) throws IOException {
        File script = File.createTempFile("fake-codex-drop-", ".sh");
        script.deleteOnExit();
        Files.writeString(script.toPath(), HANDSHAKE_SCRIPT
                                           + "read -r _req4\n"
                                           + "printf '{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}\\n'\n"
                                           + "exec >&-\n"
                                           + tail,
                StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    /**
     * Handshake, swallows the {@code turn/start}, then once {@code go} exists reports that turn as started
     * and interrupted (the late notifications of a turn the user already stopped) and touches
     * {@code nextRequest} when one more request arrives.
     */
    private static File fakeCodexLateStoppedTurn(File go, File nextRequest) throws IOException {
        File script = File.createTempFile("fake-codex-late-", ".sh");
        script.deleteOnExit();
        Files.writeString(script.toPath(), HANDSHAKE_SCRIPT
                                           + "read -r _req4\n"
                                           + "while [ ! -f '" + go.getAbsolutePath() + "' ]; do sleep 0.05; done\n"
                                           + "printf '%s\\n' '" + turnStarted("stopped-turn") + "'\n"
                                           + "printf '%s\\n' '" + turnCompleted("stopped-turn", "interrupted", null) + "'\n"
                                           + "read -r _req5\n"
                                           + "touch '" + nextRequest.getAbsolutePath() + "'\n"
                                           + "sleep 5\n",
                StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    private static CodexAiProcessManager started(File script, List<AiProcessEvent> events) {
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.setCurrentSession(new AiSession("s1", "Test", null, AiTypeEnum.CODEX, null,
                new CodexSessionSettings(), Instant.now(), Instant.now()));
        manager.start(script.getAbsolutePath(), "fake-model");
        return manager;
    }

    private static File noMarker() throws IOException {
        File marker = File.createTempFile("codex-compact-marker-", "");
        assertTrue(marker.delete());
        return marker;
    }

    private static CodexAiProcessManager connected(File script, List<AiProcessEvent> events) throws Exception {
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.setCurrentSession(new AiSession("s1", "Test", null, AiTypeEnum.CODEX, null,
                new CodexSessionSettings(), Instant.now(), Instant.now()));
        manager.start(script.getAbsolutePath(), "fake-model");
        manager.spawnAndHandshake(new File(System.getProperty("java.io.tmpdir")));
        awaitTrue(() -> manager.threadId() != null, "handshake completed");
        events.clear();
        return manager;
    }

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    private static long statuses(List<AiProcessEvent> events, StatusEventTypeEnum type) {
        return events.stream().filter(e -> e instanceof StatusEvent se && se.type() == type).count();
    }

    private static StatusEvent firstStatus(List<AiProcessEvent> events, StatusEventTypeEnum type) {
        return events.stream().filter(e -> e instanceof StatusEvent se && se.type() == type)
                .map(e -> (StatusEvent) e).findFirst().orElseThrow(() -> new AssertionError("no " + type + " event"));
    }

    private static long count(List<AiProcessEvent> events, Class<?> type) {
        return events.stream().filter(type::isInstance).count();
    }

    // ---- builders ----
    @Test
    void buildThreadCompactStartParamsCarriesTheThreadId() {
        JsonObject params = CodexAiProcessManager.buildThreadCompactStartParams("th_1");
        assertEquals("th_1", params.get("threadId").getAsString());
    }

    @Test
    void notificationTurnIdReadsTheNestedTurnIdAndTheFlatTurnId() {
        JsonObject nested = new JsonObject();
        JsonObject turn = new JsonObject();
        turn.addProperty("id", "t-nested");
        nested.add("turn", turn);
        JsonObject flat = new JsonObject();
        flat.addProperty("turnId", "t-flat");

        assertEquals("t-nested", CodexAiProcessManager.notificationTurnId(nested));
        assertEquals("t-flat", CodexAiProcessManager.notificationTurnId(flat));
        assertNull(CodexAiProcessManager.notificationTurnId(new JsonObject()));
    }

    // ---- the compaction turn stays away from the UI ----
    @Test
    void compactionTurnNeverLeaksAndReadyIsEmittedExactlyOnce() throws Exception {
        File script = fakeCodexCompaction(noMarker(), false,
                turnStarted(COMPACT_TURN),
                agentDelta(COMPACT_TURN, "summary of the conversation"),
                compactionItemStarted(COMPACT_TURN),
                tokenUsage(COMPACT_TURN, 1234, 200000),
                turnCompleted(COMPACT_TURN, "completed", null));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        assertTrue(manager.compact());
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.READY) > 0, "READY after the compaction turn completed");
        Thread.sleep(200);

        assertEquals(1, statuses(events, StatusEventTypeEnum.BUSY), "exactly one BUSY");
        StatusEvent busy = firstStatus(events, StatusEventTypeEnum.BUSY);
        assertEquals("Compacting conversation...", busy.text());
        assertFalse(busy.cancellable(), "codex compaction cannot be cancelled, so Stop must not be offered");
        assertEquals(1, statuses(events, StatusEventTypeEnum.READY), "READY exactly once");
        assertEquals("Conversation compacted", firstStatus(events, StatusEventTypeEnum.READY).text());
        assertEquals(0, statuses(events, StatusEventTypeEnum.FAILED));
        assertEquals(0, statuses(events, StatusEventTypeEnum.THINKING), "the compaction turn's turn/started must not surface as THINKING");
        assertEquals(0, count(events, TurnCompleteEvent.class), "a compaction closes with READY, never TurnComplete");
        assertEquals(0, count(events, TextDeltaEvent.class), "the compaction's summary text must not reach the chat");
        assertTrue(events.indexOf(firstStatus(events, StatusEventTypeEnum.BUSY))
                   < events.indexOf(firstStatus(events, StatusEventTypeEnum.READY)));
        assertFalse(manager.isBusy(), "the manager must be free once READY is out");
        assertFalse(manager.isProcessing(), "a compaction must never look like a user turn");

        manager.stop();
    }

    @Test
    void compactionTurnStillForwardsTokenUsageSoTheGaugeShrinks() throws Exception {
        File script = fakeCodexCompaction(noMarker(), false,
                turnStarted(COMPACT_TURN),
                tokenUsage(COMPACT_TURN, 1234, 200000),
                turnCompleted(COMPACT_TURN, "completed", null));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.READY) > 0, "READY");

        CodexTokenUsageEvent usage = events.stream().filter(CodexTokenUsageEvent.class::isInstance)
                .map(CodexTokenUsageEvent.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError("token usage was swallowed"));
        assertEquals(1234, usage.usedTokens());
        assertEquals(200000, usage.contextWindow());

        manager.stop();
    }

    @Test
    void failedCompactionTurnClosesWithOneFailedAndNoTurnComplete() throws Exception {
        File script = fakeCodexCompaction(noMarker(), false,
                turnStarted(COMPACT_TURN),
                turnCompleted(COMPACT_TURN, "failed", "context too large"));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED");
        Thread.sleep(200);

        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED));
        assertEquals("Compact failed: context too large", firstStatus(events, StatusEventTypeEnum.FAILED).text());
        assertEquals(0, statuses(events, StatusEventTypeEnum.READY));
        assertEquals(0, count(events, TurnCompleteEvent.class));
        assertFalse(manager.isBusy());

        manager.stop();
    }

    @Test
    void interruptedCompactionTurnClosesWithFailedNotReady() throws Exception {
        File script = fakeCodexCompaction(noMarker(), false,
                turnStarted(COMPACT_TURN),
                turnCompleted(COMPACT_TURN, "interrupted", null));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED");
        Thread.sleep(200);

        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED));
        assertTrue(firstStatus(events, StatusEventTypeEnum.FAILED).text().contains("interrupted"));
        assertEquals(0, statuses(events, StatusEventTypeEnum.READY));

        manager.stop();
    }

    @Test
    void turnCompletedOfAnEarlierTurnIsForwardedAndDoesNotCloseTheCompaction() throws Exception {
        File script = fakeCodexCompaction(noMarker(), false,
                turnStarted(COMPACT_TURN),
                turnCompleted("cancelled-earlier-turn", "interrupted", null),
                turnCompleted(COMPACT_TURN, "completed", null));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.READY) > 0, "READY");
        Thread.sleep(200);

        assertEquals(1, count(events, TurnCompleteEvent.class),
                "the earlier turn's completion belongs to the chat and must be forwarded, once");
        assertEquals(1, statuses(events, StatusEventTypeEnum.READY));
        assertEquals(0, statuses(events, StatusEventTypeEnum.FAILED));
        assertTrue(events.indexOf(events.stream().filter(TurnCompleteEvent.class::isInstance).findFirst().orElseThrow())
                   < events.indexOf(firstStatus(events, StatusEventTypeEnum.READY)),
                "the earlier turn's completion must not be what closed the compaction");

        manager.stop();
    }

    // ---- exactly one closing status when the backend goes away ----
    @Test
    void stopMidCompactionClosesWithOneFailed() throws Exception {
        File script = fakeCodexCompaction(noMarker(), false, turnStarted(COMPACT_TURN));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.BUSY) > 0, "BUSY");
        assertTrue(manager.isBusy());

        manager.stop();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED after stop");
        Thread.sleep(300);

        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED), "exactly one closing status");
        assertEquals(0, statuses(events, StatusEventTypeEnum.READY));
        assertFalse(manager.isBusy());
    }

    @Test
    void processExitMidCompactionClosesWithOneFailed() throws Exception {
        File script = fakeCodexCompaction(noMarker(), true, turnStarted(COMPACT_TURN));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);
        clearCancelledByUser(manager);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED after the process exited");
        Thread.sleep(500);

        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED), "exit and disconnect must close the work once between them");
        assertEquals(0, statuses(events, StatusEventTypeEnum.EXITED),
                "the fake exits with code 1; a second EXITED closer after the FAILED would be a double close: " + events);
        assertEquals(0, statuses(events, StatusEventTypeEnum.READY));
        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED) + statuses(events, StatusEventTypeEnum.EXITED)
                        + statuses(events, StatusEventTypeEnum.READY), "exactly one closing status in total: " + events);
        assertFalse(manager.isBusy());

        manager.stop();
    }

    /**
     * start() goes through stop(), which sets cancelledByUser and would make handleProcessExit suppress
     * EXITED.
     */
    private static void clearCancelledByUser(CodexAiProcessManager manager) throws Exception {
        var field = CodexAiProcessManager.class.getSuperclass().getDeclaredField("cancelledByUser");
        field.setAccessible(true);
        field.setBoolean(manager, false);
    }

    @Test
    void crashMidTurnAfterAPromptIsReportedAsExited() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = started(fakeCodexCrashesOnTurnStart(), events);

        manager.sendPrompt("go", new File(System.getProperty("java.io.tmpdir")), List.of());
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.EXITED) > 0,
                "EXITED after the process died mid-turn: " + events);
        Thread.sleep(300);

        assertEquals(1, statuses(events, StatusEventTypeEnum.EXITED), "exactly one EXITED: " + events);

        manager.stop();
    }

    @Test
    void disconnectThenExitClosesTheCompactionWithOneFailedAndNoExited() throws Exception {
        File script = fakeCodexDropsTheStreamAfterCompactIsAccepted("sleep 0.5\nexit 1\n");
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);
        clearCancelledByUser(manager);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED from losing the stream");
        assertTrue(firstStatus(events, StatusEventTypeEnum.FAILED).text().contains("disconnected"),
                "the disconnect, not the later exit, closed the work: " + events);
        Thread.sleep(1000);

        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED), "exactly one FAILED: " + events);
        assertEquals(0, statuses(events, StatusEventTypeEnum.EXITED),
                "the process exit after a disconnect that already closed the work must not close it again: " + events);
        assertEquals(0, statuses(events, StatusEventTypeEnum.READY));
        assertFalse(manager.isBusy());

        manager.stop();
    }

    @Test
    void aDisconnectClosureOfAnEarlierProcessDoesNotSuppressALaterCrash() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(fakeCodexDropsTheStreamAfterCompactIsAccepted("sleep 5\n"), events);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED from losing the stream");

        // Restart before the first process's exit callback runs: that callback finds itself superseded and returns.
        manager.start(fakeCodexCrashesOnTurnStart().getAbsolutePath(), "fake-model");
        events.clear();
        manager.sendPrompt("go", new File(System.getProperty("java.io.tmpdir")), List.of());
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.EXITED) > 0,
                "EXITED for the second process's crash, which the first process's disconnect must not swallow: " + events);
        Thread.sleep(300);

        assertEquals(1, statuses(events, StatusEventTypeEnum.EXITED), "exactly one EXITED: " + events);

        manager.stop();
    }

    @Test
    void compactAfterStoppingATurnThatHasNotReportedYetIsRefusedAndItsLateTurnIsNotSwallowed() throws Exception {
        File go = noMarker();
        File nextRequest = noMarker();
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(fakeCodexLateStoppedTurn(go, nextRequest), events);

        manager.sendPrompt("first", new File(System.getProperty("java.io.tmpdir")), List.of());
        assertTrue(manager.isProcessing(), "the turn started, but the app-server has not reported it yet");
        manager.interrupt(InterruptTypeEnum.Cancel);
        assertFalse(manager.isProcessing(), "Stop unlocks the UI at once");
        events.clear();

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED, the compaction being refused");
        assertTrue(firstStatus(events, StatusEventTypeEnum.FAILED).text().contains("Wait for Codex"),
                "the refusal says why: " + events);
        assertFalse(manager.isBusy(), "the refusal closed the work it opened");

        assertTrue(go.createNewFile());
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.THINKING) > 0,
                "the stopped turn's late turn/started reaching the UI; a compaction must not have claimed it");
        Thread.sleep(300);
        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED), "no second closer from the late turn: " + events);
        assertEquals(0, statuses(events, StatusEventTypeEnum.READY));

        assertTrue(manager.compact(), "the stopped turn has completed, so compaction is possible again");
        Thread.sleep(800);
        assertTrue(nextRequest.exists(),
                "thread/compact/start reaching the app-server once the stopped turn completed: " + events);

        manager.stop();
    }

    @Test
    void compactButtonAfterStoppingATurnThatHasNotReportedYetSaysWaitWithInfoOnly() throws Exception {
        File go = noMarker();
        File nextRequest = noMarker();
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        var impl = new CodexAiImplementation(events::add, null) {
            CodexAiProcessManager exposedDelegate() {
                return delegate();
            }
        };
        CodexAiProcessManager manager = impl.exposedDelegate();
        manager.setCurrentSession(new AiSession("s1", "Test", null, AiTypeEnum.CODEX, null,
                new CodexSessionSettings(), Instant.now(), Instant.now()));
        manager.start(fakeCodexLateStoppedTurn(go, nextRequest).getAbsolutePath(), "fake-model");
        manager.spawnAndHandshake(new File(System.getProperty("java.io.tmpdir")));
        awaitTrue(() -> manager.threadId() != null, "handshake completed");

        manager.sendPrompt("first", new File(System.getProperty("java.io.tmpdir")), List.of());
        manager.interrupt(InterruptTypeEnum.Cancel);
        assertFalse(manager.isProcessing(), "Stop unlocks the UI at once");
        events.clear();

        impl.compact();

        assertEquals(1, events.size(), "the refusal is exactly one INFO: " + events);
        StatusEvent info = firstStatus(events, StatusEventTypeEnum.INFO);
        assertTrue(info.text().contains("Wait for Codex"), "the refusal says why: " + info.text());
        assertEquals(0, statuses(events, StatusEventTypeEnum.BUSY), "no BUSY: the tab must not turn red for a wait: " + events);
        assertEquals(0, statuses(events, StatusEventTypeEnum.FAILED), "no FAILED: " + events);
        assertFalse(manager.isBusy());

        assertTrue(go.createNewFile());
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.THINKING) > 0, "the stopped turn's late turn/started");
        awaitTrue(() -> count(events, TurnCompleteEvent.class) > 0, "the stopped turn's turn/completed");
        events.clear();

        impl.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.BUSY) > 0, "BUSY once the stopped turn has completed");
        Thread.sleep(500);
        assertTrue(nextRequest.exists(), "thread/compact/start reaching the app-server: " + events);

        manager.stop();
    }

    // ---- guards ----
    @Test
    void secondCompactWhileOneIsRunningIsRefusedWithoutASecondBusy() throws Exception {
        File script = fakeCodexCompaction(noMarker(), false, turnStarted(COMPACT_TURN));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        assertTrue(manager.compact());
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.BUSY) > 0, "BUSY");

        assertFalse(manager.compact(), "a second compaction must be refused while one is in flight");
        assertEquals(1, statuses(events, StatusEventTypeEnum.BUSY));

        manager.stop();
    }

    @Test
    void compactWithoutAnActiveSessionClosesWithFailedNotStuckBusy() {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);

        manager.compact();

        assertEquals(1, statuses(events, StatusEventTypeEnum.BUSY));
        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED));
        assertTrue(firstStatus(events, StatusEventTypeEnum.FAILED).text().contains("Codex session is not active"));
        assertFalse(manager.isBusy());
    }

    @Test
    void sendPromptIsRefusedWhileACompactionIsRunning() throws Exception {
        File marker = noMarker();
        File script = fakeCodexCompaction(marker, false, turnStarted(COMPACT_TURN));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.BUSY) > 0, "BUSY");

        manager.sendPrompt("hello", new File(System.getProperty("java.io.tmpdir")), List.of());
        Thread.sleep(500);

        assertFalse(marker.exists(), "no turn/start may reach the app-server while the compaction is running");
        assertFalse(manager.isProcessing());
        List<AiProcessEvent> refusal = events.stream()
                .filter(e -> !(e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.BUSY))
                .toList();
        assertEquals(2, refusal.size(), "a refusal is exactly INFO then TurnComplete: " + refusal);
        assertTrue(refusal.get(0) instanceof StatusEvent info && info.type() == StatusEventTypeEnum.INFO
                   && !info.text().isBlank(), "first the INFO carrying the reason: " + refusal.get(0));
        assertTrue(refusal.get(1) instanceof TurnCompleteEvent, "then the TurnCompleteEvent closing the turn the UI opened");
        assertEquals(1, statuses(events, StatusEventTypeEnum.INFO));
        assertEquals(1, count(events, TurnCompleteEvent.class));
        assertEquals(0, statuses(events, StatusEventTypeEnum.READY), "the refusal must not close the compaction's work");
        assertTrue(manager.isBusy(), "the compaction is still in flight after the refusal");

        manager.stop();
    }

    @Test
    void sendPromptWhileATurnIsRunningEmitsInfoThenTurnCompleteOnceEach() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(fakeCodexTurnNeverEnds(), events);
        File dir = new File(System.getProperty("java.io.tmpdir"));

        manager.sendPrompt("first", dir, List.of());
        assertTrue(manager.isProcessing(), "the first prompt started a turn");
        events.clear();

        manager.sendPrompt("second", dir, List.of());

        assertEquals(2, events.size(), "a refusal is exactly INFO then TurnComplete: " + events);
        assertTrue(events.get(0) instanceof StatusEvent info && info.type() == StatusEventTypeEnum.INFO
                   && !info.text().isBlank(), "first the INFO carrying the reason: " + events.get(0));
        assertTrue(events.get(1) instanceof TurnCompleteEvent,
                "the UI only sends once its previous turn closed, so a backend still processing is unwinding a"
                + " turn the UI already closed; without this closer the tab stays locked");
        assertTrue(manager.isProcessing(), "the running turn is untouched");

        manager.stop();
    }

    @Test
    void sendPromptWithADiffPendingEmitsInfoThenTurnCompleteOnceEach() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(fakeCodexTurnNeverEnds(), events);
        manager.setPendingDiff(true);

        manager.sendPrompt("hello", new File(System.getProperty("java.io.tmpdir")), List.of());

        assertEquals(2, events.size(), "a refusal is exactly INFO then TurnComplete: " + events);
        assertTrue(events.get(0) instanceof StatusEvent info && info.type() == StatusEventTypeEnum.INFO
                   && !info.text().isBlank(), "first the INFO carrying the reason: " + events.get(0));
        assertTrue(events.get(1) instanceof TurnCompleteEvent, "then the TurnCompleteEvent closing the UI's turn");
        assertFalse(manager.isProcessing(), "no turn was started");

        manager.stop();
    }

    // ---- a compaction that outlives its work must still be swallowed ----
    @Test
    void lateCompactionTurnAfterATimeoutStillNeverLeaksATurnComplete() throws Exception {
        File go = noMarker();
        File script = fakeCodexCompaction(noMarker(), false,
                turnStarted(COMPACT_TURN),
                WAIT_FOR + go.getAbsolutePath(),
                agentDelta(COMPACT_TURN, "late summary"),
                tokenUsage(COMPACT_TURN, 777, 200000),
                turnCompleted(COMPACT_TURN, "completed", null));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        assertTrue(manager.compact(300));
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED from the work timing out");
        assertFalse(manager.isBusy(), "the timeout closed the work");

        assertTrue(go.createNewFile());
        awaitTrue(() -> count(events, CodexTokenUsageEvent.class) > 0, "the late token usage, which is still forwarded");
        Thread.sleep(300);

        assertEquals(0, count(events, TurnCompleteEvent.class),
                "the late turn/completed must not surface as a real turn end");
        assertEquals(0, count(events, TextDeltaEvent.class), "the late summary text must not reach the chat");
        assertEquals(0, statuses(events, StatusEventTypeEnum.THINKING));
        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED), "the timeout closes the work exactly once");
        assertEquals(0, statuses(events, StatusEventTypeEnum.READY), "the late completion must not close the work a second time");
        assertFalse(manager.isProcessing());

        manager.stop();
    }

    @Test
    void failedCompactRequestReleasesTheSwallowSlotSoTheNextTurnIsNotSwallowed() throws Exception {
        File go = noMarker();
        File script = fakeCodexCompaction(noMarker(), false,
                REQUEST_FAILS,
                WAIT_FOR + go.getAbsolutePath(),
                turnStarted("user-turn"));
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = connected(script, events);

        manager.compact();
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.FAILED) > 0, "FAILED from the refused request");
        assertTrue(firstStatus(events, StatusEventTypeEnum.FAILED).text().contains("compact refused"));

        assertTrue(go.createNewFile());
        awaitTrue(() -> statuses(events, StatusEventTypeEnum.THINKING) > 0,
                "an unrelated turn/started reaching the UI: no compaction turn is coming, so nothing may swallow it");
        assertEquals(1, statuses(events, StatusEventTypeEnum.FAILED));

        manager.stop();
    }
}
