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
        assertEquals("ok", toolResult.content());
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

        List<ChatResult> repeating = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            repeating.add(new ChatResult("",
                    List.of(new ChatToolCall("call-" + i, "GetPluginVersion", "{}")),
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
        final List<String> invokedToolNames = new ArrayList<>();

        TestOllamaProcessManager(AiProcessEventListener listener, HttpAiClient fakeClient) {
            super(listener);
            this.fakeClient = fakeClient;
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
                    return "ok";
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
