package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.HttpAiClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiHttpStatusException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiToolCallParseException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.AbstractChatContextBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextBrokerSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTrimStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.OllamaChatContextBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.session.OllamaAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.ContextPersistenceManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaAiProcessManagerTest {

    private static int countOccurrences(String text, String target) {
        if (text == null || target == null || target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    private static AiSession newSession() {
        return new AiSession("sid", "session", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL
                        .createDefaultSettings(),
                Instant.now(), Instant.now());
    }

    private static void awaitIdle(OllamaAiProcessManager manager) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (manager.isProcessing() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertFalse(manager.isProcessing(), "turn did not finish within 5s");
    }

    /**
     * Serves a fixed {@code /api/tags} body (and an empty {@code /v1/models} list) on an ephemeral loopback
     * port — never the real Ollama, per the standing "do not hammer the box" instruction. A fresh ephemeral
     * port per call keeps the discovery cache entry isolated from every other test.
     */
    private static HttpServer startFakeOllamaDiscoveryServer(String tagsResponseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = tagsResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    @TempDir
    Path contextTempDir;

    @Test
    void toolCallThenFinalTextLoopsAndTerminates() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{}}",
                        List.of(), "stop"),
                new ChatResult("Finished.", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        AiSession shared = new AiSession("sid", "session", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, fakeClient.requests.size());
        ChatRequest secondRequest = fakeClient.requests.get(1);
        ChatMessage assistantToolCall = secondRequest.messages().get(2);
        assertEquals(ChatRole.ASSISTANT, assistantToolCall.role());
        assertEquals(1, assistantToolCall.toolCalls().size());
        assertEquals("call_0", assistantToolCall.toolCalls().get(0).id());
        assertEquals("GetPluginVersion", assistantToolCall.toolCalls().get(0).name());
        ChatMessage toolResult = secondRequest.messages().get(3);
        assertEquals(ChatRole.TOOL, toolResult.role());
        assertEquals("call_0", toolResult.toolCallId());
        assertEquals("ok 1", toolResult.content());
        // Prose final answer must be streamed exactly once
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td && td.text().contains("Finished.")));
        // JSON tool-call content must NOT be emitted as a TextDeltaEvent
        assertFalse(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td && td.text().contains("GetPluginVersion")));
        assertInstanceOf(TurnCompleteEvent.class, events.get(events.size() - 1));
    }

    @Test
    void stopsAtIterationCap() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        // Arguments must differ per call: identical calls are caught by the
        // repeat guard long before the iteration cap is reached.
        List<ChatResult> repeating = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            repeating.add(new ChatResult("",
                    List.of(new ChatToolCall("call-" + i, "GetPluginVersion", "{\"n\":" + i + "}")),
                    "tool_calls"));
        }
        FakeHttpClient fakeClient = new FakeHttpClient(repeating);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        AiSession shared = new AiSession("sid", "session", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(25, manager.invokedToolNames.size());
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("Stopped after 25 tool iterations")));
    }

    /**
     * qwen2.5-coder:14b answered "hi" by calling UpdateSessionDescription, got back "Description updated.",
     * and — having no signal that the work was done — reissued the identical call every iteration. The tool
     * must run once and the turn must end promptly rather than at the iteration cap.
     */
    @Test
    void stopsWhenModelRepeatsTheSameCall() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        List<ChatResult> identical = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            identical.add(new ChatResult("",
                    List.of(new ChatToolCall("call-" + i, "GetPluginVersion", "{}")),
                    "tool_calls"));
        }
        TestOllamaProcessManager manager
                = new TestOllamaProcessManager(listener, new FakeHttpClient(identical));
        AiSession shared = new AiSession("sid3", "session3", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, manager.invokedToolNames.size(),
                "a repeated identical call must not re-run the tool");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("kept repeating the same tool call")));
        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("Stopped after 25")),
                "should end on the repeat guard, not grind out the iteration cap");
    }

    /**
     * Observed with qwen2.5-coder:14b: it called UpdateSessionDescription four times, varying the description
     * each time, so an arguments-based guard never matched — yet every call returned "Description updated."
     * An already-seen result means the call taught the model nothing.
     */
    @Test
    void stopsWhenRepeatedCallsVaryArgsButReturnTheSameResult() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        List<ChatResult> varying = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            varying.add(new ChatResult("",
                    List.of(new ChatToolCall("call-" + i, "GetPluginVersion",
                            "{\"description\":\"attempt " + i + "\"}")),
                    "tool_calls"));
        }
        TestOllamaProcessManager manager = new TestOllamaProcessManager(
                listener, new FakeHttpClient(varying), "Description updated.");
        AiSession shared = new AiSession("sid4", "session4", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(manager.invokedToolNames.size() <= 3,
                "identical results must end the turn quickly, got " + manager.invokedToolNames.size());
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("kept repeating the same tool call")));
    }

    /**
     * A stalled loop previously ended on a status line, leaving the user with no reply to "hi" at all. The
     * turn now makes one final request with no tools offered, so the model can only answer in prose.
     */
    @Test
    void stalledLoopStillProducesAnAnswer() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        List<ChatResult> scripted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            scripted.add(new ChatResult("",
                    List.of(new ChatToolCall("c" + i, "GetPluginVersion", "{\"d\":\"v" + i + "\"}")),
                    "tool_calls"));
        }
        scripted.add(new ChatResult("Hello! How can I help with your project?", List.of(), "stop"));

        FakeHttpClient fake = new FakeHttpClient(scripted);
        TestOllamaProcessManager manager
                = new TestOllamaProcessManager(listener, fake, "Description updated.");
        AiSession shared = new AiSession("sid6", "session6", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("How can I help")),
                "the user must get a real answer, not just a status line");
        // The recovery request must offer no tools, or the model can stall again.
        ChatRequest recovery = fake.requests.get(fake.requests.size() - 1);
        assertTrue(recovery.toolSchemas().isEmpty(), "final request must offer no tools");
    }

    /**
     * "{}" is what the model produced after giving up; it must not be shown as the reply.
     */
    @Test
    void emptyJsonObjectIsNotPresentedAsTheAnswer() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                done.countDown();
            }
        };

        FakeHttpClient fake = new FakeHttpClient(List.of(new ChatResult("{}", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fake);
        AiSession shared = new AiSession("sid5", "session5", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("{}")),
                "a bare {} must never reach the user as the assistant's reply");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("empty response")));
    }

    @Test
    void emptyJsonDetectionLeavesRealAnswersAlone() {
        assertTrue(OllamaAiProcessManager.isEmptyJson("{}"));
        assertTrue(OllamaAiProcessManager.isEmptyJson("  []  "));
        assertTrue(OllamaAiProcessManager.isEmptyJson("```json\n{}\n```"));
        assertFalse(OllamaAiProcessManager.isEmptyJson("{\"answer\":42}"),
                "a user can ask for JSON and must still receive it");
        assertFalse(OllamaAiProcessManager.isEmptyJson("Hello!"));
        assertFalse(OllamaAiProcessManager.isEmptyJson(""));
    }

    @Test
    void cancelDoesNotEmitCapMessageOrTurnComplete() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        List<AiProcessEvent> events = new ArrayList<>();

        OllamaAiProcessManager[] ref = {null};

        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.STOPPED) {
                stopped.countDown();
            }
        };

        HttpAiClient cancelClient = (ChatRequest request, Consumer<String> onTextDelta) -> {
            if (ref[0] != null) {
                ref[0].interrupt(InterruptTypeEnum.Cancel);
            }
            return new ChatResult("",
                    List.of(new ChatToolCall("c0", "GetPluginVersion", "{}")),
                    "tool_calls");
        };

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, cancelClient);
        ref[0] = manager;

        AiSession shared = new AiSession("sid2", "session2", null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL,
                null,
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(),
                Instant.now(), Instant.now());
        manager.setCurrentSession(shared);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());

        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        Thread turnThread = manager.activeTurnThread;
        if (turnThread != null) {
            turnThread.join(2000);
        }

        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("Stopped after 25")));
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent));
        assertTrue(manager.invokedToolNames.isEmpty());
    }

    @Test
    void historySurvivesAcrossTurns() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(2, fakeClient.requests.size());
        StringBuilder second = new StringBuilder();
        for (ChatMessage m : fakeClient.requests.get(1).messages()) {
            second.append(m.content() == null ? "" : m.content()).append('\n');
        }
        String text = second.toString();

        assertTrue(text.contains("turn one"),
                "the second request must still carry the first user message");
        assertTrue(text.contains("first answer"),
                "the second request must still carry the first assistant answer");
        assertTrue(text.contains("turn two"));
    }

    @Test
    void anIoExceptionMidTurnDoesNotBrickTheSession() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FailFirstHttpClient client = new FailFirstHttpClient(List.of(
                new ChatResult("answer after recovery", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn that fails", home, List.of());
        awaitIdle(manager);

        manager.sendPrompt("turn that should still work", home, List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size(),
                "the second turn must reach the client — a stuck open turn would throw "
                + "IllegalStateException in beginTurn() and never get here");
        StringBuilder second = new StringBuilder();
        for (ChatMessage m : client.requests.get(1).messages()) {
            second.append(m.content() == null ? "" : m.content()).append('\n');
        }
        assertFalse(second.toString().contains("turn that fails"),
                "a turn that failed must leave no trace in history");
    }

    @Test
    void theStopCallingToolsPromptNeverEntersHistory() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{\"d\":\"a\"}}",
                        List.of(), "stop"),
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{\"d\":\"b\"}}",
                        List.of(), "stop"),
                new ChatResult("{\"name\":\"GetPluginVersion\",\"arguments\":{\"d\":\"c\"}}",
                        List.of(), "stop"),
                new ChatResult("fine, here is your answer", List.of(), "stop"),
                new ChatResult("second turn answer", List.of(), "stop")));

        TestOllamaProcessManager manager
                = new TestOllamaProcessManager(listener, fakeClient, "Description updated.");
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("do something repetitive", home, List.of());
        awaitIdle(manager);
        manager.sendPrompt("next question", home, List.of());
        awaitIdle(manager);

        ChatRequest last = fakeClient.requests.get(fakeClient.requests.size() - 1);
        StringBuilder all = new StringBuilder();
        for (ChatMessage m : last.messages()) {
            String c = m.content() == null ? "" : m.content();
            assertFalse(c.contains("Stop calling tools"),
                    "the synthetic fallback prompt must never be persisted into history");
            all.append(c).append('\n');
        }
        assertTrue(all.toString().contains("fine, here is your answer"),
                "the answer the fallback produced must be kept");
    }

    /**
     * The live bug: Ollama's server rejected the whole request with HTTP 500 because the model's tool call
     * was not valid JSON ("error parsing tool call: raw='...', err=invalid character '?' ..."). Before the
     * fix the turn died with "Failed to send: HTTP 500 ...". The parse error must be fed back to the model as
     * a tool result so it can repair the call, and the turn must survive and still deliver its answer.
     */
    @Test
    void serverSideToolCallParseFailureIsRecoveredAndTheTurnSurvives() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        ToolCallParse500ThenAnswerClient client = new ToolCallParse500ThenAnswerClient();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size(),
                "one parse-failure request, then the repaired retry — the turn must survive, not die on the 500");
        ChatRequest repaired = client.requests.get(1);
        ChatMessage recoveryAssistant = repaired.messages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT
                        && m.toolCalls().size() == 1
                        && "unknown_tool".equals(m.toolCalls().get(0).name()))
                .findFirst().orElse(null);
        assertTrue(recoveryAssistant != null, "the repaired request must carry the synthetic malformed-call pair");
        String callId = recoveryAssistant.toolCalls().get(0).id();
        assertTrue(callId.startsWith("call_malformed_"), "the synthetic call id must be unique per malformed call");
        ChatMessage recoveryResult = repaired.messages().stream()
                .filter(m -> m.role() == ChatRole.TOOL && callId.equals(m.toolCallId()))
                .findFirst().orElse(null);
        assertTrue(recoveryResult != null, "the parse error must be paired to the synthetic call by id");
        assertTrue(recoveryResult.content().contains("not valid JSON"),
                "the model must be told its tool call was malformed: " + recoveryResult.content());

        assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED),
                "a recoverable parse failure must not fail the turn");
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent td
                && td.text() != null && td.text().contains("Understood")),
                "the turn must still deliver its final answer");
        assertInstanceOf(TurnCompleteEvent.class, events.get(events.size() - 1));
    }

    /**
     * A model that keeps emitting unparseable tool calls must not spin forever: two consecutive server-side
     * parse failures hit the bound and the turn fails exactly as an unrelated transport 500 would today.
     */
    @Test
    void persistentServerSideToolCallParseFailureHitsTheBoundAndFailsLikeATransportError() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        ToolCallParse500EveryTimeClient client = new ToolCallParse500EveryTimeClient();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size(),
                "two parse-failure rounds reach the bound; a third request must never be made");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED
                && se.text() != null && se.text().contains("Failed to send") && se.text().contains("HTTP 500")),
                "after the bound the turn must fail as a transport error would");
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent),
                "a failed turn must not complete normally");
    }

    /**
     * An unrelated 500 (no tool-call parse marker) must keep failing the turn exactly as it did before —
     * nothing is recovered, no synthetic pair is fabricated.
     */
    @Test
    void anUnrelatedHttp500StillFailsTheTurnUntouched() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        Plain500Client client = new Plain500Client();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(1, client.requests.size(), "an unrelated 500 must fail on the first request");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED
                && se.text() != null && se.text().contains("Failed to send") && se.text().contains("HTTP 500")),
                "an unrelated 500 must still fail the turn");
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent),
                "a failed turn must not complete normally");
        assertEquals(0, manager.invokedToolNames.size(),
                "no tool must run for a turn that never produced a parseable tool call");
    }

    @Test
    void pinnedContextReachesTheBrokerAndIsNotDuplicatedPerTurn() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.updatePinnedContext("IDENTITY-BLOCK", "BASELINE-BLOCK", null);
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);

        // Identical second call must not duplicate the blocks (upsertPin is idempotent).
        manager.updatePinnedContext("IDENTITY-BLOCK", "BASELINE-BLOCK", null);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(2, fakeClient.requests.size());
        String system = fakeClient.requests.get(1).messages().get(0).content();
        assertEquals(1, countOccurrences(system, "IDENTITY-BLOCK"),
                "IDENTITY pin must appear exactly once after two identical upserts");
        assertEquals(1, countOccurrences(system, "BASELINE-BLOCK"),
                "BASELINE pin must appear exactly once after two identical upserts");
        assertTrue(system.indexOf("IDENTITY-BLOCK") < system.indexOf("BASELINE-BLOCK"),
                "IDENTITY slot is declared before BASELINE in PinSlotEnum so it must render first");
    }

    @Test
    void reportedUsageReachesTheBrokerAndCalibratesTheEstimator() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("answer", List.of(), "stop", 5000, 20)));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hello", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertTrue(manager.broker.hasSeenReportedUsage(),
                "a backend that reports usage must be recorded as doing so");
        assertTrue(manager.broker.calibrationRatio() > 1.0d,
                "a large reported prompt_tokens must push the estimate upward");
    }

    @Test
    void sessionSettingsOverrideGlobalDefaultsInTheBroker() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("a", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);

        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(1234);
        ((OllamaSessionSettings) s.settings()).setContextTrimStrategy(ContextTrimStrategyEnum.DROP);
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");

        assertEquals(1234, manager.brokerSettingsForTest().tokenThreshold());
        assertEquals(ContextTrimStrategyEnum.DROP, manager.brokerSettingsForTest().strategy());
    }

    @Test
    void summariserWiredInStartIsUsedWhenSummariseStrategyTrims() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("a short summary of turn one", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setContextTrimStrategy(ContextTrimStrategyEnum.SUMMARISE);
        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(1);
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(3, fakeClient.requests.size(),
                "turn two's trim must trigger one extra summariser call in between");
        boolean sawSummary = fakeClient.requests.get(2).messages().stream()
                .anyMatch(m -> m.content() != null
                        && m.content().contains("[Summary of earlier conversation:"));
        assertTrue(sawSummary, "the summariser wired unconditionally in start() must be used "
                + "by the broker's SUMMARISE trim path");
    }

    @Test
    void compactContextRunsOffTheCallingThreadAndReportsUsageWhenDone() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CountDownLatch usageReported = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof OllamaTokenUsageEvent) {
                usageReported.countDown();
            }
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of());
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");

        manager.compactContext();

        assertTrue(usageReported.await(5, TimeUnit.SECONDS),
                "compactContext must report usage once its background thread finishes, "
                + "the same way the info bar learns a turn finished");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.text() != null && se.text().contains("Nothing to compact")),
                "an empty broker has nothing to compact");
        assertFalse(manager.isSummarising(),
                "summarising must have cleared by the time usage is reported");
    }

    @Test
    void contextIsSavedOnStopAndRestoredOnStartWhenEnabled() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient client = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setContextPersistOnClose(true);

        TestOllamaProcessManager first = new TestOllamaProcessManager(listener, client);
        first.setContextBaseDir(contextTempDir);
        first.setCurrentSession(session);
        first.start(null, "qwen2.5-coder:7b");
        first.sendPrompt("remember this", new File(System.getProperty("user.home")), List.of());
        awaitIdle(first);
        first.stop();

        TestOllamaProcessManager second = new TestOllamaProcessManager(listener, client);
        second.setContextBaseDir(contextTempDir);
        second.setCurrentSession(session);
        second.start(null, "qwen2.5-coder:7b");

        assertTrue(second.broker.entryCount() > 0,
                "history must survive a stop/start cycle when persistence is enabled");
    }

    @Test
    void contextIsNotSavedWhenPersistenceIsOff() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient client = new FakeHttpClient(List.of(
                new ChatResult("answer", List.of(), "stop")));

        AiSession session = newSession();
        ((OllamaSessionSettings) session.settings()).setContextPersistOnClose(false);

        TestOllamaProcessManager first = new TestOllamaProcessManager(listener, client);
        first.setContextBaseDir(contextTempDir);
        first.setCurrentSession(session);
        first.start(null, "qwen2.5-coder:7b");
        first.sendPrompt("do not remember", new File(System.getProperty("user.home")), List.of());
        awaitIdle(first);
        first.stop();

        TestOllamaProcessManager second = new TestOllamaProcessManager(listener, client);
        second.setContextBaseDir(contextTempDir);
        second.setCurrentSession(session);
        second.start(null, "qwen2.5-coder:7b");

        assertEquals(0, second.broker.entryCount());
    }

    @Test
    void tokenThresholdChangeBetweenTurnsIsPickedUpOnTheNextTurn() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(9000);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);
        assertEquals(9000, manager.brokerSettingsForTest().tokenThreshold());

        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(4321);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertEquals(4321, manager.brokerSettingsForTest().tokenThreshold(),
                "a threshold change must take effect on the very next turn, without "
                + "restarting the session");
    }

    @Test
    void loweringThresholdBetweenTurnsCausesTheBrokerToActuallyTrimOnTheNextTurn() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(
                new ChatResult("first answer", List.of(), "stop"),
                new ChatResult("second answer", List.of(), "stop")));

        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setContextTrimStrategy(ContextTrimStrategyEnum.DROP);
        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(1_000_000);
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");

        File home = new File(System.getProperty("user.home"));
        manager.sendPrompt("turn one", home, List.of());
        awaitIdle(manager);
        int entriesAfterTurnOne = manager.broker.entryCount();
        assertEquals(2, entriesAfterTurnOne, "turn one commits with no trimming under a huge threshold");

        ((OllamaSessionSettings) s.settings()).setContextTokenThreshold(1);
        manager.sendPrompt("turn two", home, List.of());
        awaitIdle(manager);

        assertTrue(manager.broker.entryCount() < entriesAfterTurnOne + 2,
                "lowering the threshold between turns must cause the very next turn's trim to "
                + "actually evict, not merely record the new number with no effect");
    }

    @Test
    void reasoningEffortIsOmittedFromTheRequestWhenNotSet() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(new ChatResult("answer", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        manager.setCurrentSession(newSession());
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertNull(fakeClient.requests.get(0).reasoningEffort());
    }

    @Test
    void reasoningEffortFromSessionSettingsIsSentWithTheExactValue() throws Exception {
        AiProcessEventListener listener = event -> {
        };
        FakeHttpClient fakeClient = new FakeHttpClient(List.of(new ChatResult("answer", List.of(), "stop")));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setReasoningEffort("high");
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals("high", fakeClient.requests.get(0).reasoningEffort());
    }

    /**
     * The spec's Ollama section is explicit that the field name is unverified against a real server: a 4xx
     * carrying reasoning_effort must be retried exactly once without it, the user told exactly once, and the
     * field must never be sent again for the rest of the session — not even on a later turn.
     */
    @Test
    void reasoningEffortRejectedWith4xxIsRetriedOnceAndNeverSentAgainThisSession() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        RejectReasoningEffortHttpClient client
                = new RejectReasoningEffortHttpClient(new ChatResult("answer", List.of(), "stop"));
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setReasoningEffort("high");
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(2, client.requests.size(), "must retry exactly once");
        assertEquals("high", client.requests.get(0).reasoningEffort());
        assertNull(client.requests.get(1).reasoningEffort(), "the retry must omit the field");
        long infoCount = events.stream().filter(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.INFO
                && se.text() != null && se.text().contains(OpenAiJsonKeyEnum.REASONING_EFFORT.key())).count();
        assertEquals(1, infoCount, "exactly one INFO event for the rejection");

        manager.sendPrompt("hi again", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(3, client.requests.size());
        assertNull(client.requests.get(2).reasoningEffort(),
                "once disabled for the session, a later turn must not resend it or retry again");
    }

    /**
     * Regression test for a review finding: the barren-rounds exit (two consecutive tool-call rounds that
     * make no progress) used to pass a STALE per-iteration reasoning-effort snapshot into answerWithoutTools
     * instead of re-resolving it. A 4xx rejection earlier in the SAME turn sets the sticky disabled flag
     * mid-loop, so that stale snapshot could put the field back on the wire for the fallback no-tools request
     * — a second failed request and a second INFO event in the same turn, breaking both "never resend once
     * disabled" and "exactly one INFO".
     *
     * <p>
     * Expected request sequence (0-based indices into
     * {@link RejectReasoningEffortThenBarrenLoopHttpClient#requests}, traced against
     * {@code runTurn}/{@code chatWithReasoningEffortRetry}/{@code answerWithoutTools}; Ollama is
     * {@code TOOL_CALLS_VIA_SCHEMA}, so {@code requestTools} is {@code List.of()} for every request in the
     * turn — {@code toolSchemas()} cannot be used to distinguish the tool-call rounds from the fallback
     * round, unlike a request's ordinal):
     * <ul>
     * <li>0: iteration 0's original attempt, {@code reasoningEffort="high"} — rejected with HTTP 400, firing
     * the one INFO event and setting the sticky disabled flag.</li>
     * <li>1: iteration 0's retry (same {@code chatWithReasoningEffortRetry} call),
     * {@code reasoningEffort=null} — returns a tool call, executed for the first time (madeProgress=true,
     * barrenRounds stays 0).</li>
     * <li>2: iteration 1, {@code reasoningEffort=null} (the flag already suppresses it) — the SAME tool call
     * again, deduplicated by {@code executedCalls} (no invoke, madeProgress=false, barrenRounds=1).</li>
     * <li>3: iteration 2, {@code reasoningEffort=null} — the SAME tool call again, deduplicated again
     * (barrenRounds=2), which triggers the barren-rounds exit and calls {@code answerWithoutTools}.</li>
     * <li>4: {@code answerWithoutTools}'s no-tools fallback request — must carry {@code reasoningEffort=null}
     * (the bug under test); returns a real text answer so the turn completes normally.</li>
     * </ul>
     */
    @Test
    void barrenRoundsExitAfterAMidTurnRejectionNeverResendsReasoningEffortToTheFallbackRequest() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiProcessEventListener listener = events::add;
        RejectReasoningEffortThenBarrenLoopHttpClient client = new RejectReasoningEffortThenBarrenLoopHttpClient();
        TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, client);
        AiSession s = newSession();
        ((OllamaSessionSettings) s.settings()).setReasoningEffort("high");
        manager.setCurrentSession(s);
        manager.start(null, "qwen2.5-coder:7b");
        manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
        awaitIdle(manager);

        assertEquals(5, client.requests.size(),
                "expected exactly 5 requests: reject, retry, two repeated barren rounds, then the fallback — "
                + "a different count means the barren-rounds exit was not reached the way this test assumes");
        assertEquals("high", client.requests.get(0).reasoningEffort());
        assertNull(client.requests.get(4).reasoningEffort(),
                "the barren-rounds fallback request must never carry a reasoning effort the session has "
                + "already been told the server rejects");

        long infoCount = events.stream().filter(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.INFO
                && se.text() != null && se.text().contains(OpenAiJsonKeyEnum.REASONING_EFFORT.key())).count();
        assertEquals(1, infoCount,
                "exactly one INFO event for the whole turn — not a second one from the fallback request");
    }

    /**
     * Confirms the interaction between pre-flight capability discovery and the 4xx retry backstop, per the
     * review round: with discovery in place, a session-pinned level on a model discovery has POSITIVELY
     * confirmed cannot think must be stripped BEFORE the request is ever built, so it never reaches
     * {@code chatWithReasoningEffortRetry} and the 4xx path never fires for it — exactly one INFO in total
     * (the pre-flight one), not two.
     */
    @Test
    void sessionPinnedLevelOnAKnownNonThinkingModelFiresExactlyOneInfoAndNeverReachesTheWire() throws Exception {
        HttpServer discoveryServer = startFakeOllamaDiscoveryServer(
                "{\"models\":[{\"name\":\"qwen2.5-coder:7b\",\"capabilities\":[\"completion\",\"tools\"]}]}");
        try {
            String baseUrl = "http://" + discoveryServer.getAddress().getHostString() + ":" + discoveryServer.getAddress().getPort();
            CountDownLatch discovered = new CountDownLatch(1);
            OllamaModelDiscovery.discoverAsync(baseUrl, models -> discovered.countDown(), hint -> {
            });
            assertTrue(discovered.await(5, TimeUnit.SECONDS), "discovery did not complete");

            List<AiProcessEvent> events = new ArrayList<>();
            AiProcessEventListener listener = events::add;
            FakeHttpClient fakeClient = new FakeHttpClient(List.of(new ChatResult("answer", List.of(), "stop")));
            TestOllamaProcessManager manager = new TestOllamaProcessManager(listener, fakeClient);
            AiSession s = newSession();
            ((OllamaSessionSettings) s.settings()).setBaseUrl(baseUrl);
            ((OllamaSessionSettings) s.settings()).setReasoningEffort("high");
            manager.setCurrentSession(s);
            manager.start(null, "qwen2.5-coder:7b");
            manager.sendPrompt("hi", new File(System.getProperty("user.home")), List.of());
            awaitIdle(manager);

            assertEquals(1, fakeClient.requests.size(),
                    "the pre-flight clear must strip the field before the request is even built — one clean "
                    + "request, not a reject-then-retry pair");
            assertNull(fakeClient.requests.get(0).reasoningEffort());

            long infoCount = events.stream().filter(e -> e instanceof StatusEvent se
                    && se.type() == StatusEventTypeEnum.INFO).count();
            assertEquals(1, infoCount, "exactly one INFO in total for the whole turn — the pre-flight clear, "
                    + "with no second one from the (never-reached) 4xx retry path");
            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se && se.text() != null
                    && se.text().contains("Thinking") && se.text().contains("not supported")),
                    "the one INFO must be the pre-flight clear's own message");
        } finally {
            discoveryServer.stop(0);
        }
    }

    /**
     * Rejects only the FIRST request (ordinal 0, the one still carrying {@code reasoningEffort="high"}) with
     * a 400, then scripts the SAME tool call for ordinals 1-3 — driving two consecutive barren rounds — and a
     * real text answer from ordinal 4 onward (the no-tools fallback request from {@code answerWithoutTools}).
     * Branches on request ORDINAL rather than {@code toolSchemas()}/emptiness: Ollama is
     * {@code TOOL_CALLS_VIA_SCHEMA} ({@code AiTypeEnum.OLLAMA_LOCAL}), so {@code requestTools} is
     * {@code List.of()} for every request in an Ollama turn ({@code OllamaAiProcessManager.runTurn}:
     * {@code requestTools = schemaMode ? List.of() : tools}) — {@code toolSchemas()} is constant across every
     * request for this session type and cannot tell the tool-call rounds apart from the fallback round.
     */
    private static final class RejectReasoningEffortThenBarrenLoopHttpClient implements HttpAiClient {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            int ordinal = requests.size();
            requests.add(request);
            if (ordinal == 0) {
                throw new OpenAiHttpStatusException(400, "bad request: unknown field reasoning_effort");
            }
            if (ordinal <= 3) {
                // The retry, then two more rounds of the model repeating the SAME call — drives barrenRounds to 2.
                return new ChatResult("", List.of(new ChatToolCall("call-x", "GetPluginVersion", "{}")), "tool_calls");
            }
            // ordinal 4: the no-tools fallback request from answerWithoutTools.
            String text = "fine, here is your answer";
            onTextDelta.accept(text);
            return new ChatResult(text, List.of(), "stop");
        }
    }

    /**
     * Rejects any request carrying reasoning_effort with a 400, exactly the way a server that only
     * understands Ollama's native {@code think} parameter would behave against the OpenAI-compatible
     * endpoint.
     */
    private static final class RejectReasoningEffortHttpClient implements HttpAiClient {

        private final ChatResult okResult;
        private final List<ChatRequest> requests = new ArrayList<>();

        RejectReasoningEffortHttpClient(ChatResult okResult) {
            this.okResult = okResult;
        }

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            requests.add(request);
            if (request.reasoningEffort() != null) {
                throw new OpenAiHttpStatusException(400, "bad request: unknown field reasoning_effort");
            }
            if (okResult.assistantText() != null && !okResult.assistantText().isBlank()) {
                onTextDelta.accept(okResult.assistantText());
            }
            return okResult;
        }
    }

    private static final class FakeHttpClient implements HttpAiClient {

        private final List<ChatResult> scripted;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index = 0;

        FakeHttpClient(List<ChatResult> scripted) {
            this.scripted = scripted;
        }

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) {
            requests.add(request);
            ChatResult result = scripted.get(index++);
            if (result.assistantText() != null && !result.assistantText().isBlank()) {
                onTextDelta.accept(result.assistantText());
            }
            return result;
        }
    }

    private static final class FailFirstHttpClient implements HttpAiClient {

        private final List<ChatResult> scripted;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index = 0;

        FailFirstHttpClient(List<ChatResult> scripted) {
            this.scripted = scripted;
        }

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta)
                throws IOException {
            requests.add(request);
            if (index == 0) {
                index++;
                throw new IOException("stream cut");
            }
            ChatResult result = scripted.get(index++ - 1);
            if (result.assistantText() != null && !result.assistantText().isBlank()) {
                onTextDelta.accept(result.assistantText());
            }
            return result;
        }
    }

    /**
     * Serves one server-side tool-call parse failure (HTTP 500, Ollama's "error parsing tool call" shape),
     * then a normal schema reply.
     */
    private static final class ToolCallParse500ThenAnswerClient implements HttpAiClient {

        private final List<ChatRequest> requests = new ArrayList<>();
        private int index = 0;

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            requests.add(request);
            if (index++ == 0) {
                throw new OpenAiToolCallParseException(500,
                        "HTTP 500 from https://ollama/v1/chat/completions: "
                        + "{\"error\":{\"message\":\"error parsing tool call: raw='hello ? world', "
                        + "err=invalid character '?' after object key:value pair\",\"type\":\"api_error\"}}",
                        "invalid character '?' after object key:value pair");
            }
            return new ChatResult("{\"message\":\"Understood — let me fix that.\",\"tool_name\":\"\"}",
                    List.of(), "stop");
        }
    }

    /**
     * Serves a server-side tool-call parse failure on every request.
     */
    private static final class ToolCallParse500EveryTimeClient implements HttpAiClient {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            requests.add(request);
            throw new OpenAiToolCallParseException(500,
                    "HTTP 500 from https://ollama/v1/chat/completions: "
                    + "{\"error\":{\"message\":\"error parsing tool call: raw='hello ? world', "
                    + "err=invalid character '?' after object key:value pair\",\"type\":\"api_error\"}}",
                    "invalid character '?' after object key:value pair");
        }
    }

    /**
     * Serves an ordinary 500 with no tool-call parse marker.
     */
    private static final class Plain500Client implements HttpAiClient {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
            requests.add(request);
            throw new OpenAiHttpStatusException(500,
                    "HTTP 500 from https://ollama/v1/chat/completions: {\"error\":\"upstream exploded\"}");
        }
    }

    private static final class TestOllamaProcessManager extends OllamaAiProcessManager {

        private final HttpAiClient fakeClient;
        /**
         * When set, every tool returns this — the "Description updated." case.
         */
        private final String fixedToolResult;
        final List<String> invokedToolNames = new ArrayList<>();
        private volatile Path contextBaseDir;

        TestOllamaProcessManager(AiProcessEventListener listener, HttpAiClient fakeClient) {
            this(listener, fakeClient, null);
        }

        TestOllamaProcessManager(AiProcessEventListener listener, HttpAiClient fakeClient,
                String fixedToolResult) {
            super(listener);
            this.fakeClient = fakeClient;
            this.fixedToolResult = fixedToolResult;
        }

        /**
         * Only needed by tests that enable contextPersistOnClose.
         */
        void setContextBaseDir(Path contextBaseDir) {
            this.contextBaseDir = contextBaseDir;
        }

        @Override
        ContextPersistenceManager createContextPersistenceManager() {
            return new ContextPersistenceManager(contextBaseDir);
        }

        @Override
        boolean registerMcp(OllamaMcpRegistrar reg) {
            return true;
        }

        @Override
        HttpAiClient createHttpAiClient() {
            return fakeClient;
        }

        @Override
        OllamaMcpBridge createBridge(OllamaAiSession session) {
            return new OllamaMcpBridge(session) {
                @Override
                protected String executeTool(String toolName, JsonObject argsWithAuth) {
                    invokedToolNames.add(toolName);
                    // Distinct per call by default: a repeated result now means
                    // "no progress" and ends the turn, tested separately.
                    return fixedToolResult != null ? fixedToolResult : "ok " + invokedToolNames.size();
                }
            };
        }

        @Override
        AbstractChatContextBroker createContextBroker(String sessionId, ContextBrokerSettings settings) {
            return new OllamaChatContextBroker(sessionId, settings);
        }

        @Override
        Map<McpToolEnum, McpToolInterface> buildToolHandlers(OllamaAiSession session) {
            return Map.of(McpToolEnum.GET_PLUGIN_VERSION, new McpToolInterface() {
                @Override
                public McpSectionEnum section() {
                    return McpSectionEnum.SYSTEM;
                }

                @Override
                public String instruction(Set<McpInstructionOptionEnum> options) {
                    return "GetPluginVersion -> fake";
                }

                @Override
                public boolean isMutating() {
                    return false;
                }

                @Override
                public JsonObject schema(Set<McpInstructionOptionEnum> options) {
                    JsonObject tool = new JsonObject();
                    tool.addProperty("name", McpToolEnum.GET_PLUGIN_VERSION.toolName());
                    JsonObject inputSchema = new JsonObject();
                    inputSchema.addProperty("type", "object");
                    inputSchema.add("properties", new JsonObject());
                    tool.add("inputSchema", inputSchema);
                    return tool;
                }

                @Override
                public String handle(ToolRequestArguments args,
                        kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession session) {
                    return "ok";
                }
            });
        }
    }
}
