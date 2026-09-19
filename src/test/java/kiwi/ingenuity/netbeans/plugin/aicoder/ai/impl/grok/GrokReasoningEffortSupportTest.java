package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins the static per-model reasoning-effort table against the spec: {@code grok-4.6} supports
 * low/medium/high/xhigh, {@code grok-4.5} supports low/medium/high, and any other/unknown model supports none.
 */
class GrokReasoningEffortSupportTest {

    @Test
    void grok46SupportsAllFourLevels() {
        assertEquals(List.of("low", "medium", "high", "xhigh"), GrokReasoningEffortSupport.supportedFor("grok-4.6"));
    }

    @Test
    void grok45SupportsThreeLevelsButNotXhigh() {
        List<String> supported = GrokReasoningEffortSupport.supportedFor("grok-4.5");
        assertEquals(List.of("low", "medium", "high"), supported);
        assertTrue(!supported.contains("xhigh"));
    }

    @Test
    void unknownModelSupportsNoLevels() {
        assertTrue(GrokReasoningEffortSupport.supportedFor("grok-1-ancient").isEmpty());
    }

    @Test
    void nullModelSupportsNoLevels() {
        assertTrue(GrokReasoningEffortSupport.supportedFor(null).isEmpty());
    }

    @Test
    void allKnownLevelsIsTheUnionAcrossModels() {
        assertEquals(List.of("low", "medium", "high", "xhigh"), GrokReasoningEffortSupport.allKnownLevels());
    }
}
