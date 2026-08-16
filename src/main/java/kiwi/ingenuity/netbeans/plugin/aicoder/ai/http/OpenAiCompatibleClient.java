package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

public class OpenAiCompatibleClient implements HttpAiClient {

    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleClient.class.getName());
    private static final Gson GSON = new Gson();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);

    static ChatResult assembleSse(List<String> sseDataLines) {
        StringBuilder assistantText = new StringBuilder();
        Map<Integer, PartialToolCall> toolCalls = new LinkedHashMap<>();
        String finishReason = null;
        Integer promptTokens = null;
        Integer completionTokens = null;

        for (String dataLine : sseDataLines) {
            if (dataLine == null || dataLine.isBlank() || "[DONE]".equals(dataLine)) {
                continue;
            }
            JsonObject root;
            try {
                root = JsonParser.parseString(dataLine).getAsJsonObject();
            }
            catch (RuntimeException ex) {
                continue;
            }
            JsonObject usage = root.getAsJsonObject("usage");
            if (usage != null) {
                JsonElement pt = usage.get("prompt_tokens");
                if (pt != null && !pt.isJsonNull()) {
                    promptTokens = pt.getAsInt();
                }
                JsonElement ct = usage.get("completion_tokens");
                if (ct != null && !ct.isJsonNull()) {
                    completionTokens = ct.getAsInt();
                }
            }
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                continue;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonElement finishReasonEl = choice.get("finish_reason");
            if (finishReasonEl != null && !finishReasonEl.isJsonNull()) {
                finishReason = finishReasonEl.getAsString();
            }
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta == null) {
                continue;
            }
            JsonElement contentEl = delta.get("content");
            if (contentEl != null && !contentEl.isJsonNull()) {
                assistantText.append(contentEl.getAsString());
            }
            JsonArray deltaToolCalls = delta.getAsJsonArray("tool_calls");
            if (deltaToolCalls != null) {
                appendToolCalls(toolCalls, deltaToolCalls);
            }
        }

        List<ChatToolCall> finalToolCalls = toolCalls.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .map(PartialToolCall::toChatToolCall)
                .toList();
        return new ChatResult(assistantText.toString(), finalToolCalls, finishReason,
                promptTokens, completionTokens);
    }

    private static List<String> readSseDataLines(InputStream body, Consumer<String> onTextDelta) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).stripLeading();
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.WARNING, "ollama sse: {0}", data);
                }
                lines.add(data);
                emitTextDelta(data, onTextDelta);
            }
        }
        return lines;
    }

    private static void emitTextDelta(String data, Consumer<String> onTextDelta) {
        if (onTextDelta == null || data == null || data.isBlank() || "[DONE]".equals(data)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta == null) {
                return;
            }
            JsonElement contentEl = delta.get("content");
            if (contentEl != null && !contentEl.isJsonNull()) {
                onTextDelta.accept(contentEl.getAsString());
            }
        }
        catch (RuntimeException ex) {
            // skip malformed delta
        }
    }

    private static void appendToolCalls(Map<Integer, PartialToolCall> toolCalls, JsonArray deltaToolCalls) {
        for (JsonElement element : deltaToolCalls) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject toolCall = element.getAsJsonObject();
            int index = toolCall.has("index") ? toolCall.get("index").getAsInt() : toolCalls.size();
            PartialToolCall partial = toolCalls.computeIfAbsent(index, i -> new PartialToolCall());
            JsonElement idEl = toolCall.get("id");
            if (idEl != null && !idEl.isJsonNull()) {
                partial.id = idEl.getAsString();
            }
            JsonObject function = toolCall.getAsJsonObject("function");
            if (function == null) {
                continue;
            }
            JsonElement nameEl = function.get("name");
            if (nameEl != null && !nameEl.isJsonNull()) {
                partial.name = nameEl.getAsString();
            }
            JsonElement argsEl = function.get("arguments");
            if (argsEl != null && !argsEl.isJsonNull()) {
                partial.arguments.append(argsEl.getAsString());
            }
        }
    }

    private static JsonObject buildPayload(ChatRequest request) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", request.model());
        payload.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        payload.add("stream_options", streamOptions);

        JsonArray messages = new JsonArray();
        for (ChatMessage message : request.messages() == null ? List.<ChatMessage>of() : request.messages()) {
            messages.add(toApiMessage(message));
        }
        payload.add("messages", messages);

        JsonArray tools = new JsonArray();
        for (JsonObject toolSchema : request.toolSchemas() == null ? List.<JsonObject>of() : request.toolSchemas()) {
            JsonObject function = new JsonObject();
            if (toolSchema != null) {
                if (toolSchema.has("name")) {
                    function.add("name", toolSchema.get("name"));
                }
                if (toolSchema.has("description")) {
                    function.add("description", toolSchema.get("description"));
                }
                JsonElement parameters = toolSchema.has("inputSchema")
                        ? toolSchema.get("inputSchema")
                        : toolSchema.has("parameters") ? toolSchema.get("parameters") : null;
                if (parameters != null && parameters.isJsonObject()) {
                    function.add("parameters", deepCopy(parameters.getAsJsonObject()));
                }
            }
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            tools.add(tool);
        }
        payload.add("tools", tools);
        if (request.responseFormat() != null) {
            payload.add("response_format", request.responseFormat());
        }
        return payload;
    }

    static JsonObject buildPayloadForTest(ChatRequest request) {
        return buildPayload(request);
    }

    private static JsonObject toApiMessage(ChatMessage message) {
        JsonObject out = new JsonObject();
        out.addProperty("role", message.role().name().toLowerCase(Locale.ROOT));
        if (message.content() == null) {
            out.add("content", null);
        }
        else {
            out.addProperty("content", message.content());
        }
        if (message.toolCallId() != null) {
            out.addProperty("tool_call_id", message.toolCallId());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            JsonArray toolCalls = new JsonArray();
            for (ChatToolCall call : message.toolCalls()) {
                JsonObject toolCall = new JsonObject();
                if (call.id() != null) {
                    toolCall.addProperty("id", call.id());
                }
                toolCall.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name());
                function.addProperty("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
                toolCall.add("function", function);
                toolCalls.add(toolCall);
            }
            out.add("tool_calls", toolCalls);
        }
        return out;
    }

    private static JsonObject deepCopy(JsonObject source) {
        return source == null ? new JsonObject() : JsonParser.parseString(source.toString()).getAsJsonObject();
    }

    private static String trimTrailingSlash(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
    private final HttpClient httpClient;

    public OpenAiCompatibleClient() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    OpenAiCompatibleClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException {
        Objects.requireNonNull(request, "request");
        if (request.baseUrl() == null || request.baseUrl().isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }

        String endpoint = trimTrailingSlash(request.baseUrl()) + "/v1/chat/completions";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(DEFAULT_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + request.apiKey().trim());
        }
        String payloadJson = GSON.toJson(buildPayload(request));
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.WARNING, "ollama request: {0}", payloadJson);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8));

        try {
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    throw new IOException("HTTP " + response.statusCode() + " from " + endpoint + ": " + errorBody);
                }
                List<String> sseDataLines = readSseDataLines(body, onTextDelta);
                return assembleSse(sseDataLines);
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for chat completion", ex);
        }
        catch (RuntimeException ex) {
            throw new IOException("Invalid SSE payload: " + ex.getMessage(), ex);
        }
    }

    private static final class PartialToolCall {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ChatToolCall toChatToolCall() {
            return new ChatToolCall(id, name, arguments.toString());
        }
    }
}
