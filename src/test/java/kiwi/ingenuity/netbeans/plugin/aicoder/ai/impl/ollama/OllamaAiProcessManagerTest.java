package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import java.io.File;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.session.OllamaAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OllamaAiProcessManagerTest {

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
     * A stalled loop previously ended on a status line, leaving the user with no
     * reply to "hi" at all. The turn now makes one final request with no tools
     * offered, so the model can only answer in prose.
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

    /** "{}" is what the model produced after giving up; it must not be shown as the reply. */
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

    private static final class TestOllamaProcessManager extends OllamaAiProcessManager {

        private final HttpAiClient fakeClient;
        /** When set, every tool returns this — the "Description updated." case. */
        private final String fixedToolResult;
        final List<String> invokedToolNames = new ArrayList<>();

        TestOllamaProcessManager(AiProcessEventListener listener, HttpAiClient fakeClient) {
            this(listener, fakeClient, null);
        }

        TestOllamaProcessManager(AiProcessEventListener listener, HttpAiClient fakeClient,
                String fixedToolResult) {
            super(listener);
            this.fakeClient = fakeClient;
            this.fixedToolResult = fixedToolResult;
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
