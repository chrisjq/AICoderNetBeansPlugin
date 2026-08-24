package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ClaudeAiProcessManager#isInputJsonDeltaFragment}, the predicate that excludes tool-input stream fragments
 * from the "ai json" debug log. Confirmed shapes are taken from live-captured {@code messages.log} lines (see the
 * predicate's javadoc), not guessed.
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
}
