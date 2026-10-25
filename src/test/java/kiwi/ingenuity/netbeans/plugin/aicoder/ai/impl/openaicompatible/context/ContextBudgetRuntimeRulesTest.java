package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The spec §8.1 runtime rules: the reserve is at most half the window, keep-recent fits inside the window after the
 * reserve and the overhead, and the tool-result share is in [0, 100 %]. Checked at 4 096, 8 192 and 150 000 tokens.
 */
@Timeout(10)
class ContextBudgetRuntimeRulesTest {

    private static final ContextBudget DEFAULTS = new ContextBudget(ContextBudgetSettings.defaults());

    @Test
    void theDefaultReserveFollowsThePercentageAtLargeWindowsAndTheMinimumAtSmallOnes() {
        assertEquals(1228, DEFAULTS.reserve(8192));
        assertEquals(1024, DEFAULTS.reserve(4096));
        assertTrue(Math.abs(DEFAULTS.reserve(150_000) - 22_500) <= 1, "15 % of 150 000");
    }

    @Test
    void anAbsoluteReserveIsCappedAtHalfTheWindow() {
        ContextBudget huge = new ContextBudget(new ContextBudgetSettings(0.15, 1024, 0.5, 3000, 0.25, 2048, null, 0.25));

        assertEquals(2048, huge.reserve(4096), "3 000 would exceed half of 4 096");
        assertTrue(huge.reserve(8192) <= 4096);
    }

    @Test
    void keepRecentAtDefaultsFitsAfterTheReserveAndOverhead() {
        assertEquals(2048, DEFAULTS.keepRecent(4096, 0));
        assertEquals(72, DEFAULTS.keepRecent(4096, 3000), "4 096 − 1 024 reserve − 3 000 overhead");
        assertEquals(37_500, DEFAULTS.keepRecent(150_000, 0), "25 % of 150 000");
    }

    @Test
    void keepRecentNeverExceedsWhatIsLeftAndNeverGoesNegative() {
        ContextBudget absolute = new ContextBudget(new ContextBudgetSettings(0.15, 1024, 0.5, null, 0.25, 2048, 10_000, 0.25));

        assertEquals(6964, absolute.keepRecent(8192, 0), "8 192 − 1 228 reserve");
        assertEquals(0, DEFAULTS.keepRecent(4096, 10_000), "overhead larger than the window leaves nothing");
        assertEquals(0, DEFAULTS.keepRecent(-100, 0), "a negative window counts as zero");
    }

    @Test
    void aToolResultShareAboveOneIsClampedToTheWholeRemainder() {
        ContextBudget share = new ContextBudget(new ContextBudgetSettings(0.15, 1024, 0.5, null, 0.25, 2048, null, 5.0));

        assertEquals(6964, share.toolResultCapTokens(8192), "clamped to 100 % of 8 192 − 1 228");
        assertEquals(1741, DEFAULTS.toolResultCapTokens(8192), "the default 25 % share");
    }

    @Test
    void aNegativeWindowReservesNothing() {
        assertEquals(0, DEFAULTS.reserve(-5));
        assertEquals(0, DEFAULTS.toolResultCapTokens(-5));
    }
}
