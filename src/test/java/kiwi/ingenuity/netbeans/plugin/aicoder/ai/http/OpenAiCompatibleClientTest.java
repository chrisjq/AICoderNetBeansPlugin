package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {

    /**
     * The follow-up request sent after a malformed schema-mode tool call has been answered with an error.
     * <p>
     * Under TOOL_CALLS_VIA_SCHEMA the tools array is deliberately empty, so this history carries a tool call for a
     * function the request never declares. That is not new — every successful schema-mode call does the same thing with
     * a real tool name — but the recovery path is the case where the name is not a real tool at all, and two reviewers
     * independently expected a backend to reject it. What we can pin locally is that we emit a well-formed pair: the
     * result must carry the id of the call it answers, or the backend cannot match them and the model never sees the
     * correction it is meant to act on.
     */
    @Test
    void malformedCallRecoveryPairSerialisesWithAMatchingToolCallId() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            String callId = "call_malformed_1";
            List<ChatMessage> history = List.of(
                    new ChatMessage(ChatRole.USER, "Message the other session.", List.of(), null),
                    new ChatMessage(ChatRole.ASSISTANT, null,
                            List.of(new ChatToolCall(callId, "unknown_tool", "{}")), null),
                    new ChatMessage(ChatRole.TOOL,
                            "Error: you supplied tool_arguments but left tool_name empty, so no tool was called.",
                            List.of(), callId));

            ChatRequest request = new ChatRequest(
                    "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/",
                    "",
                    "qwen2.5-coder:14b",
                    history,
                    List.of());

            new OpenAiCompatibleClient().chat(request, delta -> {
            });

            JsonObject payload = JsonParser.parseString(requestBody.get()).getAsJsonObject();
            JsonObject assistant = payload.getAsJsonArray("messages").get(1).getAsJsonObject();
            assertEquals("assistant", assistant.get("role").getAsString());
            JsonObject call = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
            assertEquals(callId, call.get("id").getAsString());
            assertEquals("unknown_tool", call.getAsJsonObject("function").get("name").getAsString());

            JsonObject toolResult = payload.getAsJsonArray("messages").get(2).getAsJsonObject();
            assertEquals("tool", toolResult.get("role").getAsString());
            // The whole point of the synthetic call: without this the error is an
            // orphan and the model is never told why nothing happened.
            assertEquals(callId, toolResult.get("tool_call_id").getAsString());
            assertTrue(toolResult.get("content").getAsString().contains("tool_name"));
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void assembleSseAccumulatesTextAndStructuredToolCalls() {
        List<String> lines = List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"Let me \"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"check.\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Welling\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ton, NZ\\\"}\"}}]}}]}",
                "{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]");

        ChatResult result = OpenAiCompatibleClient.assembleSse(lines);

        assertEquals("Let me check.", result.assistantText());
        assertEquals("tool_calls", result.finishReason());
        assertEquals(1, result.toolCalls().size());
        assertEquals("call_1", result.toolCalls().get(0).id());
        assertEquals("get_weather", result.toolCalls().get(0).name());
        assertEquals("{\"city\":\"Wellington, NZ\"}", result.toolCalls().get(0).argumentsJson());
    }

    @Test
    void assembleSseReturnsTextOnlyStopWhenNoToolCallsArrive() {
        List<String> lines = List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\" world\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\"}]}",
                "[DONE]");

        ChatResult result = OpenAiCompatibleClient.assembleSse(lines);

        assertEquals("Hello world", result.assistantText());
        assertEquals("stop", result.finishReason());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void chatPostsOpenAiCompatiblePayloadAndStreamsTextDeltas() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = ""
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                    + "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            JsonObject toolSchema = new JsonObject();
            toolSchema.addProperty("name", "get_weather");
            toolSchema.addProperty("description", "Weather lookup");
            JsonObject citySchema = new JsonObject();
            citySchema.addProperty("type", "string");
            JsonObject properties = new JsonObject();
            properties.add("city", citySchema);
            JsonObject inputSchema = new JsonObject();
            inputSchema.addProperty("type", "object");
            inputSchema.add("properties", properties);
            inputSchema.add("required", JsonParser.parseString("[\"city\"]").getAsJsonArray());
            toolSchema.add("inputSchema", inputSchema);

            JsonObject originalInputSchema = toolSchema.getAsJsonObject("inputSchema");
            JsonObject originalProperties = originalInputSchema.getAsJsonObject("properties");

            OpenAiCompatibleClient client = new OpenAiCompatibleClient();
            List<String> deltas = new ArrayList<>();
            ChatRequest request = new ChatRequest(
                    "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/",
                    "secret-123",
                    "qwen2.5-coder:7b",
                    List.of(new ChatMessage(ChatRole.USER, "What is the weather?", List.of(), null)),
                    List.of(toolSchema));

            ChatResult result = client.chat(request, deltas::add);

            assertEquals(List.of("Hel", "lo"), deltas);
            assertEquals("/v1/chat/completions", requestPath.get());
            assertEquals("Bearer secret-123", authorization.get());
            assertEquals("Hello", result.assistantText());
            assertEquals("stop", result.finishReason());

            JsonObject payload = JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertTrue(payload.get("stream").getAsBoolean());
            assertEquals("qwen2.5-coder:7b", payload.get("model").getAsString());
            assertEquals("user", payload.getAsJsonArray("messages")
                    .get(0).getAsJsonObject().get("role").getAsString());
            JsonObject tool = payload.getAsJsonArray("tools").get(0).getAsJsonObject();
            assertEquals("function", tool.get("type").getAsString());
            JsonObject function = tool.getAsJsonObject("function");
            assertEquals("get_weather", function.get("name").getAsString());
            assertFalse(function.has("inputSchema"));
            JsonObject parameters = function.getAsJsonObject("parameters");
            assertEquals("object", parameters.get("type").getAsString());
            assertEquals("string", parameters.getAsJsonObject("properties")
                    .getAsJsonObject("city").get("type").getAsString());
            assertEquals("city", parameters.getAsJsonArray("required").get(0).getAsString());
            assertTrue(originalInputSchema == toolSchema.getAsJsonObject("inputSchema"));
            assertTrue(originalProperties == originalInputSchema.getAsJsonObject("properties"));
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void blankApiKeyOmitsAuthorizationHeader() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>("<unset>");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            OpenAiCompatibleClient client = new OpenAiCompatibleClient();
            ChatRequest request = new ChatRequest(
                    "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort(),
                    "   ",
                    "qwen2.5-coder:7b",
                    List.of(new ChatMessage(ChatRole.USER, "ping", List.of(), null)),
                    List.of());

            client.chat(request, text -> {
            });

            assertEquals(null, authorization.get());
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void assembleSseSkipsGarbageLines() {
        List<String> lines = List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "this is not JSON at all!",
                "{\"choices\":[{\"delta\":{\"content\":\" world\"}}]}",
                "[DONE]");

        ChatResult result = OpenAiCompatibleClient.assembleSse(lines);

        assertEquals("Hello world", result.assistantText());
    }

    @Test
    void payloadRequestsUsageAlongsideStreaming() {
        ChatRequest request = new ChatRequest("http://localhost:11434", null, "m",
                List.of(new ChatMessage(ChatRole.USER, "hi", List.of(), null)), List.of());

        JsonObject payload = OpenAiCompatibleClient.buildPayloadForTest(request);

        assertTrue(payload.has("stream_options"));
        assertTrue(payload.getAsJsonObject("stream_options").get("include_usage").getAsBoolean(),
                "without this the endpoint never reports prompt_tokens");
    }

    @Test
    void usageIsParsedFromTheFinalSseChunk() {
        ChatResult result = OpenAiCompatibleClient.assembleSse(List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56}}",
                "[DONE]"));

        assertEquals("hello", result.assistantText());
        assertEquals(1234, result.promptTokens());
        assertEquals(56, result.completionTokens());
    }

    @Test
    void absentUsageYieldsNullsRatherThanFailing() {
        ChatResult result = OpenAiCompatibleClient.assembleSse(List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
                "[DONE]"));

        assertEquals("hello", result.assistantText());
        assertNull(result.promptTokens());
    }
}
