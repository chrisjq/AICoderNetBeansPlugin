package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ContextBudgetFactoriesTest {

    @Test
    void contextBudgetFromAnUnsetSessionIsTheDefaults() {
        assertEquals(ContextBudgetSettings.defaults(),
                     ContextBudgetSettings.from(new OpenAiCompatibleSessionSettings()));
    }

    @Test
    void contextBudgetFromASessionConvertsPercentagesAndKeepsAbsolutes() {
        OpenAiCompatibleSessionSettings session = new OpenAiCompatibleSessionSettings();
        session.setReservePercent(20);
        session.setReserveMaxSharePercent(40);
        session.setReserveAbsolute(4096);
        session.setKeepRecentPercent(30);
        session.setKeepRecentAbsolute(8000);
        session.setToolResultSharePercent(10);

        ContextBudgetSettings budget = ContextBudgetSettings.from(session);

        assertEquals(0.20, budget.reservePercent(), 1e-9);
        assertEquals(0.40, budget.reserveMaxShare(), 1e-9);
        assertEquals(4096, budget.reserveAbsolute());
        assertEquals(0.30, budget.keepRecentPercent(), 1e-9);
        assertEquals(8000, budget.keepRecentAbsolute());
        assertEquals(0.10, budget.toolResultShare(), 1e-9);
    }

    @Test
    void anAbsoluteOfZeroMeansThePercentageApplies() {
        OpenAiCompatibleSessionSettings session = new OpenAiCompatibleSessionSettings();
        session.setReserveAbsolute(0);
        session.setKeepRecentAbsolute(0);

        ContextBudgetSettings budget = ContextBudgetSettings.from(session);

        assertNull(budget.reserveAbsolute());
        assertNull(budget.keepRecentAbsolute());
    }

    @Test
    void contextBudgetFromNullIsTheDefaults() {
        assertEquals(ContextBudgetSettings.defaults(), ContextBudgetSettings.from(null));
    }

    @Test
    void prunerFromAnUnsetSessionUsesTheDefaults() {
        Pruner pruner = Pruner.from(new OpenAiCompatibleSessionSettings());

        assertEquals(Pruner.DEFAULT_THRESHOLD_CHARS, pruner.thresholdChars());
        assertEquals(Pruner.DEFAULT_CHARS_KEPT, pruner.charsKept());
    }

    @Test
    void prunerFromASessionUsesItsThresholdAndCharsKept() {
        OpenAiCompatibleSessionSettings session = new OpenAiCompatibleSessionSettings();
        session.setPruneThresholdChars(1000);
        session.setPruneCharsKept(300);

        Pruner pruner = Pruner.from(session);

        assertEquals(1000, pruner.thresholdChars());
        assertEquals(300, pruner.charsKept());
    }
}
