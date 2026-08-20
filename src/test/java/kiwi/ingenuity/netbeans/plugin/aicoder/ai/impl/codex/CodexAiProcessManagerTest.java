package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CodexAiProcessManagerTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static AiSession newSession(String id, CodexSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.CODEX, null, settings, Instant.now(), Instant.now());
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

    /**
     * A minimal real {@code sh} process speaking just enough of the handshake
     * to satisfy {@code spawnAndHandshake}: replies to {@code initialize} (id
     * 1) then {@code thread/start} (id 2), then exits with {@code exitCode} —
     * simulating a crash immediately after a successful handshake. Ids are
     * deterministic because each spawn gets a fresh {@code CodexJsonRpcClient}
     * whose id counter always starts at 1.
     */
    private static File fakeCodexThatExitsAfterHandshake(int exitCode) throws IOException {
        File script = File.createTempFile("fake-codex-", ".sh");
        script.deleteOnExit();
        String body = "#!/bin/sh\n"
                + "read -r _req1\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"userAgent\":\"fake\",\"codexHome\":\"/tmp\"}}\\n'\n"
                + "read -r _notif\n"
                + "read -r _req2\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"thread\":{\"id\":\"fake-thread-id\"},\"model\":\"fake-model\"}}\\n'\n"
                + "exit " + exitCode + "\n";
        Files.writeString(script.toPath(), body, StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    // ---- interrupt(Mail): turn/steer, not turn/interrupt — steers a running turn
    // instead of cancelling it, mirroring GithubCopilotProcessManager's approach
    // (not Claude's, which interrupts). Wire shape confirmed live: `codex app-server
    // generate-json-schema` against codex-cli 0.148.0 (no persisted Slice 5 schemas
    // remained on disk to reuse) — TurnSteerParams requires threadId + expectedTurnId
    // + input; TurnSteerResponse is just {turnId}, so a refusal (e.g.
    // ActiveTurnNotSteerable) can only arrive as a genuine JSON-RPC error, never a
    // "successful" body. ----
    /**
     * Handshake identical to {@link #fakeCodexThatExitsAfterHandshake}, then
     * answers {@code turn/start} (id 3) so a turn is genuinely in flight,
     * {@code touch}es {@code markerFile} the instant {@code turn/steer} (id 4)
     * actually arrives — proving the request was sent, not just that no
     * exception was thrown — then answers it with {@code steerResponseLine} and
     * sleeps so the connection stays up for the rest of the test instead of
     * triggering a crash/EXITED event.
     */
    private static File fakeCodexHandshakeTurnThenSteer(File markerFile, String steerResponseLine) throws IOException {
        File script = File.createTempFile("fake-codex-", ".sh");
        script.deleteOnExit();
        String body = "#!/bin/sh\n"
                + "read -r _req1\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"userAgent\":\"fake\",\"codexHome\":\"/tmp\"}}\\n'\n"
                + "read -r _notif\n"
                + "read -r _req2\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"thread\":{\"id\":\"fake-thread-id\"},\"model\":\"fake-model\"}}\\n'\n"
                + "read -r _req3\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"turn\":{\"id\":\"fake-turn-id\",\"items\":[],\"status\":\"inProgress\"}}}\\n'\n"
                + "read -r _req4\n"
                + "touch '" + markerFile.getAbsolutePath() + "'\n"
                + "printf '" + steerResponseLine + "\\n'\n"
                + "sleep 5\n";
        Files.writeString(script.toPath(), body, StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    /**
     * Handshake identical to {@link #fakeCodexThatExitsAfterHandshake}, but
     * sleeps afterward instead of exiting — a real, connected, idle
     * (never-processing) session with no turn started, for testing the
     * Mail-while-idle no-op path.
     */
    private static File fakeCodexHandshakeThenSleep() throws IOException {
        File script = File.createTempFile("fake-codex-", ".sh");
        script.deleteOnExit();
        String body = "#!/bin/sh\n"
                + "read -r _req1\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"userAgent\":\"fake\",\"codexHome\":\"/tmp\"}}\\n'\n"
                + "read -r _notif\n"
                + "read -r _req2\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"thread\":{\"id\":\"fake-thread-id\"},\"model\":\"fake-model\"}}\\n'\n"
                + "sleep 5\n";
        Files.writeString(script.toPath(), body, StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    private static boolean noStoppedOrFailedEvent(List<AiProcessEvent> events) {
        return events.stream().noneMatch(e -> e instanceof StatusEvent se
                && (se.type() == StatusEventTypeEnum.STOPPED || se.type() == StatusEventTypeEnum.FAILED));
    }

    // ---- buildInitializeParams: shape confirmed by live probe against codex-cli 0.147.0 ----
    @Test
    void buildInitializeParamsHasClientInfoAndCapabilities() {
        JsonObject params = CodexAiProcessManager.buildInitializeParams("aicoder-netbeans", "AI Coder for NetBeans", "1.2");

        assertTrue(params.has("clientInfo"));
        JsonObject clientInfo = params.getAsJsonObject("clientInfo");
        assertEquals("aicoder-netbeans", clientInfo.get("name").getAsString());
        assertEquals("AI Coder for NetBeans", clientInfo.get("title").getAsString());
        assertEquals("1.2", clientInfo.get("version").getAsString());

        assertTrue(params.has("capabilities"), "capabilities object must be present even when empty");
    }

    // ---- buildThreadStartParams: cwd + the sandbox/approval combo §0a says routes
    // approvals to the plugin, + the model (honored directly per live probe, unlike
    // OpenCode which needs a post-hoc config-option call) ----
    @Test
    void buildThreadStartParamsCarriesCwdSandboxApprovalPolicyAndModel() {
        JsonObject params = CodexAiProcessManager.buildThreadStartParams("/home/user/project", "gpt-5.6-luna");
        assertEquals("/home/user/project", params.get("cwd").getAsString());
        assertEquals("workspace-write", params.get("sandbox").getAsString());
        assertEquals("untrusted", params.get("approvalPolicy").getAsString());
        assertEquals("gpt-5.6-luna", params.get("model").getAsString());
    }

    @Test
    void buildThreadStartParamsOmitsModelWhenBlank() {
        JsonObject params = CodexAiProcessManager.buildThreadStartParams("/tmp", null);
        assertFalse(params.has("model"), "a null model must let Codex use its own default, not send an empty string");
        JsonObject params2 = CodexAiProcessManager.buildThreadStartParams("/tmp", "  ");
        assertFalse(params2.has("model"));
    }

    // ---- buildThreadResumeParams: same routing combo, keyed by the stored thread id ----
    @Test
    void buildThreadResumeParamsCarriesThreadIdCwdSandboxApprovalPolicyAndModel() {
        JsonObject params = CodexAiProcessManager.buildThreadResumeParams("th_abc", "/home/user/project", "gpt-5.5");
        assertEquals("th_abc", params.get("threadId").getAsString());
        assertEquals("/home/user/project", params.get("cwd").getAsString());
        assertEquals("workspace-write", params.get("sandbox").getAsString());
        assertEquals("untrusted", params.get("approvalPolicy").getAsString());
        assertEquals("gpt-5.5", params.get("model").getAsString());
    }

    // ---- buildTurnStartParams ----
    @Test
    void buildTurnStartParamsCarriesThreadIdAndTextInput() {
        JsonObject params = CodexAiProcessManager.buildTurnStartParams("th_123", "hello codex");
        assertEquals("th_123", params.get("threadId").getAsString());
        JsonArray input = params.getAsJsonArray("input");
        assertEquals(1, input.size());
        JsonObject textInput = input.get(0).getAsJsonObject();
        assertEquals("text", textInput.get("type").getAsString());
        assertEquals("hello codex", textInput.get("text").getAsString());
    }

    // ---- buildTurnInterruptParams: TurnInterruptParams requires BOTH ids (schema-confirmed) ----
    @Test
    void buildTurnInterruptParamsCarriesThreadIdAndTurnId() {
        JsonObject params = CodexAiProcessManager.buildTurnInterruptParams("th_123", "tu_456");
        assertEquals("th_123", params.get("threadId").getAsString());
        assertEquals("tu_456", params.get("turnId").getAsString());
    }

    // ---- extractThreadId: the exact bug the design doc warns against repeating ----
    @Test
    void extractThreadIdReadsNestedThreadDotId() {
        // Real (trimmed) shape of a thread/start response, captured live against
        // codex-cli 0.147.0 — thread id is nested and camelCase, NOT a top-level
        // thread_id as codex exec's unrelated --json JSONL format uses.
        JsonObject result = json(
                "{\"thread\":{\"id\":\"01a01885-5fba-7932-a9bc-da38712890b6\",\"sessionId\":\"01a01885-5fba-7932-a9bc-da38712890b6\"},"
                + "\"model\":\"gpt-5.6-terra\"}");

        assertEquals("01a01885-5fba-7932-a9bc-da38712890b6", CodexAiProcessManager.extractThreadId(result));
    }

    @Test
    void extractThreadIdReturnsNullWhenThreadMissing() {
        JsonObject result = new JsonObject();
        result.addProperty("model", "gpt-5.6-terra");
        assertNull(CodexAiProcessManager.extractThreadId(result));
    }

    @Test
    void extractThreadIdReturnsNullWhenThreadIsNotAnObject() {
        JsonObject result = new JsonObject();
        result.add("thread", new JsonArray());
        assertNull(CodexAiProcessManager.extractThreadId(result));
    }

    @Test
    void extractThreadIdReturnsNullWhenIdFieldMissing() {
        JsonObject result = new JsonObject();
        JsonObject thread = new JsonObject();
        thread.addProperty("sessionId", "01a01885-5fba-7932-a9bc-da38712890b6");
        result.add("thread", thread);
        assertNull(CodexAiProcessManager.extractThreadId(result));
    }

    @Test
    void extractThreadIdReturnsNullOnNullResult() {
        assertNull(CodexAiProcessManager.extractThreadId(null));
    }

    @Test
    void extractThreadIdReturnsNullWhenIdIsJsonNull() {
        JsonObject result = new JsonObject();
        JsonObject thread = new JsonObject();
        thread.add("id", JsonNull.INSTANCE);
        result.add("thread", thread);
        assertNull(CodexAiProcessManager.extractThreadId(result));
    }

    // ---- extractTurnId: same nested shape, needed for turn/interrupt ----
    @Test
    void extractTurnIdReadsNestedTurnDotId() {
        JsonObject result = json("{\"threadId\":\"th_1\",\"turn\":{\"id\":\"tu_1\",\"items\":[],\"status\":\"inProgress\"}}");
        assertEquals("tu_1", CodexAiProcessManager.extractTurnId(result));
    }

    @Test
    void extractTurnIdReturnsNullWhenTurnMissing() {
        assertNull(CodexAiProcessManager.extractTurnId(new JsonObject()));
    }

    @Test
    void extractTurnIdReturnsNullOnNullResult() {
        assertNull(CodexAiProcessManager.extractTurnId(null));
    }

    // ---- start(): must refuse cleanly, never NPE, and never spawn a process itself ----
    @Test
    void startFailsWhenExecutableMissing() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.setCurrentSession(newSession("s1", new CodexSessionSettings()));

        manager.start("/definitely/not/a/real/codex/binary", "gpt-5.6-terra");

        assertFalse(manager.isRunning());
        assertEquals(1, events.size());
        StatusEvent status = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, status.type());
    }

    @Test
    void startFailsWhenNoSessionConfigured() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        // Deliberately no setCurrentSession() call.

        manager.start("/usr/bin/codex", "gpt-5.6-terra");

        assertFalse(manager.isRunning());
        assertEquals(1, events.size());
        assertEquals(StatusEventTypeEnum.FAILED, ((StatusEvent) events.get(0)).type());
    }

    // ---- sendPrompt: must return promptly, never block the calling (EDT) thread,
    // even though the handshake itself takes seconds — same guard OpenCode has. ----
    @Test
    void sendPromptReturnsPromptlyBeforeHandshakeCompletes() throws Exception {
        CountDownLatch handshakeStarted = new CountDownLatch(1);
        CountDownLatch handshakeGate = new CountDownLatch(1);

        CodexAiProcessManager manager = new CodexAiProcessManager(e -> {
        }) {
            @Override
            public synchronized void start(String executablePath, String model) {
                running = true;
            }

            @Override
            protected void spawnAndHandshake(File workDir) throws Exception {
                handshakeStarted.countDown();
                handshakeGate.await(5, TimeUnit.SECONDS);
                throw new IOException("test-abort — intentional");
            }
        };
        manager.start("fake", "fake");

        long t0 = System.nanoTime();
        manager.sendPrompt("hello", new File(System.getProperty("java.io.tmpdir")), List.of());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(handshakeStarted.await(2, TimeUnit.SECONDS), "handshake thread did not start within 2 s");
        assertTrue(elapsedMs < 500, "sendPrompt blocked for " + elapsedMs + " ms — would freeze the EDT");
        assertTrue(manager.isProcessing(), "processing must be true while the handshake is in flight");

        handshakeGate.countDown();
    }

    @Test
    void sendPromptIsNoOpWhenNotRunning() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        // running is false — never started.
        assertDoesNotThrow(() -> manager.sendPrompt("hello", new File(System.getProperty("java.io.tmpdir")), List.of()));
        assertTrue(events.isEmpty());
        assertFalse(manager.isProcessing());
    }

    // ---- stop(): safe to call repeatedly / when never started ----
    @Test
    void stopIsSafeWhenNeverStarted() {
        CodexAiProcessManager manager = new CodexAiProcessManager(e -> {
        });
        assertDoesNotThrow(manager::stop);
        assertFalse(manager.isRunning());
        assertNull(manager.threadId());
    }

    // ---- interrupt(): only acts while a turn is in flight, and only for Cancel ----
    @Test
    void interruptIsNoOpWhenNotProcessing() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.interrupt(InterruptTypeEnum.Cancel);
        assertTrue(events.isEmpty(), "interrupt with nothing in flight must not fire any event");
    }

    @Test
    void interruptMailIsNoOpWhenIdle() throws Exception {
        File script = fakeCodexHandshakeThenSleep();
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.setCurrentSession(newSession("s1", new CodexSessionSettings()));
        manager.start(script.getAbsolutePath(), "fake-model");

        // Drive the handshake directly (same package, protected method) rather than
        // via sendPrompt/sendTurn, so threadId/client get populated but processing
        // stays false and no turn is ever started.
        manager.spawnAndHandshake(new File(System.getProperty("java.io.tmpdir")));
        awaitTrue(() -> manager.threadId() != null, "handshake completed");
        assertFalse(manager.isProcessing());
        events.clear();

        assertDoesNotThrow(() -> manager.interrupt(InterruptTypeEnum.Mail));
        assertTrue(noStoppedOrFailedEvent(events), "Mail while idle must not fire STOPPED or FAILED");
        assertFalse(manager.isProcessing());

        manager.stop();
    }

    @Test
    void interruptMailWhileProcessingAttemptsSteer() throws Exception {
        File marker = File.createTempFile("codex-steer-marker-", "");
        assertTrue(marker.delete());
        File script = fakeCodexHandshakeTurnThenSteer(marker,
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"turnId\":\"fake-turn-id\"}}");
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.setCurrentSession(newSession("s1", new CodexSessionSettings()));
        manager.start(script.getAbsolutePath(), "fake-model");

        manager.sendPrompt("hi", new File(System.getProperty("java.io.tmpdir")), List.of());
        awaitTrue(() -> manager.currentTurnId() != null, "turn/start response processed, turn in flight");
        assertTrue(manager.isProcessing());
        events.clear();

        manager.interrupt(InterruptTypeEnum.Mail);

        awaitTrue(marker::exists, "turn/steer actually reached the app-server");
        // Give the response a moment to round-trip before asserting on post-state.
        Thread.sleep(100);
        assertTrue(noStoppedOrFailedEvent(events), "a successful steer must not cancel or fail the turn");
        assertTrue(manager.isProcessing(), "the turn must still be running — Mail steers, it does not interrupt");

        manager.stop();
    }

    @Test
    void interruptMailRefusedSteerDoesNotThrowOrCancelTheTurn() throws Exception {
        File marker = File.createTempFile("codex-steer-marker-", "");
        assertTrue(marker.delete());
        // ActiveTurnNotSteerable (or any other refusal) surfaces as a plain JSON-RPC
        // error — TurnSteerResponse has no error field of its own to carry it, and
        // CodexJsonRpcClient does not parse `error.data` at all, so every refusal
        // reason is handled identically here.
        File script = fakeCodexHandshakeTurnThenSteer(marker,
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"error\":{\"code\":-32000,\"message\":\"active turn not steerable\"}}");
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.setCurrentSession(newSession("s1", new CodexSessionSettings()));
        manager.start(script.getAbsolutePath(), "fake-model");

        manager.sendPrompt("hi", new File(System.getProperty("java.io.tmpdir")), List.of());
        awaitTrue(() -> manager.currentTurnId() != null, "turn/start response processed, turn in flight");
        events.clear();

        assertDoesNotThrow(() -> manager.interrupt(InterruptTypeEnum.Mail));

        awaitTrue(marker::exists, "turn/steer was attempted despite the eventual refusal");
        Thread.sleep(100);
        assertTrue(noStoppedOrFailedEvent(events),
                "a refused steer must not cancel or fail the turn — the message is left for the normal inbox flush");
        assertTrue(manager.isProcessing(), "a refused steer must leave the turn running, never escalate to Cancel");

        manager.stop();
    }

    @Test
    void interruptCancelWhileProcessingIsUnchangedByTheMailSteerAddition() throws Exception {
        File script = fakeCodexHandshakeTurnThenSteer(
                File.createTempFile("codex-unused-marker-", ""), "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}");
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.setCurrentSession(newSession("s1", new CodexSessionSettings()));
        manager.start(script.getAbsolutePath(), "fake-model");

        manager.sendPrompt("hi", new File(System.getProperty("java.io.tmpdir")), List.of());
        awaitTrue(() -> manager.currentTurnId() != null, "turn/start response processed, turn in flight");

        manager.interrupt(InterruptTypeEnum.Cancel);

        awaitTrue(() -> events.stream().anyMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.STOPPED),
                "Cancel must still fire STOPPED exactly as before this change");
        assertFalse(manager.isProcessing(), "Cancel must still clear processing immediately, unlike Mail");

        manager.stop();
    }

    // ---- resumeSession(): stores the request; the guard against the wrong id
    // format lives in CodexAiImplementation, not here (Codex thread ids have no
    // "ses_"-style prefix to check, unlike OpenCode) ----
    @Test
    void resumeSessionIgnoresBlankIds() {
        CodexAiProcessManager manager = new CodexAiProcessManager(e -> {
        });
        manager.resumeSession(null);
        manager.resumeSession("");
        manager.resumeSession("  ");
        assertNull(manager.pendingResumeThreadId);
    }

    @Test
    void resumeSessionStoresNonBlankId() {
        CodexAiProcessManager manager = new CodexAiProcessManager(e -> {
        });
        manager.resumeSession("th_stored");
        assertEquals("th_stored", manager.pendingResumeThreadId);
    }

    // ---- isMcpActive: false until start() successfully registers a CodexAiMcpRegistrar ----
    @Test
    void isMcpActiveIsFalseBeforeStart() {
        CodexAiProcessManager manager = new CodexAiProcessManager(e -> {
        });
        assertFalse(manager.isMcpActive());
    }

    // ---- buildMcpConfigArgs: per-invocation -c overrides, never written to config.toml
    // (design doc §0a) — no header-based credentials, since McpHookServer authenticates
    // tools/call from JSON-RPC arguments, not HTTP headers (confirmed by reading it) ----
    @Test
    void buildMcpConfigArgsReturnsEmptyForNullOrBlankUrl() {
        assertTrue(CodexAiProcessManager.buildMcpConfigArgs(null).isEmpty());
        assertTrue(CodexAiProcessManager.buildMcpConfigArgs("").isEmpty());
        assertTrue(CodexAiProcessManager.buildMcpConfigArgs("   ").isEmpty());
    }

    @Test
    void buildMcpConfigArgsCarriesUrlAndAutoApprovalMode() {
        List<String> args = CodexAiProcessManager.buildMcpConfigArgs("http://127.0.0.1:1234/mcp/codex");

        assertEquals(4, args.size(), "two -c flag/value pairs");
        assertEquals("-c", args.get(0));
        assertEquals("mcp_servers." + CodexAiProcessManager.MCP_SERVER_NAME + ".url=\"http://127.0.0.1:1234/mcp/codex\"",
                args.get(1));
        assertEquals("-c", args.get(2));
        // "approve", not "auto": with "auto" a live run prompted for every tool call,
        // including read-only ones, because these tools carry no annotations for it to
        // decide from. Valid values are auto/prompt/writes/approve.
        assertEquals("mcp_servers." + CodexAiProcessManager.MCP_SERVER_NAME + ".default_tools_approval_mode=\"approve\"",
                args.get(3));
    }

    // ---- extractModel: thread/start echoes the applied model back as a top-level
    // result.model (live-probe confirmed) — used to detect a silent model mismatch ----
    @Test
    void extractModelReadsTopLevelModel() {
        JsonObject result = json("{\"thread\":{\"id\":\"th_1\"},\"model\":\"gpt-5.6-luna\"}");
        assertEquals("gpt-5.6-luna", CodexAiProcessManager.extractModel(result));
    }

    @Test
    void extractModelReturnsNullWhenMissingOrNullResult() {
        assertNull(CodexAiProcessManager.extractModel(new JsonObject()));
        assertNull(CodexAiProcessManager.extractModel(null));
        JsonObject result = new JsonObject();
        result.add("model", JsonNull.INSTANCE);
        assertNull(CodexAiProcessManager.extractModel(result));
    }

    // ---- Crash handling: a Codex death must be visible (EXITED) and must not
    // leave the session permanently "busy" for the next prompt (finding 2 from
    // the two-reviewer gap analysis) ----
    @Test
    void processCrashAfterHandshakeReportsExitedAndClearsConnectionState() throws Exception {
        File script = fakeCodexThatExitsAfterHandshake(7);
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);
        manager.setCurrentSession(newSession("s1", new CodexSessionSettings()));

        manager.start(script.getAbsolutePath(), "fake-model");
        assertTrue(manager.isRunning());

        manager.sendPrompt("hi", new File(System.getProperty("java.io.tmpdir")), List.of());

        awaitTrue(() -> events.stream().anyMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.EXITED),
                "EXITED status event after the fake process exits nonzero");
        StatusEvent exited = (StatusEvent) events.stream()
                .filter(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.EXITED)
                .findFirst().orElseThrow();
        assertTrue(exited.text().contains("Codex"), "EXITED message should name Codex: " + exited.text());
        assertTrue(exited.text().contains("7"), "EXITED message should carry the exit code: " + exited.text());

        // The core of finding 2: connection state must not be left dangling so a
        // second sendPrompt would take the dead sendTurn path instead of
        // re-handshaking, and processing must not be stuck true forever.
        awaitTrue(() -> manager.client() == null, "client cleared after crash");
        assertNull(manager.appServerHandler(), "appServerHandler cleared after crash");
        assertNull(manager.threadId(), "threadId cleared after crash");
        assertFalse(manager.isProcessing(), "processing must not stay stuck true after a crash");

        manager.stop();
    }

    @Test
    void onHandlerDisconnectedClearsProcessingAndConnectionState() throws Exception {
        TestableCodexAiProcessManager manager = new TestableCodexAiProcessManager(e -> {
        });
        manager.forceState(true, false, null);

        manager.onHandlerDisconnected();

        assertFalse(manager.isProcessing());
        assertNull(manager.client());
        assertNull(manager.appServerHandler());
        assertNull(manager.threadId());
        assertNull(manager.currentTurnId());
    }

    @Test
    void handleProcessExitIgnoresStaleExitFromSupersededProcess() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAiProcessManager manager = new CodexAiProcessManager(events::add);

        Process real = new ProcessBuilder("sh", "-c", "exit 3").start();
        real.waitFor(5, TimeUnit.SECONDS);
        // currentProcess deliberately left at its default (null) — not `real` — so
        // handleProcessExit's staleness guard (currentProcess != dead) must reject it.
        manager.handleProcessExit(real);

        assertTrue(events.isEmpty(), "a stale/superseded process exit must not fire any event");
    }

    @Test
    void handleProcessExitSuppressedWhenCancelledByUser() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        TestableCodexAiProcessManager manager = new TestableCodexAiProcessManager(events::add);
        Process real = new ProcessBuilder("sh", "-c", "exit 9").start();
        real.waitFor(5, TimeUnit.SECONDS);
        manager.forceState(false, true, real);

        manager.handleProcessExit(real);

        assertTrue(events.isEmpty(), "a user-initiated stop must not report EXITED for the same death");
    }

    /**
     * {@code processing}/{@code cancelledByUser}/{@code currentProcess} are
     * {@code protected} on the base class in a different package — a subclass
     * (even a test-only one) can set them from its own code, a plain top-level
     * test class cannot. Exists purely to simulate "a turn was in flight" /
     * "the user pressed stop" without driving a real handshake for tests that
     * only care about {@code handleProcessExit}/{@code onHandlerDisconnected}'s
     * own logic.
     */
    private static final class TestableCodexAiProcessManager extends CodexAiProcessManager {

        TestableCodexAiProcessManager(kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener l) {
            super(l);
        }

        void forceState(boolean processingVal, boolean cancelledVal, Process proc) {
            processing = processingVal;
            cancelledByUser = cancelledVal;
            currentProcess = proc;
        }
    }
}
