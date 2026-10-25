package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SchemaToolCallingTest {

    @Test
    @Timeout(5)
    void parseAndRenderRoundTripDoesNotOfferEndTurn() {
        JsonObject tool = gitLog();
        String rendered = SchemaToolCalling.renderToolList(List.of(tool, endTurnTool()));
        assertTrue(rendered.contains("- GitLog(projectPath, [limit])"), rendered);
        assertTrue(rendered.contains("Returns recent commit history. Equivalent to git log."), rendered);
        assertTrue(rendered.contains("projectPath (string, required)"), rendered);
        assertTrue(rendered.contains("limit (integer, optional)"), rendered);
        assertFalse(rendered.contains("EndTurn"), rendered);

        JsonObject format = SchemaToolCalling.responseFormat(List.of("GitLog", "EndTurn"));
        assertEquals("json_schema", format.get("type").getAsString());
        JsonObject schema = format.getAsJsonObject("json_schema").getAsJsonObject("schema");
        assertEquals(3, schema.getAsJsonArray("required").size());
        JsonArray allowed = schema.getAsJsonObject("properties").getAsJsonObject("tool_name").getAsJsonArray("enum");
        List<String> values = new ArrayList<>();
        allowed.forEach(element -> values.add(element.getAsString()));
        assertEquals(List.of("", "GitLog"), values);

        SchemaToolCalling.Reply reply = SchemaToolCalling.parse(
                "{\"message\":\"\",\"tool_name\":\"GitLog\",\"tool_arguments\":{\"projectPath\":\"/p\"}}",
                List.of(), Set.of("GitLog"));
        assertEquals(1, reply.calls().size());
        assertEquals("GitLog", reply.calls().get(0).name());
        assertEquals("{\"projectPath\":\"/p\"}", reply.calls().get(0).argumentsJson());
        assertTrue(reply.spokeProtocol());
        assertNull(reply.toolCallError());
    }

    @Test
    @Timeout(5)
    void endTurnIsNotATool() {
        SchemaToolCalling.Reply reply = SchemaToolCalling.parse(
                "{\"message\":\"bye\",\"tool_name\":\"EndTurn\",\"tool_arguments\":{\"message\":\"bye\"}}",
                List.of(), Set.of("GitLog", "EndTurn"));

        assertTrue(reply.calls().isEmpty());
        assertNotNull(reply.toolCallError());
        assertTrue(reply.toolCallError().contains("EndTurn"), reply.toolCallError());
    }

    @Test
    @Timeout(5)
    void emptyToolNameWithArgumentsIsAnErrorAndWithoutThemIsAnAnswer() {
        SchemaToolCalling.Reply missingName = SchemaToolCalling.parse(
                "{\"message\":\"Sending now.\",\"tool_name\":\"\",\"tool_arguments\":{\"subject\":\"s\"}}",
                List.of(), Set.of("PeerMessageSend"));
        assertTrue(missingName.calls().isEmpty());
        assertNotNull(missingName.toolCallError());
        assertTrue(missingName.toolCallError().contains("tool_name"), missingName.toolCallError());

        SchemaToolCalling.Reply answer = SchemaToolCalling.parse(
                "{\"message\":\"Hello.\",\"tool_name\":\"\",\"tool_arguments\":{}}",
                List.of(), Set.of("PeerMessageSend"));
        assertEquals("Hello.", answer.message());
        assertTrue(answer.calls().isEmpty());
        assertNull(answer.toolCallError());
    }

    @Test
    @Timeout(5)
    void decoratedArgumentNamesAreCleaned() {
        SchemaToolCalling.Reply reply = SchemaToolCalling.parse(
                "{\"message\":\"\",\"tool_name\":\"GetFileContent\",\"tool_arguments\":{\"!filePath\":\"/p/pom.xml\"}}",
                List.of(), Set.of("GetFileContent"));

        assertEquals("{\"filePath\":\"/p/pom.xml\"}", reply.calls().get(0).argumentsJson());
    }

    @Test
    @Timeout(5)
    void structuredCallsWinOverSchemaText() {
        SchemaToolCalling.Reply reply = SchemaToolCalling.parse(
                "{\"message\":\"ignored\",\"tool_name\":\"GitLog\",\"tool_arguments\":{}}",
                List.of(new ChatToolCall("c1", "SaveFile", "{}")),
                Set.of("GitLog"));

        assertEquals(1, reply.calls().size());
        assertEquals("SaveFile", reply.calls().get(0).name());
        assertFalse(reply.spokeProtocol());
    }

    private static JsonObject gitLog() {
        return JsonParser.parseString("""
            {"name":"GitLog","description":"Returns recent commit history. Equivalent to git log.",
             "inputSchema":{"type":"object",
               "properties":{"projectPath":{"type":"string","description":"Project root."},
                             "limit":{"type":"integer","description":"Maximum entries."}},
               "required":["projectPath"]}}
            """).getAsJsonObject();
    }

    private static JsonObject endTurnTool() {
        return JsonParser.parseString("""
            {"name":"EndTurn","description":"End the turn.","inputSchema":{"type":"object","properties":{}}}
            """).getAsJsonObject();
    }
}
