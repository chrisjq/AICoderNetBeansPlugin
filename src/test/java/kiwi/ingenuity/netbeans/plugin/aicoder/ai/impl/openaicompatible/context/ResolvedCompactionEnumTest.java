package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ResolvedCompactionEnumTest {

    @Test
    @Timeout(5)
    void declarationOrder() {
        assertEquals(List.of(ResolvedCompactionEnum.SUMMARISE, ResolvedCompactionEnum.DROP,
                             ResolvedCompactionEnum.NONE),
                     List.of(ResolvedCompactionEnum.values()));
    }

    @Test
    @Timeout(5)
    void resolveKeepsSummariseAndNoneAndLeavesAutoToTheTurn() {
        assertEquals(ResolvedCompactionEnum.SUMMARISE, ResolvedCompactionEnum.resolve(CompactionStrategyEnum.SUMMARISE));
        assertEquals(ResolvedCompactionEnum.DROP, ResolvedCompactionEnum.resolve(CompactionStrategyEnum.AUTO));
        assertEquals(ResolvedCompactionEnum.DROP, ResolvedCompactionEnum.resolve(CompactionStrategyEnum.DROP));
        assertEquals(ResolvedCompactionEnum.NONE, ResolvedCompactionEnum.resolve(CompactionStrategyEnum.NONE));
        assertEquals(ResolvedCompactionEnum.DROP, ResolvedCompactionEnum.resolve(null));
    }
}
