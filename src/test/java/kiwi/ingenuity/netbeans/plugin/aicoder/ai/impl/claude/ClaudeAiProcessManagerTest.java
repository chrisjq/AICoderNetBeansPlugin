package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ClaudeAiProcessManager#isInputJsonDeltaFragment} (the predicate that excludes tool-input stream fragments
 * from the "ai json" debug log — confirmed shapes are taken from live-captured {@code messages.log} lines, not guessed)
 * and {@link ClaudeAiProcessManager#configureEffort} (the gate that keeps a corrupted or hand-edited stored effort
 * level like "banana" from ever reaching {@code --effort}, which would hard-fail the CLI at spawn).
 */
class ClaudeAiProcessManagerTest {

    @Test
    void recognisesARealCapturedInputJsonDeltaFragment() {
        String line = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"sessionId\\\": \\\"b154400c-bbb\"}},"
                + "\"session_id\":\"sid-1\",\"parent_tool_use_id\":null,\"uuid\":\"u-1\"}";

        assertTrue(ClaudeAiProcessManager.isInputJsonDeltaFragment(line));
    }

    @Test
    void recognisesAnEmptyPartialJsonFragment() {
        String line = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\"}},"
                + "\"session_id\":\"sid-1\",\"parent_tool_use_id\":null,\"uuid\":\"u-1\"}";

        assertTrue(ClaudeAiProcessManager.isInputJsonDeltaFragment(line));
    }

    @Test
    void doesNotCatchTextDeltaSharingTheSameContentBlockDeltaCarrier() {
        String line = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}},"
                + "\"session_id\":\"sid-1\",\"parent_tool_use_id\":null,\"uuid\":\"u-1\"}";

        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(line), line);
    }

    @Test
    void doesNotCatchMessageStartUsageAccounting() {
        String line = "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\","
                + "\"message\":{\"usage\":{\"input_tokens\":123}}},\"session_id\":\"sid-1\"}";

        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(line));
    }

    @Test
    void doesNotCatchMessageStopOrOtherContentBlockLifecycleEvents() {
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_stop\"},\"session_id\":\"sid-1\"}"));
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\"},\"session_id\":\"sid-1\"}"));
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":0},\"session_id\":\"sid-1\"}"));
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_stop\",\"index\":0},\"session_id\":\"sid-1\"}"));
    }

    @Test
    void doesNotCatchNonStreamEventTopLevelTypes() {
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(
                "{\"type\":\"assistant\",\"message\":{\"content\":[]}}"));
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(
                "{\"type\":\"result\",\"subtype\":\"success\"}"));
    }

    @Test
    void failsSafeOnUnparseableOrMissingInput() {
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(null));
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(""));
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment("not json at all"));
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment("{\"type\":\"stream_event\"}"));
        assertFalse(ClaudeAiProcessManager.isInputJsonDeltaFragment(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\"}}"));
    }

    @Test
    void configureEffort_unknownLevelIsDroppedWithOneInfoEvent() {
        List<StatusEvent> events = new ArrayList<>();
        ClaudeAiProcessManager mgr = new ClaudeAiProcessManager(e -> {
            if (e instanceof StatusEvent se) {
                events.add(se);
            }
        });

        mgr.configureEffort("banana");

        assertNull(mgr.getConfiguredEffort(), "a hand-edited/corrupted value like \"banana\" must never reach --effort");
        assertEquals(1, events.size());
        assertEquals(StatusEventTypeEnum.INFO, events.get(0).type(),
                     "the drop must be surfaced to the user via exactly one INFO event");
    }

    @Test
    void configureEffort_anythingNotInTheKnownFiveLevelsIsDropped() {
        ClaudeAiProcessManager mgr = new ClaudeAiProcessManager(e -> {
        });

        mgr.configureEffort("banana");
        assertNull(mgr.getConfiguredEffort());
        mgr.configureEffort("MEDIUM"); // wrong casing is still not a known level
        assertNull(mgr.getConfiguredEffort());
        mgr.configureEffort("very-high");
        assertNull(mgr.getConfiguredEffort());
    }

    @Test
    void configureEffort_acceptsOnlyTheFiveKnownLevels() {
        for (String known : List.of("low", "medium", "high", "xhigh", "max")) {
            ClaudeAiProcessManager mgr = new ClaudeAiProcessManager(e -> {
            });
            mgr.configureEffort(known);
            assertEquals(known, mgr.getConfiguredEffort(), "\"" + known + "\" is a valid Claude effort level");
        }
    }

    @Test
    void configureEffort_nullOrBlankMeansOmitEffortFlag() {
        ClaudeAiProcessManager mgr = new ClaudeAiProcessManager(e -> {
        });

        mgr.configureEffort(null);
        assertNull(mgr.getConfiguredEffort());
        mgr.configureEffort("");
        assertNull(mgr.getConfiguredEffort());
        mgr.configureEffort("   ");
        assertNull(mgr.getConfiguredEffort());
    }
}
