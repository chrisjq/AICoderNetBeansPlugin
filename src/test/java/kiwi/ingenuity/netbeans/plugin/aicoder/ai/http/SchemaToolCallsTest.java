package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payloads here are the verbatim replies qwen2.5-coder:14b gave when driven
 * with a response schema and the tools listed in the prompt.
 */
class SchemaToolCallsTest {

    private static ChatResult text(String content) {
        return new ChatResult(content, List.of(), "stop");
    }

    @Test
    void answerReplyYieldsMessageAndNoCall() {
        ChatResult result = text("{\n \"message\": \"Hello! How can I assist you today?\",\n"
                + " \"tool_name\": \"\",\n \"tool_arguments\": {}\n}");

        SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("get_current_weather"));

        assertEquals("Hello! How can I assist you today?", reply.message());
        assertTrue(reply.calls().isEmpty(), "an empty tool_name must not produce a call");
    }

    @Test
    void toolReplyYieldsCallWithArguments() {
        ChatResult result = text("{\n \"message\": \"\",\n"
                + " \"tool_name\": \"get_current_weather\",\n"
                + " \"tool_arguments\": {\"city\": \"Toronto\"}\n}");

        SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("get_current_weather"));

        assertEquals(1, reply.calls().size());
        assertEquals("get_current_weather", reply.calls().get(0).name());
        assertEquals("{\"city\":\"Toronto\"}", reply.calls().get(0).argumentsJson());
    }

    /**
     * Verbatim failures from qwen2.5-coder when required parameters were marked
     * with a trailing "!": it folded the marker into the argument name, and the
     * tool reported the argument as missing.
     */
    @Test
    void decoratedArgumentNamesAreCleaned() {
        ChatResult leading = text("{\"message\":\"\",\"tool_name\":\"GetFileContent\","
                + "\"tool_arguments\":{\"!filePath\":\"/p/pom.xml\"}}");
        ChatResult trailing = text("{\"message\":\"\",\"tool_name\":\"GetFileContent\","
                + "\"tool_arguments\":{\"filePath!\":\"/p/pom.xml\"}}");
        ChatResult bracketed = text("{\"message\":\"\",\"tool_name\":\"GetFileContent\","
                + "\"tool_arguments\":{\"[startLine]\":\"1\"}}");

        assertEquals("{\"filePath\":\"/p/pom.xml\"}",
                SchemaToolCalls.parse(leading, Set.of("GetFileContent")).calls().get(0).argumentsJson());
        assertEquals("{\"filePath\":\"/p/pom.xml\"}",
                SchemaToolCalls.parse(trailing, Set.of("GetFileContent")).calls().get(0).argumentsJson());
        assertEquals("{\"startLine\":\"1\"}",
                SchemaToolCalls.parse(bracketed, Set.of("GetFileContent")).calls().get(0).argumentsJson());
    }

    @Test
    void unknownToolNameIsDropped() {
        ChatResult result = text("{\"message\":\"\",\"tool_name\":\"rm_rf\",\"tool_arguments\":{}}");

        assertTrue(SchemaToolCalls.parse(result, Set.of("get_current_weather")).calls().isEmpty(),
                "only advertised tools may be invoked");
    }

    /** The model still ignores the schema occasionally; the text path must cover it. */
    @Test
    void fallsBackToTheTextExtractorWhenTheSchemaIsIgnored() {
        ChatResult result = text("```json\n{\"name\":\"get_current_weather\","
                + "\"arguments\":{\"city\":\"Toronto\"}}\n```");

        SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("get_current_weather"));

        assertEquals(1, reply.calls().size());
        assertEquals("get_current_weather", reply.calls().get(0).name());
    }

    @Test
    void structuredToolCallsStillWin() {
        ChatResult result = new ChatResult("",
                List.of(new ChatToolCall("c1", "get_current_weather", "{\"city\":\"Oslo\"}")),
                "tool_calls");

        SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("get_current_weather"));

        assertEquals(1, reply.calls().size());
        assertEquals("get_current_weather", reply.calls().get(0).name());
    }

    @Test
    void responseFormatIsAJsonSchemaRequiringAllThreeFields() {
        JsonObject format = SchemaToolCalls.responseFormat();

        assertEquals("json_schema", format.get("type").getAsString());
        JsonObject schema = format.getAsJsonObject("json_schema").getAsJsonObject("schema");
        assertTrue(schema.getAsJsonObject("properties").has("message"));
        assertTrue(schema.getAsJsonObject("properties").has("tool_name"));
        assertTrue(schema.getAsJsonObject("properties").has("tool_arguments"));
        assertEquals(3, schema.getAsJsonArray("required").size());
    }

    /**
     * Parameter names exist only in the tool schemas, so dropping the tools
     * array means the prompt must carry them or the model cannot call anything.
     */
    @Test
    void renderedToolListCarriesParameterNames() {
        JsonObject tool = JsonParser.parseString("""
            {"name":"GitLog","description":"Returns recent commit history. Equivalent to git log.",
             "inputSchema":{"type":"object",
               "properties":{"projectPath":{"type":"string"},"limit":{"type":"integer"}},
               "required":["projectPath"]}}
            """).getAsJsonObject();

        String rendered = SchemaToolCalls.renderToolList(List.of(tool));

        assertTrue(rendered.contains("GitLog("), rendered);
        assertTrue(rendered.contains("GitLog(projectPath,"),
                "required names must be undecorated so they can be copied verbatim: " + rendered);
        assertTrue(rendered.contains("[limit]"), "optional params are bracketed: " + rendered);
        assertTrue(rendered.contains("Returns recent commit history"), rendered);
        assertFalse(rendered.contains("Equivalent to git log"),
                "only the first sentence is kept, to bound prompt size");
    }

    /** Splitting naively on ". " cut "(e.g. /path/Foo.java)" to "(e.g" mid-word. */
    @Test
    void abbreviationsSurviveSentenceTruncation() {
        JsonObject tool = JsonParser.parseString("""
            {"name":"GetCurrentFile",
             "description":"Returns the cursor position (e.g. /path/Foo.java:42) in the editor.",
             "inputSchema":{"type":"object","properties":{}}}
            """).getAsJsonObject();

        String rendered = SchemaToolCalls.renderToolList(List.of(tool));

        assertTrue(rendered.contains("/path/Foo.java:42) in the editor."), rendered);
    }
}
