package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RequestBuilderTest {

    private static ChatRequestSpec spec(Double temperature, Double topP, Integer maxTokens, String reasoningEffort,
                                        JsonArray tools) {
        return new ChatRequestSpec("http://localhost:1234/v1", "gpt-4o-mini",
                                   List.of(new ChatMessage(ChatRole.USER, "hi", List.of(), null)),
                                   tools, temperature, topP, maxTokens, reasoningEffort);
    }

    @Test
    @Timeout(5)
    void everyOptionalFieldPresentWhenAllowed() {
        JsonArray tools = new JsonArray();
        tools.add(toolSchema("read_file", "Read a file", "{}"));
        JsonObject payload = RequestBuilder.build(spec(0.4, 0.9, 256, "high", tools), new CompatibilityFlags());

        assertEquals("gpt-4o-mini", payload.get("model").getAsString());
        assertTrue(payload.get("stream").getAsBoolean());
        assertTrue(payload.has("stream_options"));
        assertEquals(0.4, payload.get("temperature").getAsDouble());
        assertEquals(0.9, payload.get("top_p").getAsDouble());
        assertEquals(256, payload.get("max_tokens").getAsInt());
        assertFalse(payload.has("max_completion_tokens"));
        assertEquals("high", payload.get("reasoning_effort").getAsString());
        assertTrue(payload.has("tools"));
    }

    @Test
    @Timeout(5)
    void optionalFieldsAbsentWhenNull() {
        JsonObject payload = RequestBuilder.build(spec(null, null, null, null, null), new CompatibilityFlags());

        assertFalse(payload.has("temperature"));
        assertFalse(payload.has("top_p"));
        assertFalse(payload.has("max_tokens"));
        assertFalse(payload.has("max_completion_tokens"));
        assertFalse(payload.has("reasoning_effort"));
        assertFalse(payload.has("tools"));
        assertTrue(payload.has("stream_options"));
    }

    @Test
    @Timeout(5)
    void flagOffRemovesStreamOptions() {
        CompatibilityFlags flags = new CompatibilityFlags();
        flags.turnOff(CompatibilityFlags.STREAM_OPTIONS);
        JsonObject payload = RequestBuilder.build(spec(null, null, null, null, null), flags);

        assertFalse(payload.has("stream_options"));
        assertFalse(flags.supportsStreamUsage());
    }

    @Test
    @Timeout(5)
    void flagOffRemovesReasoningEffort() {
        CompatibilityFlags flags = new CompatibilityFlags();
        flags.turnOff(CompatibilityFlags.REASONING_EFFORT);
        JsonObject payload = RequestBuilder.build(spec(null, null, null, "high", null), flags);

        assertFalse(payload.has("reasoning_effort"));
        assertFalse(flags.supportsReasoningEffort());
    }

    @Test
    @Timeout(5)
    void turnOffMaxTokensSwitchesFieldName() {
        CompatibilityFlags before = new CompatibilityFlags();
        JsonObject first = RequestBuilder.build(spec(null, null, 128, null, null), before);
        assertEquals(128, first.get("max_tokens").getAsInt());
        assertFalse(first.has("max_completion_tokens"));

        CompatibilityFlags flags = new CompatibilityFlags();
        flags.turnOff(CompatibilityFlags.MAX_TOKENS);
        JsonObject renamed = RequestBuilder.build(spec(null, null, 128, null, null), flags);
        assertFalse(renamed.has("max_tokens"));
        assertEquals(128, renamed.get("max_completion_tokens").getAsInt());
        assertTrue(flags.usesMaxCompletionTokens());
    }

    @Test
    @Timeout(5)
    void assistantToolCallMessageShape() {
        ChatMessage assistant = new ChatMessage(ChatRole.ASSISTANT, null,
                                                List.of(new ChatToolCall("call_1", "read_file", "{\"path\":\"a\"}")), null);
        ChatRequestSpec spec = new ChatRequestSpec("http://x/v1", "m", List.of(assistant), null, null, null, null, null);

        JsonObject message = RequestBuilder.build(spec, new CompatibilityFlags())
                .getAsJsonArray("messages").get(0).getAsJsonObject();

        assertEquals("assistant", message.get("role").getAsString());
        assertTrue(message.get("content").isJsonNull());
        JsonObject call = message.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", call.get("id").getAsString());
        assertEquals("function", call.get("type").getAsString());
        JsonObject function = call.getAsJsonObject("function");
        assertEquals("read_file", function.get("name").getAsString());
        assertEquals("{\"path\":\"a\"}", function.get("arguments").getAsString());
    }

    @Test
    @Timeout(5)
    void toolResultMessageShape() {
        ChatMessage tool = new ChatMessage(ChatRole.TOOL, "file contents", List.of(), "call_1");
        ChatRequestSpec spec = new ChatRequestSpec("http://x/v1", "m", List.of(tool), null, null, null, null, null);

        JsonObject message = RequestBuilder.build(spec, new CompatibilityFlags())
                .getAsJsonArray("messages").get(0).getAsJsonObject();

        assertEquals("tool", message.get("role").getAsString());
        assertEquals("file contents", message.get("content").getAsString());
        assertEquals("call_1", message.get("tool_call_id").getAsString());
        assertFalse(message.has("tool_calls"));
    }

    @Test
    @Timeout(5)
    void emptyToolsAreOmitted() {
        CompatibilityFlags flags = new CompatibilityFlags();
        assertFalse(RequestBuilder.build(spec(null, null, null, null, null), flags).has("tools"));
        assertFalse(RequestBuilder.build(spec(null, null, null, null, new JsonArray()), flags).has("tools"));
    }

    @Test
    @Timeout(5)
    void toolSchemaCarriesNameDescriptionAndParameters() {
        JsonArray schemas = new JsonArray();
        schemas.add(toolSchema("read_file", "Read a file", "{\"type\":\"object\"}"));

        JsonObject tool = RequestBuilder.build(spec(null, null, null, null, schemas), new CompatibilityFlags())
                .getAsJsonArray("tools").get(0).getAsJsonObject();

        assertEquals("function", tool.get("type").getAsString());
        JsonObject function = tool.getAsJsonObject("function");
        assertEquals("read_file", function.get("name").getAsString());
        assertEquals("Read a file", function.get("description").getAsString());
        assertEquals("object", function.getAsJsonObject("parameters").get("type").getAsString());
        assertFalse(function.has("input_schema"));
    }

    private static ChatRequestSpec withKeepAlive(String keepAlive) {
        return new ChatRequestSpec("http://localhost:11434/v1", "llama3",
                                   List.of(new ChatMessage(ChatRole.USER, "hi", List.of(), null)),
                                   null, null, null, null, null, null, keepAlive);
    }

    @Test
    @Timeout(5)
    void keepAliveIsSentWhenSet() {
        JsonObject payload = RequestBuilder.build(withKeepAlive("5m"), new CompatibilityFlags());

        assertEquals("5m", payload.get("keep_alive").getAsString());
    }

    @Test
    @Timeout(5)
    void keepAliveIsAbsentWhenNullOrBlank() {
        assertFalse(RequestBuilder.build(withKeepAlive(null), new CompatibilityFlags()).has("keep_alive"));
        assertFalse(RequestBuilder.build(withKeepAlive(""), new CompatibilityFlags()).has("keep_alive"));
        assertFalse(RequestBuilder.build(withKeepAlive("   "), new CompatibilityFlags()).has("keep_alive"));
    }

    @Test
    @Timeout(5)
    void camelCaseInputSchemaBecomesParameters() {
        JsonArray schemas = new JsonArray();
        JsonObject schema = new JsonObject();
        schema.addProperty("name", "list_tools");
        schema.addProperty("description", "List tools");
        schema.add("inputSchema", JsonParser.parseString(
                   "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}"));
        schemas.add(schema);

        JsonObject function = RequestBuilder.build(spec(null, null, null, null, schemas), new CompatibilityFlags())
                .getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function");

        JsonObject parameters = function.getAsJsonObject("parameters");
        assertEquals("object", parameters.get("type").getAsString());
        assertTrue(parameters.getAsJsonObject("properties").has("x"));
        assertFalse(function.has("inputSchema"));
    }

    private static ChatRequestSpec withResponseFormat(JsonObject responseFormat) {
        return new ChatRequestSpec("http://localhost:1234/v1", "gpt-4o-mini",
                                   List.of(new ChatMessage(ChatRole.USER, "hi", List.of(), null)),
                                   null, null, null, null, null, responseFormat);
    }

    private static JsonObject jsonSchemaFormat() {
        return JsonParser.parseString(
                "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"summary\",\"schema\":{\"type\":\"object\"}}}")
                .getAsJsonObject();
    }

    @Test
    @Timeout(5)
    void responseFormatPresentWhenAllowed() {
        JsonObject payload = RequestBuilder.build(withResponseFormat(jsonSchemaFormat()), new CompatibilityFlags());

        assertEquals("json_schema", payload.getAsJsonObject("response_format").get("type").getAsString());
    }

    @Test
    @Timeout(5)
    void responseFormatAbsentAfterTurnOff() {
        CompatibilityFlags flags = new CompatibilityFlags();
        flags.turnOff(CompatibilityFlags.RESPONSE_FORMAT);

        JsonObject payload = RequestBuilder.build(withResponseFormat(jsonSchemaFormat()), flags);

        assertFalse(payload.has("response_format"));
        assertFalse(flags.optionalFieldsPresent(withResponseFormat(jsonSchemaFormat()))
                .contains(CompatibilityFlags.RESPONSE_FORMAT));
    }

    @Test
    @Timeout(5)
    void responseFormatAbsentWhenNull() {
        JsonObject payload = RequestBuilder.build(withResponseFormat(null), new CompatibilityFlags());

        assertFalse(payload.has("response_format"));
    }

    @Test
    @Timeout(5)
    void dropAllOptionalLeavesOnlyTheCore() {
        JsonArray tools = new JsonArray();
        tools.add(toolSchema("read_file", "Read a file", "{}"));
        CompatibilityFlags flags = new CompatibilityFlags();
        flags.dropAllOptional();

        JsonObject payload = RequestBuilder.build(spec(null, null, null, "high", tools), flags);

        assertEquals(Set.of("model", "stream", "messages", "tools"), payload.keySet());
        assertFalse(payload.has("stream_options"));
        assertFalse(payload.has("reasoning_effort"));
        assertFalse(flags.supportsStreamUsage());
        assertFalse(flags.supportsReasoningEffort());
    }

    @Test
    @Timeout(5)
    void resetRestoresDefaults() {
        CompatibilityFlags flags = new CompatibilityFlags();
        flags.dropAllOptional();
        flags.reset();

        assertTrue(flags.supportsStreamUsage());
        assertTrue(flags.supportsReasoningEffort());
        assertTrue(flags.supportsJsonSchemaResponseFormat());
        assertFalse(flags.usesMaxCompletionTokens());
        JsonObject payload = RequestBuilder.build(spec(null, null, null, null, null), flags);
        assertTrue(payload.has("stream_options"));
    }

    @Test
    @Timeout(5)
    void optionalFieldsPresentListsEnabledFields() {
        CompatibilityFlags flags = new CompatibilityFlags();
        Set<String> present = flags.optionalFieldsPresent(spec(null, null, 10, "low", null));

        assertEquals(Set.of(CompatibilityFlags.STREAM_OPTIONS, CompatibilityFlags.REASONING_EFFORT,
                            CompatibilityFlags.MAX_TOKENS), present);
        assertEquals(Set.of(CompatibilityFlags.STREAM_OPTIONS),
                     flags.optionalFieldsPresent(spec(null, null, null, null, null)));

        flags.turnOff(CompatibilityFlags.STREAM_OPTIONS);
        assertEquals(Set.of(), flags.optionalFieldsPresent(spec(null, null, null, null, null)));
    }

    private static JsonObject toolSchema(String name, String description, String inputSchemaJson) {
        JsonObject schema = new JsonObject();
        schema.addProperty("name", name);
        schema.addProperty("description", description);
        schema.add("input_schema", JsonParser.parseString(inputSchemaJson));
        return schema;
    }
}
