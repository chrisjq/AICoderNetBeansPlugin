package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
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
     * qwen2.5-coder:14b answered "hi" by calling UpdateSessionDescription, got
     * back "Description updated.", and — having no signal that the work was
     * done — reissued the identical call every iteration. The tool must run
     * once and the turn must end promptly rather than at the iteration cap.
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
     * Observed with qwen2.5-coder:14b: it called UpdateSessionDescription four
     * times, varying the description each time, so an arguments-based guard
     * never matched — yet every call returned "Description updated." An
     * already-seen result means the call taught the model nothing.
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
     * A stalled loop previously ended on a status line, leaving the user with
     * no reply to "hi" at all. The turn now makes one final request with no
     * tools offered, so the model can only answer in prose.
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
     * "{}" is what the model produced after giving up; it must not be shown as
     * the reply.
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
