package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiStreamJsonParser;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiThinkingLevelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiToolResultEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiStreamJsonParserTest {

    private static final String ACCEPTED_MARKER
            = "SUCCESS " + (char) 0x2014 + " the user accepted";

    private List<AiProcessEvent> parse(String... lines) {
        List<AiProcessEvent> events = new ArrayList<>();
        PiStreamJsonParser parser = new PiStreamJsonParser(events::add);
        for (String line : lines) {
            parser.parseLine(line);
        }
        return events;
    }

    @Test
    void agentStart_andThinkingDelta_surfaceThrowingStatus() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"agent_start\"}",
                "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_delta\",\"delta\":\"hmm\"}}",
                "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_delta\",\"delta\":\"hmm2\"}}");

        long thinking = events.stream().filter(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.THINKING).count();
        assertEquals(2, thinking, "agent_start plus the first thinking_delta each surface THINKING");
    }

    @Test
    void textContent_producesTextDeltaEvent() {
        String line = "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"delta\":\"Hello world\"}}";

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        assertInstanceOf(TextDeltaEvent.class, events.get(0));
        assertEquals("Hello world", ((TextDeltaEvent) events.get(0)).text());
    }

    @Test
    void toolExecutionStart_producesToolUseEventWithPath() {
        String line = "{\"type\":\"tool_execution_start\",\"toolCallId\":\"tc1\",\"toolName\":\"Write\","
                + "\"args\":{\"path\":\"/foo/Bar.java\",\"content\":\"hi\"}}";

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        ToolUseEvent e = assertInstanceOf(ToolUseEvent.class, events.get(0));
        assertEquals("Write", e.toolName());
        assertEquals("/foo/Bar.java", e.filePath());
        assertEquals(ToolUseEvent.Kind.OTHER, e.kind());
    }

    @Test
    void toolExecutionEnd_producesToolResultEvent() {
        String line = "{\"type\":\"tool_execution_end\",\"toolCallId\":\"tc1\",\"toolName\":\"Write\","
                + "\"result\":{\"content\":[{\"text\":\"File dumped\"}]},\"isError\":false}";

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        PiToolResultEvent e = assertInstanceOf(PiToolResultEvent.class, events.get(0));
        assertEquals("tc1", e.toolCallId());
        assertEquals("Write", e.toolName());
        assertEquals("File dumped", e.resultText());
        assertFalse(e.isError());
    }

    @Test
    void toolExecutionEnd_multiPartContent_joinsAllNonBlankTextParts() {
        String line = "{\"type\":\"tool_execution_end\",\"toolCallId\":\"tc4\",\"toolName\":\"Read\","
                + "\"result\":{\"content\":[{\"text\":\"first part\"},{\"text\":\"\"},{\"text\":\"second part\"}]},"
                + "\"isError\":false}";

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        PiToolResultEvent e = assertInstanceOf(PiToolResultEvent.class, events.get(0));
        assertEquals("first part\nsecond part", e.resultText(),
                     "every non-blank text part must be kept, not just the first — a blank part is skipped, not "
                     + "joined as an empty line");
    }

    @Test
    void toolExecutionEnd_resultContentObjectFallsBackToText() {
        String line = "{\"type\":\"tool_execution_end\",\"toolCallId\":\"tc2\",\"toolName\":\"Read\","
                + "\"result\":{\"text\":\"file contents\"},\"isError\":false}";

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        PiToolResultEvent e = assertInstanceOf(PiToolResultEvent.class, events.get(0));
        assertEquals("file contents", e.resultText());
    }

    @Test
    void toolExecutionEnd_acceptedMarker_downgradesIsError() {
        String line = "{\"type\":\"tool_execution_end\",\"toolCallId\":\"tc3\",\"toolName\":\"Edit\","
                + "\"result\":{\"content\":[{\"text\":\"" + ACCEPTED_MARKER + "\"}]},\"isError\":true}";

        List<AiProcessEvent> events = parse(line);

        assertEquals(1, events.size());
        PiToolResultEvent e = assertInstanceOf(PiToolResultEvent.class, events.get(0));
        assertFalse(e.isError(), "a user-accepted write/edit is a rejection notice, not an error");
        assertEquals(ACCEPTED_MARKER, e.resultText(),
                     "resultText must match the exact accepted-marker text (the same constant this test used to "
                     + "build the input), not merely start with \"SUCCESS\" — a dash or wording drift between the "
                     + "extension and the parser must fail this test");
    }

    @Test
    void agentSettled_closesTurnWithTurnCompleteThenReady() {
        List<AiProcessEvent> events = parse("{\"type\":\"agent_settled\"}");

        assertEquals(2, events.size());
        assertInstanceOf(TurnCompleteEvent.class, events.get(0));
        StatusEvent ready = assertInstanceOf(StatusEvent.class, events.get(1));
        assertEquals(StatusEventTypeEnum.READY, ready.type());
        assertTrue(ready.text().contains("Pi"));
    }

    @Test
    void messageEnd_stopReasonError_surfacesFailedButNoTurnComplete() {
        // stopReason/errorMessage live under message_end.message (pi's AssistantMessage), not top-level — verified
        // live against a real pi 0.85.1 process.
        List<AiProcessEvent> events = parse(
                "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\","
                + "\"errorMessage\":\"cannot help\"}}");

        boolean failed = events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED
                && se.text() != null && se.text().contains("cannot help"));
        assertTrue(failed, "stopReason error must surface FAILED");
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent));
    }

    @Test
    void messageEnd_stopReasonAborted_producesNoEvents() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"aborted\"}}");
        assertTrue(events.isEmpty(), "abort is owned by the run manager; the parser renders nothing");
    }

    @Test
    void messageEnd_userMessageHasNoStopReason_doesNotCrash() {
        // pi fires message_end for the user's own echoed message too (no stopReason field at all — it's a
        // UserMessage, not an AssistantMessage); must not throw or misfire FAILED.
        List<AiProcessEvent> events = parse(
                "{\"type\":\"message_end\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"hi\"}],\"timestamp\":1}}");
        assertTrue(events.isEmpty());
    }

    @Test
    void responseFailure_userFacingCommand_surfacesFailed() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"response\",\"command\":\"prompt\",\"success\":false,\"error\":\"no such model\"}");

        boolean failed = events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED
                && se.text() != null && se.text().contains("no such model"));
        assertTrue(failed, "a failed prompt/steer/abort response is a direct result of a user action");
    }

    /**
     * get_state/get_available_models/get_session_stats/set_model/get_available_thinking_levels/set_thinking_level are
     * background/housekeeping commands PiAiProcessManager already treats as best-effort and silently swallows on
     * failure — surfacing FAILED for one of these would show an error unrelated to anything the user did. set_model is
     * deliberately used here (not get_session_stats) to also prove this isn't merely "some background commands", now
     * that a DIFFERENT background command moved out of this test's spot above.
     */
    @Test
    void responseFailure_backgroundCommand_producesNoEvent() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"response\",\"command\":\"set_model\",\"success\":false,\"error\":\"no such model\"}");
        assertTrue(events.isEmpty(), "a failed background/housekeeping command must not surface FAILED");
    }

    /**
     * compact IS user-facing but is deliberately excluded from USER_FACING_COMMANDS: PiAiImplementation.compact()'s own
     * whenComplete already emits "Compact failed: …" for a rejected response and must stay the single owner of that
     * error surface (it also reports no-session/failed-send cases this parser never sees) — including compact here too
     * double-reported a rejected compact.
     */
    @Test
    void responseFailure_compact_producesNoEvent() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"response\",\"command\":\"compact\",\"success\":false,\"error\":\"nothing to compact\"}");
        assertTrue(events.isEmpty(),
                   "a failed compact must not surface FAILED here — PiAiImplementation.compact() already does");
    }

    @Test
    void responseFailure_getSessionStats_producesNoEvent() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"response\",\"command\":\"get_session_stats\",\"success\":false,\"error\":\"transient hiccup\"}");
        assertTrue(events.isEmpty(), "a transient get_session_stats failure must not surface FAILED");
    }

    /**
     * A successful {@code get_state}/{@code get_available_models}/{@code get_session_stats} response is read directly
     * by {@code PiAiProcessManager} off the id-correlated future instead — the parser itself must produce no event for
     * any of them. This is exactly the behaviour that changed (previously these commands each fired their own event);
     * only the failure branch was covered before this test existed.
     */
    @Test
    void responseSuccess_getState_producesNoEvents() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"response\",\"command\":\"get_state\",\"success\":true,"
                + "\"data\":{\"model\":{\"id\":\"claude-sonnet-5\",\"provider\":\"github-copilot\"}}}");
        assertTrue(events.isEmpty(), "a successful get_state response must produce no parser events");
    }

    @Test
    void responseSuccess_getAvailableModels_producesNoEvents() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"response\",\"command\":\"get_available_models\",\"success\":true,"
                + "\"data\":{\"models\":[{\"id\":\"claude-sonnet-5\",\"provider\":\"github-copilot\"}]}}");
        assertTrue(events.isEmpty(), "a successful get_available_models response must produce no parser events");
    }

    @Test
    void responseSuccess_getSessionStats_producesNoEvents() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"response\",\"command\":\"get_session_stats\",\"success\":true,"
                + "\"data\":{\"contextUsage\":{\"tokens\":100,\"contextWindow\":1000,\"percent\":10.0}}}");
        assertTrue(events.isEmpty(), "a successful get_session_stats response must produce no parser events");
    }

    /**
     * {@code queue_update} and {@code tool_execution_update} are kept in {@code PiEventTypeEnum} specifically so
     * {@code PiEventTypeEnum.of()} resolves them to a known constant instead of logging them as an unhandled type on
     * every occurrence — the parser itself renders nothing for either. Guards the behaviour those comments document.
     */
    @Test
    void queueUpdate_producesNoEvents() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"queue_update\",\"steering\":[],\"followUp\":[]}");
        assertTrue(events.isEmpty(), "queue_update must be silently ignored");
    }

    @Test
    void toolExecutionUpdate_producesNoEvents() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"tool_execution_update\",\"toolCallId\":\"tc1\",\"toolName\":\"Write\","
                + "\"args\":{},\"partialResult\":\"...\"}");
        assertTrue(events.isEmpty(), "tool_execution_update must be silently ignored");
    }

    @Test
    void compactionStart_surfacesInfoRegardlessOfReason() {
        // reason:"manual" (our own compact command) and reason:"threshold"|"overflow" (pi's own automatic
        // compaction, never requested by the plugin) are both surfaced identically — verified against
        // agent-session.d.ts.
        for (String reason : List.of("manual", "threshold", "overflow")) {
            List<AiProcessEvent> events = parse(
                    "{\"type\":\"compaction_start\",\"reason\":\"" + reason + "\"}");
            assertEquals(1, events.size());
            StatusEvent se = assertInstanceOf(StatusEvent.class, events.get(0));
            assertEquals(StatusEventTypeEnum.INFO, se.type());
        }
    }

    @Test
    void compactionEnd_success_surfacesInfo() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"compaction_end\",\"reason\":\"threshold\",\"aborted\":false,\"willRetry\":false}");
        assertEquals(1, events.size());
        StatusEvent se = assertInstanceOf(StatusEvent.class, events.get(0));
        assertEquals(StatusEventTypeEnum.INFO, se.type());
        assertTrue(se.text().contains("compacted"));
    }

    @Test
    void compactionEnd_aborted_surfacesInfo() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"compaction_end\",\"reason\":\"manual\",\"aborted\":true,\"willRetry\":false}");
        assertEquals(1, events.size());
        StatusEvent se = assertInstanceOf(StatusEvent.class, events.get(0));
        assertEquals(StatusEventTypeEnum.INFO, se.type());
        assertTrue(se.text().contains("aborted"));
    }

    @Test
    void compactionEnd_error_surfacesInfoWithMessage() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"compaction_end\",\"reason\":\"overflow\",\"aborted\":false,\"willRetry\":false,"
                + "\"errorMessage\":\"context too large\"}");
        assertEquals(1, events.size());
        StatusEvent se = assertInstanceOf(StatusEvent.class, events.get(0));
        assertEquals(StatusEventTypeEnum.INFO, se.type());
        assertTrue(se.text().contains("context too large"));
    }

    @Test
    void thinkingLevelChanged_producesEventWithLevel() {
        List<AiProcessEvent> events = parse("{\"type\":\"thinking_level_changed\",\"level\":\"low\"}");
        assertEquals(1, events.size());
        PiThinkingLevelChangedEvent e = assertInstanceOf(PiThinkingLevelChangedEvent.class, events.get(0));
        assertEquals("low", e.level());
    }

    @Test
    void thinkingLevelChanged_missingLevel_producesNoEvents() {
        List<AiProcessEvent> events = parse("{\"type\":\"thinking_level_changed\"}");
        assertTrue(events.isEmpty());
    }

    @Test
    void confirmRequest_emitsConfirmEventAndReplyOnDecision() {
        List<AiProcessEvent> events = new ArrayList<>();
        List<String> ui = new ArrayList<>();
        PiStreamJsonParser parser = new PiStreamJsonParser(events::add);
        parser.setUiResponseSender(ui::add);
        parser.parseLine("{\"type\":\"extension_ui_request\",\"method\":\"confirm\",\"id\":\"ui-1\","
                + "\"title\":\"Approve Write?\",\"message\":\"details\"}");

        assertEquals(1, events.size());
        ConfirmEvent ce = assertInstanceOf(ConfirmEvent.class, events.get(0));
        assertEquals("Approve Write?", ce.displayText());
        assertTrue(ui.isEmpty(), "no reply before the decision is made");

        ce.response().complete(PermissionDecision.allowed());
        assertEquals(List.of("{\"type\":\"extension_ui_response\",\"id\":\"ui-1\",\"confirmed\":true}"),
                     ui, "resolving the decision must write the extension_ui_response");
    }

    @Test
    void confirmRequest_noTitleFallsBackToMessage() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"extension_ui_request\",\"method\":\"confirm\",\"id\":\"ui-2\",\"message\":\"really?\"}");
        assertEquals(1, events.size());
        ConfirmEvent ce = assertInstanceOf(ConfirmEvent.class, events.get(0));
        assertEquals("really?", ce.displayText());
    }

    @Test
    void confirmRequest_deniedWritesCancelled() {
        List<AiProcessEvent> events = new ArrayList<>();
        List<String> ui = new ArrayList<>();
        PiStreamJsonParser parser = new PiStreamJsonParser(events::add);
        parser.setUiResponseSender(ui::add);
        parser.parseLine(
                "{\"type\":\"extension_ui_request\",\"method\":\"confirm\",\"id\":\"ui-3\",\"message\":\"go?\"}");
        ConfirmEvent ce = assertInstanceOf(ConfirmEvent.class, events.get(0));
        ce.response().complete(PermissionDecision.denied("no"));
        assertEquals(List.of("{\"type\":\"extension_ui_response\",\"id\":\"ui-3\",\"cancelled\":true}"), ui);
    }

    @Test
    void unsupportedRequestMethod_isAutoCancelled() {
        List<AiProcessEvent> events = new ArrayList<>();
        List<String> ui = new ArrayList<>();
        PiStreamJsonParser parser = new PiStreamJsonParser(events::add);
        parser.setUiResponseSender(ui::add);
        parser.parseLine(
                "{\"type\":\"extension_ui_request\",\"method\":\"input\",\"id\":\"ui-9\",\"title\":\"ask\"}");

        assertTrue(events.isEmpty());
        assertEquals(List.of("{\"type\":\"extension_ui_response\",\"id\":\"ui-9\",\"cancelled\":true}"), ui);
    }

    @Test
    void notifyRequest_surfacesInfoStatus() {
        List<AiProcessEvent> events = parse(
                "{\"type\":\"extension_ui_request\",\"method\":\"notify\",\"title\":\"pip\",\"message\":\"done\"}");

        assertEquals(1, events.size());
        StatusEvent se = assertInstanceOf(StatusEvent.class, events.get(0));
        assertEquals(StatusEventTypeEnum.INFO, se.type());
        assertEquals("pip", se.text());
    }

    @Test
    void agentEnd_willRetry_surfacesInfo() {
        List<AiProcessEvent> events = parse("{\"type\":\"agent_end\",\"willRetry\":true}");
        assertEquals(1, events.size());
        StatusEvent se = assertInstanceOf(StatusEvent.class, events.get(0));
        assertEquals(StatusEventTypeEnum.INFO, se.type());
        assertTrue(se.text().contains("retry"));
    }

    @Test
    void malformedMessageEndLine_emitsFailedStatusEvent() {
        String line = "{\"type\":\"message_end\",\"stopReason\":\"error\",\"errorMessage\":\"boom";
        List<AiProcessEvent> events = parse(line);

        boolean failed = events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.FAILED
                && se.text() != null && se.text().contains("could not be parsed"));
        assertTrue(failed);
        assertEquals(1, events.size());
    }

    @Test
    void malformedOtherLine_emitsNoEvents() {
        List<AiProcessEvent> events = parse("{\"type\":\"message_update\",\"delta\":\"trunc");
        assertTrue(events.isEmpty());
    }

    /**
     * Real pi can answer a send (here a rejected compact) as {@code {"type":"response","success":false,
     * "error":"cannot compact now","id":…}} with no {@code command} field — and test fakes often omit it too.
     * {@code PiRpcCommandEnum.of(null)} returns null, and the old {@code USER_FACING_COMMANDS.contains(...)} check NPEs
     * on a {@code null} argument on Java 21, landing in the parseLine catch as a bogus "Skipping unparseable pi line"
     * WARNING. The null/unknown command must be treated as the background/ignored case: no event, no WARNING.
     */
    @Test
    void responseFailure_noCommandField_producesNoEventNoWarning() {
        Logger logger = Logger.getLogger(PiStreamJsonParser.class.getName());
        WarningCapture capture = new WarningCapture();
        logger.addHandler(capture);
        try {
            List<AiProcessEvent> events = parse(
                    "{\"type\":\"response\",\"success\":false,\"error\":\"cannot compact now\",\"id\":\"crv-17\"}");
            assertTrue(events.isEmpty(), "a failed response with no command field must not surface FAILED");
            assertFalse(capture.anyContains("unparseable"),
                        "a well-formed failed response must not be logged as an unparseable line");
        }
        finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void responseFailure_unknownCommand_producesNoEventNoWarning() {
        Logger logger = Logger.getLogger(PiStreamJsonParser.class.getName());
        WarningCapture capture = new WarningCapture();
        logger.addHandler(capture);
        try {
            List<AiProcessEvent> events = parse(
                    "{\"type\":\"response\",\"command\":\"frobnicate\",\"success\":false,\"error\":\"no such command\"}");
            assertTrue(events.isEmpty(), "a failed response for an unknown command must not surface FAILED");
            assertFalse(capture.anyContains("unparseable"),
                        "a well-formed failed response for an unknown command must not be logged as unparseable");
        }
        finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void malformedResponseLine_stillLogsUnparseableWarning() {
        Logger logger = Logger.getLogger(PiStreamJsonParser.class.getName());
        WarningCapture capture = new WarningCapture();
        logger.addHandler(capture);
        try {
            List<AiProcessEvent> events = parse("{\"type\":\"response\",\"success\":fal");
            assertTrue(events.isEmpty());
            assertTrue(capture.anyContains("unparseable"),
                       "genuinely malformed input must still be logged as an unparseable line");
        }
        finally {
            logger.removeHandler(capture);
        }
    }

    private static class WarningCapture extends Handler {

        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        boolean anyContains(String fragment) {
            return records.stream().map(WarningCapture::render)
                    .anyMatch(text -> text.contains(fragment));
        }

        private static String render(LogRecord record) {
            String text = String.valueOf(record.getMessage());
            Object[] params = record.getParameters();
            if (params != null) {
                for (Object param : params) {
                    text += " " + param;
                }
            }
            return text;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
