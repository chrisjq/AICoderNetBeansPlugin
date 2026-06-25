package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeStreamJsonParser;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeSessionInfoEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

class ClaudeStreamJsonParserTest {

    private List<AiProcessEvent> parse(String... lines) {
        List<AiProcessEvent> events = new ArrayList<>();
        ClaudeStreamJsonParser parser = new ClaudeStreamJsonParser(events::add);
        for (String line : lines) {
            parser.parseLine(line);
        }
        return events;
    }

    @Test
    void textContent_producesTextDeltaEvent() {
        String line = """
            {"type":"assistant","message":{"id":"msg_1","role":"assistant",
             "content":[{"type":"text","text":"Hello world"}],
             "usage":{"input_tokens":10,"output_tokens":5}}}
            """.strip().replace("\n", "");

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        assertInstanceOf(TextDeltaEvent.class, events.get(0));
        assertEquals("Hello world", ((TextDeltaEvent) events.get(0)).text());
    }

    @Test
    void toolUse_Write_producesToolUseEvent() {
        String line = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1",
             "name":"Write","input":{"path":"/foo/Bar.java","content":"public class Bar {}"}}]}}
            """.strip().replace("\n", "");

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        ToolUseEvent e = assertInstanceOf(ToolUseEvent.class, events.get(0));
        assertEquals("/foo/Bar.java", e.filePath());
        assertEquals("public class Bar {}", e.proposedContent());
        assertEquals(ToolUseEvent.Kind.WRITE, e.kind());
    }

    @Test
    void toolUse_Edit_appliesPatchToProduceProposedContent() {
        String original = "public class Foo { int x = 1; }";
        String line = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2",
             "name":"Edit","input":{"path":"/foo/Foo.java",
             "old_string":"int x = 1;","new_string":"int x = 42;"}}]}}
            """.strip().replace("\n", "");

        ClaudeStreamJsonParser parser = new ClaudeStreamJsonParser(e -> {
        });
        parser.setCurrentFileContent("/foo/Foo.java", original);
        List<AiProcessEvent> events = new ArrayList<>();
        parser = new ClaudeStreamJsonParser(events::add);
        parser.setCurrentFileContent("/foo/Foo.java", original);
        parser.parseLine(line);

        assertEquals(1, events.size());
        ToolUseEvent e = assertInstanceOf(ToolUseEvent.class, events.get(0));
        assertEquals("public class Foo { int x = 42; }", e.proposedContent());
        assertEquals(ToolUseEvent.Kind.EDIT, e.kind());
    }

    @Test
    void resultEvent_producesTurnCompleteEvent() {
        String line = """
            {"type":"result","subtype":"success","is_error":false,
             "usage":{"input_tokens":100,"output_tokens":50}}
            """.strip().replace("\n", "");

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        assertInstanceOf(TurnCompleteEvent.class, events.get(0));
    }

    @Test
    void systemInit_withModel_producesSessionInfoEvent() {
        String line = """
            {"type":"system","subtype":"init","model":"claude-sonnet-4-5","cwd":"/home/user"}
            """.strip();

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        ClaudeSessionInfoEvent e = assertInstanceOf(ClaudeSessionInfoEvent.class, events.get(0));
        assertEquals("claude-sonnet-4-5", e.model());
        assertFalse(e.hasWeeklyPct());
    }

    @Test
    void malformedLine_isSkipped() {
        List<AiProcessEvent> events = parse("not json at all", "   ", "");
        assertTrue(events.isEmpty());
    }

    @Test
    void unknownType_isSkipped() {
        List<AiProcessEvent> events = parse("""
            {"type":"unknown_future_type","data":"x"}
            """.strip());
        assertTrue(events.isEmpty());
    }

    @Test
    void assistantMessage_withNonObjectContentElement_isSkipped() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"assistant\",\"message\":{\"content\":[\"just a string\"]}}");
        assertTrue(events.isEmpty());
    }

    @Test
    void systemInit_withNonNumericUsagePct_defaultsToUnavailable() {
        String line = "{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"test\",\"usage_pct\":\"bad\"}";
        List<AiProcessEvent> events = parse(line);
        assertEquals(1, events.size());
        ClaudeSessionInfoEvent e = assertInstanceOf(ClaudeSessionInfoEvent.class, events.get(0));
        assertFalse(e.hasWeeklyPct());
    }

    @Test
    void systemThinkingTokens_producesTypedStatusEvent() {
        String line = "{\"type\":\"system\",\"subtype\":\"thinking_tokens\",\"estimated_tokens\":42}";

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        StatusEvent se = assertInstanceOf(StatusEvent.class, events.get(0));
        assertEquals(StatusEventTypeEnum.THINKING, se.type());
        assertTrue(se.text().contains("42"));
    }
}
