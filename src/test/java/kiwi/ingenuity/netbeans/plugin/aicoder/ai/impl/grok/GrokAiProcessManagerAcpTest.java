package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpMethodEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Grok's ACP lifecycle driven against a real fake-agent subprocess (a tiny shell script speaking line-
 * delimited JSON-RPC on stdio, the same technique {@code OpenCodeHandshakeRaceTest} uses) and against a stub
 * {@link AcpConnection} for wire-shape assertions that do not need a live process.
 */
@Timeout(15)
class GrokAiProcessManagerAcpTest {

    private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"));

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

    private static TestableGrokAiProcessManager startManager(File script, List<AiProcessEvent> events) {
        TestableGrokAiProcessManager manager = new TestableGrokAiProcessManager(events::add);
        manager.setCurrentSession(new AiSession("s1", "Test", null, AiTypeEnum.GROK, null,
                new GrokSessionSettings(), Instant.now(), Instant.now()));
        manager.start(script.getAbsolutePath(), "grok-4.5");
        assertTrue(manager.isRunning(), "the fake agent session must start: " + events);
        return manager;
    }

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    private static long statusCount(List<AiProcessEvent> events, StatusEventTypeEnum type) {
        return events.stream().filter(e -> e instanceof StatusEvent se && se.type() == type).count();
    }

    // ---- 1. Launch + handshake: initialize, session/new with the plugin's MCP server, turn completes ----
    @Test
    void launchAndHandshake_sessionNewCarriesThePluginIdAsTheMcpServerName() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File capture = captureFile();
        File script = fakeAgent(
                "read -r line\n"
                + "echo \"$line\" >> \"" + capture + "\"\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r _prompt\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}\\n'\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> !readCaptureFile(capture).isEmpty(), "session/new request captured");

            String raw = readCaptureFile(capture).get(0);
            JsonObject sessionNewParams = JsonParser.parseString(raw).getAsJsonObject().getAsJsonObject("params");
            JsonArray mcpServers = sessionNewParams.getAsJsonArray("mcpServers");
            assertTrue(mcpServers.size() > 0, "the plugin's MCP server must be advertised in session/new");
            assertEquals(StringConst.PLUGIN_ID, mcpServers.get(0).getAsJsonObject().get("name").getAsString(),
                    "the MCP server name must be the plugin's id, not a dead literal");

            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.READY) >= 1, "READY");
            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof TurnCompleteEvent), "TurnComplete");
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    // ---- 2. Streaming: agent_message_chunk session/update notifications become TextDeltaEvents ----
    @Test
    void streamingAgentMessageChunk_deliversTextDeltaEvents() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r _prompt\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"ses_fake\","
                + "\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"text\":\"hi there\"}}}}\\n'\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}\\n'\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof TextDeltaEvent te && "hi there".equals(te.text())),
                    "the streamed chunk to arrive as a TextDeltaEvent: " + events);
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    // ---- 3. Cancel mid-turn: exactly one STOPPED, session/cancel sent ----
    @Test
    void cancelMidTurn_sendsSessionCancel_andEmitsExactlyOneStopped() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File marker = File.createTempFile("grok-prompt-seen-", ".marker");
        marker.delete();
        File capture = captureFile();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r _prompt\n"
                + "touch \"" + marker.getAbsolutePath() + "\"\n"
                + "read -r cancelline\n"
                + "echo \"$cancelline\" >> \"" + capture + "\"\n"
                + "sleep 60\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(marker::exists, "the fake agent to receive the prompt");
            awaitTrue(manager::isProcessing, "the turn to be in flight");

            manager.interrupt(InterruptTypeEnum.Cancel);

            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.STOPPED) == 1, "exactly one STOPPED: " + events);
            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED), events.toString());
            assertFalse(manager.isProcessing());
            awaitTrue(() -> !readCaptureFile(capture).isEmpty(), "the agent to receive a notification after Cancel");
            JsonObject received = JsonParser.parseString(readCaptureFile(capture).get(0)).getAsJsonObject();
            assertEquals("session/cancel", received.get("method").getAsString(),
                    "Cancel must actually send session/cancel to the agent, not just report STOPPED locally");
        }
        finally {
            marker.delete();
            manager.stopAndCloseConnections();
        }
    }

    // ---- 4. set_config_option: wire shape, via a stub connection (no live process needed) ----
    @Test
    void setConfigOption_sendsSessionIdConfigIdAndValue() {
        GrokAiProcessManager manager = new GrokAiProcessManager(e -> {
        });
        StubConnection conn = new StubConnection();
        manager.connection = conn;
        manager.acpSessionId = "ses_fake";
        try {
            manager.setConfigOption("reasoning_effort", "high");

            assertEquals(1, conn.sentMethods.size());
            assertEquals(AcpMethodEnum.SESSION_SET_CONFIG_OPTION, conn.sentMethods.get(0));
            JsonObject params = conn.sentParams.get(0);
            assertEquals("ses_fake", params.get("sessionId").getAsString());
            assertEquals("reasoning_effort", params.get("configId").getAsString());
            assertEquals("high", params.get("value").getAsString());
        }
        finally {
            conn.close();
        }
    }

    // ---- 5. Usage: the session/prompt result's _meta becomes a GrokTokenUsageEvent ----
    @Test
    void usageFromPromptResultMeta_deliversGrokTokenUsageEvent() {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        GrokAiProcessManager manager = new GrokAiProcessManager(events::add);
        manager.setModel("grok-4.5");

        JsonObject meta = new JsonObject();
        meta.addProperty("inputTokens", 100);
        meta.addProperty("outputTokens", 50);
        meta.addProperty("totalTokens", 150);
        meta.addProperty("modelId", "grok-4.5");
        JsonObject result = new JsonObject();
        result.add("_meta", meta);

        manager.reportUsage(result);

        assertEquals(1, events.stream().filter(e -> e instanceof GrokTokenUsageEvent).count());
        GrokTokenUsageEvent usage = (GrokTokenUsageEvent) events.get(0);
        assertEquals(150, usage.currentTokens());
        assertEquals("grok-4.5", usage.model());
    }

    @Test
    void noMetaOnPromptResult_reportsNoUsageEvent() {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        GrokAiProcessManager manager = new GrokAiProcessManager(events::add);
        manager.reportUsage(new JsonObject());
        assertTrue(events.isEmpty());
    }

    /**
     * Boss's handover: {@code initialize}/{@code session/new}'s result carries
     * {@code models.availableModels[]._meta.totalContextTokens} per model (256000 for grok-4.7). This must be
     * used as {@code maxTokens} instead of 0, and kept PER MODEL so a model switch mid-session (via {@code
     * set_config_option}) reports the new model's window on its very next turn, not the window of whichever
     * model Grok started with.
     */
    @Test
    void contextWindowFromHandshake_isUsedAsMaxTokens_andUpdatesPerModelOnASwitch() {
        GrokAiProcessManager manager = new GrokAiProcessManager(e -> {
        });
        JsonObject handshakeResult = new JsonObject();
        JsonObject models = new JsonObject();
        JsonArray availableModels = new JsonArray();
        availableModels.add(modelEntry("grok-4.7", 256_000));
        availableModels.add(modelEntry("grok-4.6", 500_000));
        models.add("availableModels", availableModels);
        handshakeResult.add("models", models);

        manager.modelContextWindows = GrokAiProcessManager.parseModelContextWindows(handshakeResult);

        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        GrokAiProcessManager usageManager = new GrokAiProcessManager(events::add);
        usageManager.modelContextWindows = manager.modelContextWindows;

        usageManager.reportUsage(promptMetaResult(150, "grok-4.7"));
        usageManager.reportUsage(promptMetaResult(300, "grok-4.6"));

        assertEquals(2, events.size());
        GrokTokenUsageEvent first = (GrokTokenUsageEvent) events.get(0);
        GrokTokenUsageEvent second = (GrokTokenUsageEvent) events.get(1);
        assertEquals(256_000, first.maxTokens(), "grok-4.7's own window, not a shared default");
        assertEquals(500_000, second.maxTokens(), "switching model must report the NEW model's window");
    }

    private static JsonObject modelEntry(String id, int totalContextTokens) {
        JsonObject meta = new JsonObject();
        meta.addProperty("totalContextTokens", totalContextTokens);
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.add("_meta", meta);
        return entry;
    }

    private static JsonObject promptMetaResult(int totalTokens, String modelId) {
        JsonObject meta = new JsonObject();
        meta.addProperty("totalTokens", totalTokens);
        meta.addProperty("modelId", modelId);
        JsonObject result = new JsonObject();
        result.add("_meta", meta);
        return result;
    }

    // ---- 6. load/resume: resumeSession() makes the NEXT handshake use session/load, not session/new ----
    @Test
    void resumeSession_usesSessionLoadWithTheStoredId() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File capture = captureFile();
        File script = fakeAgent(
                "read -r line\n"
                + "echo \"$line\" >> \"" + capture + "\"\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\\n'\n"
                + "read -r _prompt\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}\\n'\n"
                + "sleep 60\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.resumeSession("ses_stored");
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> !readCaptureFile(capture).isEmpty(), "session/load request captured");

            String raw = readCaptureFile(capture).get(0);
            JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            assertEquals("session/load", msg.get("method").getAsString());
            assertEquals("ses_stored", msg.getAsJsonObject("params").get("sessionId").getAsString());

            awaitTrue(() -> "ses_stored".equals(manager.acpSessionId), "the handshake to finish publishing the resumed id");
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    /**
     * Live-confirmed Grok bug: {@code session/load} replays the WHOLE prior conversation as session/update
     * notifications (agent_message_chunk etc.) between sending the request and answering it. The plugin's own
     * persisted history already shows that conversation, so the replay must never reach the UI as a new
     * TextDeltaEvent — only the real turn's own chunks may. No sleep before answering session/load: the fix
     * orders the suppression's clear onto the connection's notify executor itself (behind whatever replay it
     * already queued), so the correctness does not depend on winning a race against real scheduling.
     */
    @Test
    void sessionLoadReplay_neverProducesTextDeltaEvents_onlyThePromptsOwnChunkDoes() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "read -r _load\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"ses_stored\","
                + "\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"text\":\"REPLAYED OLD TEXT\"}}}}\\n'\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\\n'\n"
                + "read -r _prompt\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"ses_stored\","
                + "\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"text\":\"real answer\"}}}}\\n'\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}\\n'\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.resumeSession("ses_stored");
            manager.sendPrompt("hello", WORK_DIR, List.of());

            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof TextDeltaEvent te && "real answer".equals(te.text())),
                    "the real turn's own chunk must still arrive: " + events);
            assertTrue(events.stream().noneMatch(e -> e instanceof TextDeltaEvent te && te.text().contains("REPLAYED")),
                    "the session/load replay must never produce a TextDeltaEvent: " + events);
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    /**
     * Coder_1's review finding, pinned deterministically rather than by timing: the load's response and an
     * already-queued replay chunk complete on different paths, so clearing suppression must be ordered onto
     * the SAME single-thread notify executor the replay chunk was queued on — never run synchronously the
     * instant the load's future completes. This blocks that executor first, queues a replay chunk and the
     * ordered clear behind the block, then a real chunk behind the clear, and releases — proving the clear
     * can never run ahead of a chunk that was queued before it, however the wire response happens to
     * schedule.
     */
    @Test
    void loadSuppressionClear_isOrderedOnNotifyThread_neverRunsAheadOfAQueuedReplayChunk() throws Exception {
        List<AiProcessEvent> fired = new CopyOnWriteArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });
        AcpConnection conn = new AcpConnection(new ByteArrayOutputStream(), InputStream.nullInputStream(), handler);
        try {
            CountDownLatch block = new CountDownLatch(1);
            // Occupies the single-thread notify executor so every task queued below is held back until
            // release — makes the ordering deterministic instead of hoping scheduling cooperates.
            conn.runOnNotifyThread(() -> {
                try {
                    block.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            long token = handler.beginSuppressingSessionUpdatesForLoad();
            conn.runOnNotifyThread(() -> handler.onSessionUpdate("ses_x", agentMessageChunk("REPLAYED")));
            // The fixed caller pattern: the ordered clear, queued on the SAME executor behind the replay
            // chunk above — exactly what GrokAiProcessManager's finally block now does.
            conn.runOnNotifyThread(() -> handler.endSuppressingSessionUpdatesForLoad(token));
            conn.runOnNotifyThread(() -> handler.onSessionUpdate("ses_x", agentMessageChunk("real answer")));

            block.countDown();

            awaitTrue(() -> fired.stream().anyMatch(e -> e instanceof TextDeltaEvent te && "real answer".equals(te.text())),
                    "the chunk queued after the clear must still arrive: " + fired);
            assertTrue(fired.stream().noneMatch(e -> e instanceof TextDeltaEvent te && te.text().contains("REPLAYED")),
                    "the chunk queued BEFORE the clear must never render, however the executor schedules it: " + fired);
        }
        finally {
            conn.close();
        }
    }

    private static JsonObject agentMessageChunk(String text) {
        JsonObject content = new JsonObject();
        content.addProperty("text", text);
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "agent_message_chunk");
        update.add("content", content);
        return update;
    }

    // ---- 7. Crash mid-turn: exactly one EXITED ----
    @Test
    void crashMidTurn_emitsExactlyOneExited() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r _prompt\n"
                + "exit 7\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.EXITED) == 1, "exactly one EXITED: " + events);
            // handleProcessExit's early stale-exit guard and the dying connection's own failed-future
            // callback both run asynchronously; a quiet period here (rather than asserting the instant
            // EXITED is observed) is what would catch a FAILED arriving a few milliseconds after it.
            Thread.sleep(200);
            assertEquals(1, statusCount(events, StatusEventTypeEnum.EXITED), events.toString());
            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED), events.toString());
            assertFalse(manager.isProcessing());
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    // ---- 8. Shutdown: closing stdin alone does not exit Grok, so stop() must escalate to a forced kill ----
    @Test
    void stop_closesConnectionThenKillsAfterGraceWhenTheProcessIgnoresStdinClose() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        // Ignores stdin closing (the trap keeps the shell alive); only a SIGKILL from destroyForcibly() ends it.
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "trap '' PIPE\n"
                + "while true; do sleep 1; done\n");
        GrokAiProcessManager.shutdownGraceMillisForTests = 300L;
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(manager::hasActiveProcess, "the fake agent to start");
            Process proc = manager.process();
            assertNotNull(proc);

            manager.stop();

            awaitTrue(() -> !proc.isAlive(), "the forced kill to end the process that ignored stdin closing");
        }
        finally {
            GrokAiProcessManager.shutdownGraceMillisForTests = null;
        }
    }

    // ---- 9. Session identity reaches Grok on EVERY turn, not just the first ----
    /**
     * {@code ContextProvider.buildIdentityBlock()} prepends the sessionId/secretKey block unconditionally on
     * every {@code buildPreamble} call for any CREDENTIALS-gated type (confirmed by reading
     * ContextProvider.java directly: unlike the project/file baseline, which is only sent in full on the
     * first turn, the identity block has no {@code isFirstSend} gate). Grok is explicitly named in that
     * code's own comment as a type this covers. This test proves {@code GrokAiProcessManager} does not
     * accidentally break that guarantee on its side — it must forward the given text to {@code
     * session/prompt} byte-for-byte, with nothing of its own that could cache or drop the identity block on a
     * later turn — by driving a real {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.ContextProvider}
     * against a real session and checking what actually reached the wire for TWO turns.
     */
    @Test
    void sessionIdentityBlockReachesGrokOnTheFirstAndASecondTurn() throws Exception {
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession aiSession
                                                                    = kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession.create(null, AiTypeEnum.GROK);
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.ContextProvider contextProvider
                                                                  = new kiwi.ingenuity.netbeans.plugin.aicoder.ai.ContextProvider(fo -> {
                });
        contextProvider.setSession(aiSession);

        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File capture = captureFile();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r line1\n"
                + "echo \"$line1\" >> \"" + capture + "\"\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}\\n'\n"
                + "read -r line2\n"
                + "echo \"$line2\" >> \"" + capture + "\"\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"end_turn\"}}\\n'\n"
                + "sleep 60\n");
        TestableGrokAiProcessManager manager = new TestableGrokAiProcessManager(events::add);
        manager.setCurrentSession(aiSession);
        manager.start(script.getAbsolutePath(), "grok-4.5");
        try {
            String firstPrompt = contextProvider.buildPreamble("first message", null);
            manager.sendPrompt(firstPrompt, WORK_DIR, List.of());
            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof TurnCompleteEvent), "the first turn to complete");

            String secondPrompt = contextProvider.buildPreamble("second message", null);
            manager.sendPrompt(secondPrompt, WORK_DIR, List.of());
            awaitTrue(() -> events.stream().filter(e -> e instanceof TurnCompleteEvent).count() == 2,
                    "the second turn to complete");

            List<String> captured = readCaptureFile(capture);
            assertEquals(2, captured.size(), "both turns must have reached the fake agent");
            for (int i = 0; i < 2; i++) {
                JsonObject params = JsonParser.parseString(captured.get(i)).getAsJsonObject().getAsJsonObject("params");
                String sentText = params.getAsJsonArray("prompt").get(0).getAsJsonObject().get("text").getAsString();
                assertTrue(sentText.contains("sessionId: " + aiSession.id()),
                        "turn " + (i + 1) + " must carry the session identity block: " + sentText);
                assertTrue(sentText.contains("secretKey: " + aiSession.secret()),
                        "turn " + (i + 1) + " must carry the plugin credentials, not just the first turn: " + sentText);
            }
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    // ---- Review follow-up: hung agent after publish (stdout closes, process stays alive) ----
    @Test
    void hungAgentAfterPublish_doesNotThrow_destroysTheProcess_exactlyOneFailedCloser() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r _prompt\n"
                + "exec 1>&-\n"
                + "sleep 60\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(manager::hasActiveProcess, "the fake agent to start");
            Process proc = manager.process();

            // onHandlerDisconnected must not throw IllegalThreadStateException from exitValue() on a process
            // that is still alive — it must detach, destroyForcibly, and let the pending prompt's own
            // failed future be the turn's one closer.
            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.FAILED) == 1,
                    "the hung prompt's future to fail once stdout closes and the grace period elapses: " + events);
            Thread.sleep(200);
            assertEquals(1, statusCount(events, StatusEventTypeEnum.FAILED), events.toString());
            assertEquals(0, statusCount(events, StatusEventTypeEnum.EXITED), events.toString());
            awaitTrue(() -> !proc.isAlive(), "the hung process must be destroyed, never leaked");
            assertFalse(manager.isProcessing(), "the UI must not stay stuck busy");
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    /**
     * The agent crashes right after answering, and the handshake reaches its publish point after the process
     * is dead and its output ended but before the process's exit callback has run. That is a crash, not a
     * hang: it must be reported once as EXITED with the exit code, not as a FAILED "stopped responding". The
     * exit callback is held back so this ordering happens every run — same shape, same technique, as
     * {@code OpenCodeHandshakeRaceTest.crashSeenByTheHandshakeBeforeItsExitCallback_isReportedOnceAsExited},
     * now exercising the logic both backends share through {@code AbstractAcpProcessManager
     * .publishConnectionOrReportExit}.
     */
    @Test
    void crashSeenByTheHandshakeBeforeItsExitCallback_isReportedOnceAsExited() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        HeldHandshakeManager manager = new HeldHandshakeManager(events::add);
        manager.exitCallbackGate = new CountDownLatch(1);
        manager.setCurrentSession(new AiSession("s1", "Test", null, AiTypeEnum.GROK, null,
                new GrokSessionSettings(), Instant.now(), Instant.now()));
        manager.start(fakeAgent("read -r _session\n"
                                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                                + "exit 7\n").getAbsolutePath(), "grok-4.5");
        try {
            manager.sendPrompt("hi", WORK_DIR, List.of());
            manager.awaitHeld();
            awaitTrue(() -> manager.connections.get(0).isStreamEnded()
                            && manager.process() != null && !manager.process().isAlive(),
                    "the agent to have died, its exit not yet handled");

            manager.releaseAndJoinHeld();

            assertEquals(1, statusCount(events, StatusEventTypeEnum.EXITED), "the crash is reported: " + events);
            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                     && se.type() == StatusEventTypeEnum.EXITED && se.text().contains("7")),
                    "with its exit code: " + events);
            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED), "and only once: " + events);
            assertNull(manager.connection, "no connection to the dead agent is published");
            assertTrue(manager.connections.get(0).isClosed(), "and its connection is closed");
            assertFalse(manager.isProcessing());
        }
        finally {
            manager.exitCallbackGate.countDown();
            manager.release.countDown();
            manager.stopAndCloseConnections();
        }
    }

    // ---- Review follow-up: Stop while the handshake is still in flight must publish nothing and stay silent ----
    @Test
    void stopDuringHandshake_doesNotPublishAConnection_andStaysSilent() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "sleep 1\n"
                + "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "sleep 60\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        events.clear(); // drop start()'s own READY; this test is about what happens AFTER sendPrompt+stop()
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(manager::hasActiveProcess, "the fake agent to start");

            manager.stop();

            // The handshake thread is blocked in session/new's 1s sleep window; give it time to wake, see
            // running==false in the publish block, and bail out without publishing.
            Thread.sleep(1500);
            assertEquals(null, manager.connection, "a session stopped mid-handshake must never publish a connection");
            assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent),
                    "stop() during a handshake must stay silent — its own READY/FAILED close the turn, not this: " + events);
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    // ---- Review follow-up: a late "cancelled" response after Stop must be ignored, never a second closer ----
    @Test
    void lateCancelledResponseAfterStop_isIgnored() {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        TestableGrokAiProcessManager manager = new TestableGrokAiProcessManager(events::add);
        manager.armTurn(true);
        CapturingStubConnection conn = new CapturingStubConnection();
        manager.connection = conn;
        manager.acpSessionId = "ses_fake";

        manager.sendTurn("hello");
        assertEquals(1, conn.requests.size());

        manager.stop();
        events.clear();

        JsonObject cancelledResult = new JsonObject();
        cancelledResult.addProperty("stopReason", "cancelled");
        conn.requests.get(0).complete(cancelledResult);

        assertTrue(events.isEmpty(), "a response that arrives after stop() must be ignored, not reported: " + events);
    }

    // ---- Review follow-up (finding 4): resume after a crash, using the id the crash kept ----
    @Test
    void resumeAfterCrash_usesTheKeptSessionId() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File capture = captureFile();
        File marker = File.createTempFile("grok-resumed-", ".marker");
        marker.delete();
        // Crashes on its first run (no marker yet); on the retry (marker present, same executablePath — the
        // manager always relaunches whatever script start() was given) it behaves normally, so the test
        // never has to reach into the protected executablePath field to swap scripts mid-test.
        File script = fakeAgent(
                "if [ -f \"" + marker.getAbsolutePath() + "\" ]; then\n"
                + "  read -r line\n"
                + "  echo \"$line\" >> \"" + capture + "\"\n"
                + "  printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\\n'\n"
                + "  sleep 60\n"
                + "else\n"
                + "  touch \"" + marker.getAbsolutePath() + "\"\n"
                + "  read -r _session\n"
                + "  printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_crash\"}}\\n'\n"
                + "  read -r _prompt\n"
                + "  exit 9\n"
                + "fi\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.EXITED) == 1, "the crash to be reported: " + events);
            assertEquals("ses_crash", manager.pendingAcpResumeId, "the crashed session's id must be kept to resume");
            assertEquals(null, manager.connection, "the dead connection must be dropped");
            assertEquals(null, manager.sessionConfigOptions, "stale config options must not survive the crash");

            manager.sendPrompt("retry", WORK_DIR, List.of());
            awaitTrue(() -> !readCaptureFile(capture).isEmpty(), "the retry's session/load request to be captured");
            JsonObject msg = JsonParser.parseString(readCaptureFile(capture).get(0)).getAsJsonObject();
            assertEquals("session/load", msg.get("method").getAsString());
            assertEquals("ses_crash", msg.getAsJsonObject("params").get("sessionId").getAsString());
        }
        finally {
            marker.delete();
            manager.stopAndCloseConnections();
        }
    }

    // ---- Review follow-up (finding 2): a stderr flood must not block, and its tail must show in EXITED ----
    @Test
    void stderrFlood_doesNotBlock_andIsShownInExited() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r _prompt\n"
                + "i=0; while [ $i -lt 5000 ]; do echo \"stderr line $i\" >&2; i=$((i+1)); done\n"
                + "exit 5\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.EXITED) == 1,
                    "the stderr flood must not block the exit from being reported: " + events);
            StatusEvent exited = events.stream().filter(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.EXITED)
                    .map(e -> (StatusEvent) e).findFirst().orElseThrow();
            assertTrue(exited.text().contains("stderr line 4999"), "the tail of stderr must be shown in the EXITED message: " + exited.text());
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    // ---- Review follow-up (finding 3): sendPrompt's entry refusal must post INFO + TurnComplete, not silence ----
    @Test
    void sendPromptWhileProcessing_postsInfoAndTurnComplete() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r _prompt\n"
                + "sleep 60\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("first", WORK_DIR, List.of());
            awaitTrue(manager::isProcessing, "the first turn to be in flight");
            events.clear();

            manager.sendPrompt("second", WORK_DIR, List.of());

            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof TurnCompleteEvent),
                    "the refused second prompt must still post TurnComplete, or the UI stays locked: " + events);
            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO),
                    "the refusal must say why: " + events);
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    // ---- BigP_1: reasoning_effort via session/set_config_option, including a live mid-session switch ----
    @Test
    void reasoningEffortAppliedAtHandshake_andLiveSwitchSendsSetConfigOption() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File capture = captureFile();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\",\"configOptions\":"
                + "[{\"id\":\"reasoning_effort\","
                + "\"currentValue\":\"low\",\"options\":[{\"value\":\"low\"},{\"value\":\"high\"}]}]}}\\n'\n"
                + "read -r line1\n"
                + "echo \"$line1\" >> \"" + capture + "\"\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"configOptions\":[{\"id\":\"reasoning_effort\","
                + "\"currentValue\":\"high\",\"options\":[{\"value\":\"low\"},{\"value\":\"high\"}]}]}}\\n'\n"
                + "read -r _prompt\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"end_turn\"}}\\n'\n"
                + "read -r line2\n"
                + "echo \"$line2\" >> \"" + capture + "\"\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"configOptions\":[{\"id\":\"reasoning_effort\","
                + "\"currentValue\":\"low\",\"options\":[{\"value\":\"low\"},{\"value\":\"high\"}]}]}}\\n'\n"
                + "sleep 60\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        manager.configureReasoningEffort("high", true);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> !readCaptureFile(capture).isEmpty(), "the initial reasoning_effort set_config_option to be sent");
            JsonObject first = JsonParser.parseString(readCaptureFile(capture).get(0)).getAsJsonObject();
            assertEquals("session/set_config_option", first.get("method").getAsString());
            assertEquals("reasoning_effort", first.getAsJsonObject("params").get("configId").getAsString());
            assertEquals("high", first.getAsJsonObject("params").get("value").getAsString());

            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof TurnCompleteEvent), "the turn to complete");

            manager.setConfigOption("reasoning_effort", "low").get(5, TimeUnit.SECONDS);
            awaitTrue(() -> readCaptureFile(capture).size() >= 2, "the live mid-session switch's set_config_option to be sent");
            JsonObject second = JsonParser.parseString(readCaptureFile(capture).get(1)).getAsJsonObject();
            assertEquals("session/set_config_option", second.get("method").getAsString());
            assertEquals("low", second.getAsJsonObject("params").get("value").getAsString());
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    // ---- Diagnostics: --debug --debug-file is appended only when a path is given, never otherwise ----
    @Test
    void buildAcpCommand_withoutDebugFilePath_omitsDebugFlags() {
        List<String> command = GrokAiProcessManager.buildAcpCommand("grok");
        assertTrue(command.contains("agent"));
        assertTrue(command.contains("stdio"));
        assertFalse(command.contains("--debug"), "no debug flags unless a debug file path is given: " + command);
        assertFalse(command.contains("--debug-file"), command.toString());
    }

    @Test
    void buildAcpCommand_withDebugFilePath_appendsDebugAndDebugFile() {
        String path = "/tmp/grok-acp-debug-some-session.log";
        List<String> command = GrokAiProcessManager.buildAcpCommand("grok", path);
        int debugIndex = command.indexOf("--debug");
        assertTrue(debugIndex >= 0, "must include --debug: " + command);
        assertEquals("--debug-file", command.get(debugIndex + 1), "--debug-file must immediately follow --debug: " + command);
        assertEquals(path, command.get(debugIndex + 2), "the path must immediately follow --debug-file: " + command);
        assertTrue(command.contains("agent") && command.contains("stdio"), "the base command must be unchanged: " + command);
    }

    /**
     * Review finding (LOW): Grok's own {@code --debug-file} trace contains unredacted prompts and secrets
     * verbatim (it is Grok's own writer, not this plugin's redacted log). It must live under the session's
     * private config dir ({@code ~/.ai-coder/grok/{sessionId}/}), never {@code java.io.tmpdir} where any
     * local user/process could read it, and must be deleted when the session stops.
     */
    @Test
    void debugFile_livesUnderTheSessionsPrivateConfigDir_andIsDeletedOnStop() throws Exception {
        boolean previousDebug = PluginSettings.isDebugJson();
        PluginSettings.setDebugJson(true);
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                + "read -r _prompt\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}\\n'\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        File debugFile = PluginUtil.getPluginAiSessionConfigDir(AiTypeEnum.GROK, "s1").resolve("grok-acp-debug.log").toFile();
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof TurnCompleteEvent), "the turn to complete");

            String tmpDir = System.getProperty("java.io.tmpdir");
            assertFalse(debugFile.getAbsolutePath().startsWith(tmpDir),
                    "the debug file must never be under java.io.tmpdir: " + debugFile);
            assertTrue(debugFile.getParentFile().isDirectory(), "the session's own config dir must have been created");

            // The fake agent (a plain shell script) never actually writes the file Grok's real binary would;
            // write a stand-in so stop()'s delete has something real to prove itself against.
            Files.writeString(debugFile.toPath(), "unredacted prompt/secret trace, stand-in for Grok's own writer");
            assertTrue(debugFile.isFile());

            manager.stop();

            assertFalse(debugFile.exists(), "the debug file must be deleted once the session stops: " + debugFile);
        }
        finally {
            PluginSettings.setDebugJson(previousDebug);
            PluginUtil.deleteAiSessionConfigDir(AiTypeEnum.GROK, "s1");
        }
    }

    // ---- Boss's verify follow-up: a crash-nulled sessionConfigOptions must not look like a rejection ----
    @Test
    void reasoningEffortNotCleared_whenSessionConfigOptionsIsNullAtApplyTime() {
        TestableGrokAiProcessManager manager = new TestableGrokAiProcessManager(e -> {
        });
        manager.configureReasoningEffort("high", true);
        boolean[] clearedCallbackFired = {false};
        manager.setOnReasoningEffortCleared(() -> clearedCallbackFired[0] = true);
        // Simulates a crash's detachDeadConnection racing in between the publish and this call: no live
        // session to ask, not the agent rejecting "high".
        manager.sessionConfigOptions = null;

        manager.applyInitialConfigOptionsIfNeeded();

        assertEquals("high", manager.reasoningEffort,
                "a dead connection must not be mistaken for the agent rejecting the session's stored effort");
        assertFalse(clearedCallbackFired[0], "onReasoningEffortCleared must not fire for a connection that never asked Grok anything");
    }

    // ---- BigP_1: usage + per-modelId context window, driven over the real fake-agent wire end to end ----
    @Test
    void usageAndContextWindow_fromLiveFakeAgentWire() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        File script = fakeAgent(
                "read -r _session\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\",\"models\":{\"availableModels\":["
                + "{\"modelId\":\"grok-4.7\",\"name\":\"Grok 4.7\",\"_meta\":{\"totalContextTokens\":256000}},"
                + "{\"modelId\":\"grok-4.6\",\"name\":\"Grok 4.6\",\"_meta\":{\"totalContextTokens\":500000}}]}}}\\n'\n"
                + "read -r _prompt\n"
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\",\"_meta\":{\"totalTokens\":150,"
                + "\"modelId\":\"grok-4.7\"}}}\\n'\n"
                + "sleep 60\n");
        TestableGrokAiProcessManager manager = startManager(script, events);
        try {
            manager.sendPrompt("hello", WORK_DIR, List.of());
            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof GrokTokenUsageEvent), "a usage event from the live wire: " + events);
            GrokTokenUsageEvent usage = events.stream().filter(e -> e instanceof GrokTokenUsageEvent)
                    .map(e -> (GrokTokenUsageEvent) e).findFirst().orElseThrow();
            assertEquals(150, usage.currentTokens());
            assertEquals(256_000, usage.maxTokens(), "grok-4.7's own context window, read from the live handshake response");
            assertEquals("grok-4.7", usage.model());
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    /**
     * Captures each request's future without completing it, so a test decides when and how each response
     * arrives — mirrors {@code OpenCodeHandshakeRaceTest.StubConnection}.
     */
    private static final class CapturingStubConnection extends AcpConnection {

        final List<CompletableFuture<JsonObject>> requests = new CopyOnWriteArrayList<>();

        CapturingStubConnection() {
            super(new ByteArrayOutputStream(), InputStream.nullInputStream(),
                    new GrokAcpClientHandler(e -> {
                    }, () -> {
                    }));
        }

        @Override
        public CompletableFuture<JsonObject> sendRequest(AcpMethodEnum method, JsonObject params) {
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            requests.add(future);
            return future;
        }
    }

    /**
     * Captures each request's future instead of writing it, so a test decides when and how each response
     * arrives — mirrors {@code OpenCodeHandshakeRaceTest.StubConnection}.
     */
    private static final class StubConnection extends AcpConnection {

        final List<AcpMethodEnum> sentMethods = new CopyOnWriteArrayList<>();
        final List<JsonObject> sentParams = new CopyOnWriteArrayList<>();

        StubConnection() {
            super(new ByteArrayOutputStream(), InputStream.nullInputStream(),
                    new GrokAcpClientHandler(e -> {
                    }, () -> {
                    }));
        }

        @Override
        public CompletableFuture<JsonObject> sendRequest(AcpMethodEnum method, JsonObject params) {
            sentMethods.add(method);
            sentParams.add(params);
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            future.complete(new JsonObject());
            return future;
        }
    }

    /**
     * A fake {@code grok agent stdio}: answers {@code initialize} (id 1) the way the real agent does, then
     * runs {@code after} — which is responsible for answering (or not) session/new|session/load and
     * session/prompt as each test needs.
     */
    private static File fakeAgent(String after) throws IOException {
        File script = File.createTempFile("fake-grok-", ".sh");
        script.deleteOnExit();
        String body = "#!/bin/sh\n"
                      + "read -r _init\n"
                      + "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}\\n'\n"
                      + after;
        Files.writeString(script.toPath(), body, StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    private static File captureFile() throws IOException {
        File f = File.createTempFile("grok-capture-", ".txt");
        f.deleteOnExit();
        return f;
    }

    private static List<String> readCaptureFile(File f) {
        if (f == null || !f.isFile()) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            return lines.stream().filter(l -> !l.isBlank()).toList();
        }
        catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Routes process creation through a queue of real fake-agent scripts (spawned as real processes — the
     * script itself stands in for the grok CLI).
     */
    private static final class TestableGrokAiProcessManager extends GrokAiProcessManager {

        TestableGrokAiProcessManager(AiProcessEventListener listener) {
            super(listener);
        }

        boolean hasActiveProcess() {
            return process() != null;
        }

        Process process() {
            synchronized (this) {
                return currentProcess;
            }
        }

        void stopAndCloseConnections() {
            stop();
        }

        void armTurn(boolean turnInFlight) {
            running = true;
            processing = turnInFlight;
        }
    }

    /**
     * Holds the handshake at the publish point ({@code beforeHandshakePublish}, inherited from
     * {@code AbstractAcpProcessManager}) until released, mirroring {@code
     * OpenCodeHandshakeRaceTest.HeldHandshakeManager} — the race it pins is the one the two backends now
     * share one implementation for.
     */
    private static class HeldHandshakeManager extends GrokAiProcessManager {

        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<AcpConnection> connections = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        HeldHandshakeManager(AiProcessEventListener listener) {
            super(listener);
        }

        @Override
        protected void beforeHandshakePublish(AcpConnection conn) {
            connections.add(conn);
            threads.add(Thread.currentThread());
            if (threads.size() == 1) {
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * When set, the process's own onExit callback waits for it, so a test can have the handshake see a
         * crash before that callback has handled it. Calls made from a handshake thread are never held.
         */
        volatile CountDownLatch exitCallbackGate;

        @Override
        void handleProcessExit(Process dead) {
            CountDownLatch gate = exitCallbackGate;
            if (gate != null && !threads.contains(Thread.currentThread())) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.handleProcessExit(dead);
        }

        Process process() {
            synchronized (this) {
                return currentProcess;
            }
        }

        /**
         * stop() closes a published connection only after waiting, on a background thread, for the agent to
         * answer a shutdown it never will. Closing every connection here (close is idempotent) keeps their
         * acp-notify/acp-dispatch threads from outliving the test.
         */
        void stopAndCloseConnections() {
            stop();
            connections.forEach(AcpConnection::close);
        }

        void awaitHeld() throws InterruptedException {
            assertTrue(held.await(10, TimeUnit.SECONDS), "the handshake must reach the publish point");
        }

        void releaseAndJoinHeld() throws InterruptedException {
            release.countDown();
            threads.get(0).join(10_000);
            assertFalse(threads.get(0).isAlive(), "the held handshake thread must finish");
        }
    }
}
