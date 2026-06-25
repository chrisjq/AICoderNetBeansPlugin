package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeStreamJsonParser;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeSessionInfoEvent;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Regression test: the context-window % (SessionInfoEvent.sessionPct) must only
 * be delivered to the session that produced the result — never to a
 * concurrently-running session's listener.
 *
 * ClaudeStreamJsonParser holds cachedContextWindow as an instance field and
 * dispatches events only to its own listener, so isolation is structural. This
 * test makes that guarantee explicit and prevents regressions if the class is
 * ever refactored to use shared state.
 */
class ClaudeStreamJsonParserSessionIsolationTest {

    /**
     * A result line that includes modelUsage so parseResult() can derive both
     * cachedContextWindow and sessionPct in a single call — no prior
     * initCachedContextWindow() needed.
     */
    private static final String RESULT_WITH_CTX
            = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
            + "\"usage\":{\"input_tokens\":50000},"
            + "\"modelUsage\":{\"claude-sonnet-4-6\":{\"contextWindow\":200000}}}";

    @Test
    void sessionPct_fromResultEvent_isDeliveredToItsOwnListenerOnly() {
        List<AiProcessEvent> session1Events = new ArrayList<>();
        List<AiProcessEvent> session2Events = new ArrayList<>();

        ClaudeStreamJsonParser parser1 = new ClaudeStreamJsonParser(session1Events::add);
        ClaudeStreamJsonParser parser2 = new ClaudeStreamJsonParser(session2Events::add);

        // Only parser1 processes a result line that emits a SessionInfoEvent with ctx%
        parser1.parseLine(RESULT_WITH_CTX);

        // parser2's listener must receive nothing — strict isolation
        assertEquals(0, session2Events.size(),
                "Session 2 listener must not receive any events when session 1's parser fires");

        // Confirm the assertion above is meaningful: parser1 must have emitted at
        // least one SessionInfoEvent with a valid sessionPct
        long sessionPctCount = session1Events.stream()
                .filter(e -> e instanceof ClaudeSessionInfoEvent sie && sie.hasSessionPct())
                .count();
        assertTrue(sessionPctCount >= 1,
                "Session 1 should have received at least one SessionInfoEvent with sessionPct; "
                + "got events: " + session1Events);
    }

    @Test
    void cachedContextWindow_isNotSharedAcrossInstances() {
        // Verify that initCachedContextWindow on one parser does not affect another
        List<AiProcessEvent> session1Events = new ArrayList<>();
        List<AiProcessEvent> session2Events = new ArrayList<>();

        ClaudeStreamJsonParser parser1 = new ClaudeStreamJsonParser(session1Events::add);
        ClaudeStreamJsonParser parser2 = new ClaudeStreamJsonParser(session2Events::add);

        // Prime parser1's context window
        parser1.initCachedContextWindow(200000L);

        // Now parse a result line on parser2 that has input_tokens but no modelUsage.
        // Because parser2's cachedContextWindow is still 0, it cannot compute a sessionPct
        // and therefore must NOT emit a SessionInfoEvent.
        String resultWithoutModelUsage
                = "{\"type\":\"result\",\"subtype\":\"success\","
                + "\"usage\":{\"input_tokens\":50000}}";

        parser2.parseLine(resultWithoutModelUsage);

        long session2SessionPctCount = session2Events.stream()
                .filter(e -> e instanceof ClaudeSessionInfoEvent sie && sie.hasSessionPct())
                .count();
        assertEquals(0, session2SessionPctCount,
                "Session 2 must not inherit session 1's cachedContextWindow — "
                + "the field must be instance-scoped");

        // Sanity: parser1 can compute sessionPct with its primed contextWindow
        parser1.parseLine(resultWithoutModelUsage);
        long session1SessionPctCount = session1Events.stream()
                .filter(e -> e instanceof ClaudeSessionInfoEvent sie && sie.hasSessionPct())
                .count();
        assertTrue(session1SessionPctCount >= 1,
                "Session 1 (with primed cachedContextWindow) should emit a SessionInfoEvent");
    }
}
