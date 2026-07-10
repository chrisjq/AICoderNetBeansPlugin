package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in {@link GrokResponseParser} against real captured
 * {@code grok --output-format json} turn output (per
 * https://docs.x.ai/build/cli/headless-scripting), including its defensive
 * fallbacks for non-JSON or partially-broken output.
 */
class GrokResponseParserTest {

    @Test
    void parse_realResultField_emitsTextThenTurnComplete() {
        List<AiProcessEvent> events = new ArrayList<>();
        new GrokResponseParser(events::add).parse("{\"result\":\"Hello from grok\"}");
        assertEquals(2, events.size());
        assertEquals("Hello from grok", assertInstanceOf(TextDeltaEvent.class, events.get(0)).text());
        assertInstanceOf(TurnCompleteEvent.class, events.get(1));
    }

    @Test
    void parse_errorField_emitsFailedStatusThenTurnComplete() {
        List<AiProcessEvent> events = new ArrayList<>();
        new GrokResponseParser(events::add).parse("{\"error\":\"Session ID abc is already in use.\"}");
        assertEquals(2, events.size());
        StatusEvent status = assertInstanceOf(StatusEvent.class, events.get(0));
        assertEquals(StatusEventTypeEnum.FAILED, status.type());
        assertTrue(status.text().contains("already in use"));
        assertInstanceOf(TurnCompleteEvent.class, events.get(1));
    }

    @Test
    void parse_multipleJsonLines_usesLastParseableObject() {
        List<AiProcessEvent> events = new ArrayList<>();
        String raw = "{\"result\":\"first (stale) line\"}\n{\"result\":\"final result\"}";
        new GrokResponseParser(events::add).parse(raw);
        assertEquals("final result", assertInstanceOf(TextDeltaEvent.class, events.get(0)).text());
    }

    @Test
    void parse_prettyPrintedSingleObject_fallsBackToWholeOutputParse() {
        List<AiProcessEvent> events = new ArrayList<>();
        String raw = "{\n  \"result\": \"pretty printed\"\n}";
        new GrokResponseParser(events::add).parse(raw);
        assertEquals("pretty printed", assertInstanceOf(TextDeltaEvent.class, events.get(0)).text());
    }

    @Test
    void parse_nonJsonOutput_fallsBackToRawTextDelta() {
        List<AiProcessEvent> events = new ArrayList<>();
        new GrokResponseParser(events::add).parse("plain text with no JSON at all");
        assertEquals("plain text with no JSON at all",
                assertInstanceOf(TextDeltaEvent.class, events.get(0)).text());
        assertInstanceOf(TurnCompleteEvent.class, events.get(1));
    }

    @Test
    void parse_blankOutput_emitsOnlyTurnComplete() {
        List<AiProcessEvent> events = new ArrayList<>();
        new GrokResponseParser(events::add).parse("   ");
        assertEquals(1, events.size());
        assertInstanceOf(TurnCompleteEvent.class, events.get(0));
    }

    @Test
    void parse_nullOutput_emitsOnlyTurnComplete() {
        List<AiProcessEvent> events = new ArrayList<>();
        new GrokResponseParser(events::add).parse(null);
        assertEquals(1, events.size());
        assertInstanceOf(TurnCompleteEvent.class, events.get(0));
    }

    @Test
    void parse_fallsBackAcrossResultResponseTextContentMessageKeys() {
        List<AiProcessEvent> events = new ArrayList<>();
        new GrokResponseParser(events::add).parse("{\"message\":\"via message key\"}");
        assertEquals("via message key", assertInstanceOf(TextDeltaEvent.class, events.get(0)).text());
    }
}
