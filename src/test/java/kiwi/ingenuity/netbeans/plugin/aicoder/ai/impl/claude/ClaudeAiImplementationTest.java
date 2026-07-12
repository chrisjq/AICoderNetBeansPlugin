package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ClaudeAiImplementation#shouldThrottleUsageFetch} — the pure
 * gate that decides whether a usage-endpoint fetch attempt should be skipped
 * because it's too soon after the previous one, given a learned minimum
 * interval derived from an earlier 429.
 */
class ClaudeAiImplementationTest {

    @Test
    void noThrottleUntilAnIntervalHasBeenLearned() {
        assertFalse(ClaudeAiImplementation.shouldThrottleUsageFetch(1_000_000L, 999_999L, 0));
    }

    @Test
    void throttlesWhenLessThanLearnedIntervalHasElapsed() {
        assertTrue(ClaudeAiImplementation.shouldThrottleUsageFetch(100_000L, 99_000L, 5_000L));
    }

    @Test
    void doesNotThrottleOnceLearnedIntervalHasFullyElapsed() {
        assertFalse(ClaudeAiImplementation.shouldThrottleUsageFetch(110_000L, 100_000L, 5_000L));
    }

    @Test
    void doesNotThrottleExactlyAtTheBoundary() {
        // now - lastAttempt == learnedInterval: not strictly less than, so allowed through.
        assertFalse(ClaudeAiImplementation.shouldThrottleUsageFetch(105_000L, 100_000L, 5_000L));
    }
}
