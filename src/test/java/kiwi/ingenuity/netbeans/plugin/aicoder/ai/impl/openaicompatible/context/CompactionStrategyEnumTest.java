package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CompactionStrategyEnumTest {

    @Test
    @Timeout(5)
    void declarationOrderAndDisplayNames() {
        assertEquals(List.of(CompactionStrategyEnum.AUTO, CompactionStrategyEnum.SUMMARISE,
                             CompactionStrategyEnum.DROP, CompactionStrategyEnum.NONE),
                     List.of(CompactionStrategyEnum.values()));
        assertEquals("Auto", CompactionStrategyEnum.AUTO.displayName());
        assertEquals("Summarise", CompactionStrategyEnum.SUMMARISE.displayName());
        assertEquals("Drop oldest", CompactionStrategyEnum.DROP.displayName());
        assertEquals("None", CompactionStrategyEnum.NONE.displayName());
    }

    @Test
    @Timeout(5)
    void summariseResolvesToSummariseAndAutoLeavesTheChoiceToTheTurn() {
        assertEquals(ResolvedCompactionEnum.DROP, CompactionStrategyEnum.AUTO.resolve());
        assertEquals(ResolvedCompactionEnum.SUMMARISE, CompactionStrategyEnum.SUMMARISE.resolve());
        assertEquals(ResolvedCompactionEnum.DROP, CompactionStrategyEnum.DROP.resolve());
        assertEquals(ResolvedCompactionEnum.NONE, CompactionStrategyEnum.NONE.resolve());
    }
}
