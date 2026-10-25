package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ContextBudgetTest {

    private static final ContextBudget DEFAULTS = new ContextBudget(ContextBudgetSettings.defaults());

    @Test
    @Timeout(5)
    void reserveAtEachWindowFollowsThePercentageWithAMinimum() {
        assertEquals(1024, DEFAULTS.reserve(4_096));
        assertEquals(1228, DEFAULTS.reserve(8_192));
        assertEquals(2457, DEFAULTS.reserve(16_384));
        assertEquals(19660, DEFAULTS.reserve(131_072));
    }

    @Test
    @Timeout(5)
    void reserveNeverExceedsHalfTheWindowEvenWhenAbsolute() {
        ContextBudget budget = new ContextBudget(
                new ContextBudgetSettings(0.15, 1024, 0.5, 9_000, 0.25, 2048, null, 0.25));

        assertEquals(2048, budget.reserve(4_096));
    }

    @Test
    @Timeout(5)
    void anAbsoluteReserveWinsOverThePercentage() {
        ContextBudget budget = new ContextBudget(
                new ContextBudgetSettings(0.15, 1024, 0.5, 300, 0.25, 2048, null, 0.25));

        assertEquals(300, budget.reserve(8_192));
    }

    @Test
    @Timeout(5)
    void keepRecentAtEachWindowFollowsThePercentageWithAMinimum() {
        assertEquals(2048, DEFAULTS.keepRecent(4_096, 0));
        assertEquals(2048, DEFAULTS.keepRecent(8_192, 0));
        assertEquals(4096, DEFAULTS.keepRecent(16_384, 0));
        assertEquals(32768, DEFAULTS.keepRecent(131_072, 0));
    }

    @Test
    @Timeout(5)
    void keepRecentIsClampedToTheWindowLeftAfterReserveAndOverhead() {
        assertEquals(1072, DEFAULTS.keepRecent(4_096, 2_000));
        assertEquals(0, DEFAULTS.keepRecent(4_096, 3_500));
    }

    @Test
    @Timeout(5)
    void anAbsoluteKeepRecentWinsOverThePercentage() {
        ContextBudget budget = new ContextBudget(
                new ContextBudgetSettings(0.15, 1024, 0.5, null, 0.25, 2048, 500, 0.25));

        assertEquals(500, budget.keepRecent(4_096, 0));
    }

    @Test
    @Timeout(5)
    void toolResultCapInTokensIsTheShareOfWhatIsLeftAfterReserve() {
        assertEquals(768, DEFAULTS.toolResultCapTokens(4_096));
        assertEquals(1741, DEFAULTS.toolResultCapTokens(8_192));
        assertEquals(3481, DEFAULTS.toolResultCapTokens(16_384));
        assertEquals(27853, DEFAULTS.toolResultCapTokens(131_072));
    }

    @Test
    @Timeout(5)
    void toolResultCapInCharsUsesTheTokenRatio() {
        assertEquals(2688, DEFAULTS.toolResultCapChars(4_096, 3.5d));
        assertEquals(97485, DEFAULTS.toolResultCapChars(131_072, 3.5d));
    }
}
