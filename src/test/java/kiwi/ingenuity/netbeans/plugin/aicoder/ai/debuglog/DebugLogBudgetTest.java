package kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DebugLogBudgetTest {

    @Test
    @Timeout(5)
    void ofMapsEachLimitToItsField() {
        DebugLogBudget budget = DebugLogBudget.of(1_000L, 50, 2_048L, 4_096L);

        assertEquals(new DebugLogBudget(1_000L, 50, 2_048, 4_096L), budget);
    }

    @Test
    @Timeout(5)
    void anEntryLimitAboveIntRangeIsClampedToIntMaxValue() {
        DebugLogBudget budget = DebugLogBudget.of(1L, 1, 3L << 32, 1L);

        assertEquals(Integer.MAX_VALUE, budget.maxEntryBytes());
    }
}
