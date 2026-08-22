package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The payloads here are the verbatim replies qwen2.5-coder:14b gave when driven with a response schema and the tools
 * listed in the prompt.
 * <p>
 * Exception: the malformed-tool-call tests below are constructed, and say so individually. An earlier attempt to source
 * them from the log produced a payload that turned out to be an artifact of reassembling the SSE chunks rather than
 * anything the model sent - so treat "verbatim" as a claim to be checked against the plugin's own "Tool Used" log line,
 * not against a stream rebuilt by hand.
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
     * Verbatim failures from qwen2.5-coder when required parameters were marked with a trailing "!": it folded the
     * marker into the argument name, and the tool reported the argument as missing.
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

    /**
     * The model still ignores the schema occasionally; the text path must cover it.
     */
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

    /**
     * Constructed, not observed - see the class comment. Arguments for a real tool with the name left empty is the
     * shape the parser used to drop in silence, so the model believed the call had run. It has to be told, or it
     * cannot correct itself.
     */
    @Test
    void argumentsWithNoToolNameProduceAnErrorForTheModel() {
        ChatResult result = text("""
            {"message": "Sending now.", "tool_name": "",
             "tool_arguments": {"targetSessionId": "abc", "subject": "s", "message": "m"}}
            """);

        SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("SendAiMessage"));

        assertTrue(reply.calls().isEmpty());
        assertNotNull(reply.toolCallError());
        assertTrue(reply.toolCallError().contains("tool_name"), reply.toolCallError());
    }

    /**
     * The ordinary "just answering" reply looks identical apart from empty arguments, and must stay silent — an error
     * here would fire on every conversational turn.
     */
    @Test
    void emptyToolNameWithNoArgumentsIsAPlainAnswer() {
        ChatResult result = text("""
            {"message": "Hello.", "tool_name": "", "tool_arguments": {}}
            """);

        SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("SendAiMessage"));

        assertEquals("Hello.", reply.message());
        assertTrue(reply.calls().isEmpty());
        assertNull(reply.toolCallError());
    }

    /**
     * A backend that ignores the schema can send the arguments as a JSON string rather than an object. That is not an
     * object, so the "is it empty" check has to ask the raw element - asking the parsed object would see nothing and
     * drop the call silently, which is the bug this whole path exists for.
     */
    @Test
    void argumentsSentAsAStringWithNoToolNameStillProduceAnError() {
        ChatResult result = text("""
            {"message": "", "tool_name": "",
             "tool_arguments": "{\\"targetSessionId\\": \\"abc\\", \\"subject\\": \\"s\\"}"}
            """);

        SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("SendAiMessage"));

        assertTrue(reply.calls().isEmpty());
        assertNotNull(reply.toolCallError());
        assertTrue(reply.toolCallError().contains("must be a JSON object"), reply.toolCallError());
    }

    /**
     * The shapes that genuinely mean "no call" must stay silent, or the error fires on ordinary answering turns.
     */
    @Test
    void emptyArgumentContainersAreNotTreatedAsAnAttemptedCall() {
        for (String args : List.of("{}", "[]", "\"\"", "null")) {
            ChatResult result = text(
                    "{\"message\": \"Hi.\", \"tool_name\": \"\", \"tool_arguments\": " + args + "}");

            SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("SendAiMessage"));

            assertNull(reply.toolCallError(), "tool_arguments=" + args);
            assertTrue(reply.calls().isEmpty(), "tool_arguments=" + args);
        }
    }

    /**
     * Second failure mode: a name that is not a tool. The enum in responseFormat should stop this reaching us, but only
     * for backends that honour it.
     */
    @Test
    void unknownToolNameProducesAnErrorForTheModel() {
        ChatResult result = text("""
            {"message": "", "tool_name": "grep", "tool_arguments": {"pattern": "x"}}
            """);

        SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, Set.of("SearchInFiles"));

        assertTrue(reply.calls().isEmpty());
        assertNotNull(reply.toolCallError());
        assertTrue(reply.toolCallError().contains("grep"), reply.toolCallError());
    }

    @Test
    void responseFormatIsAJsonSchemaRequiringAllThreeFields() {
        JsonObject format = SchemaToolCalls.responseFormat(List.of());

        assertEquals("json_schema", format.get("type").getAsString());
        JsonObject schema = format.getAsJsonObject("json_schema").getAsJsonObject("schema");
        assertTrue(schema.getAsJsonObject("properties").has("message"));
        assertTrue(schema.getAsJsonObject("properties").has("tool_name"));
        assertTrue(schema.getAsJsonObject("properties").has("tool_arguments"));
        assertEquals(3, schema.getAsJsonArray("required").size());
        // No names supplied, so nothing to pin tool_name to.
        assertFalse(schema.getAsJsonObject("properties").getAsJsonObject("tool_name").has("enum"));
    }

    /**
     * The empty string has to stay selectable or the model has no way to answer without calling something, which is how
     * this backend ends up inventing a tool call for "hi".
     */
    @Test
    void responseFormatPinsToolNameToKnownToolsPlusTheEmptyString() {
        JsonObject format = SchemaToolCalls.responseFormat(List.of("GitLog", "SaveFile"));

        JsonArray allowed = format.getAsJsonObject("json_schema").getAsJsonObject("schema")
                .getAsJsonObject("properties").getAsJsonObject("tool_name").getAsJsonArray("enum");
        List<String> values = new ArrayList<>();
        allowed.forEach(e -> values.add(e.getAsString()));
        assertEquals(List.of("", "GitLog", "SaveFile"), values);
    }

    /**
     * Parameter names exist only in the tool schemas, so dropping the tools array means the prompt must carry them or
     * the model cannot call anything.
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

    /**
     * Splitting naively on ". " cut "(e.g. /path/Foo.java)" to "(e.g" mid-word.
     */
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
