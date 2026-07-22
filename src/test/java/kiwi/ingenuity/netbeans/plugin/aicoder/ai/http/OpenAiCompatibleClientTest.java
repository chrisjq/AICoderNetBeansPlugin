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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {

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
}
