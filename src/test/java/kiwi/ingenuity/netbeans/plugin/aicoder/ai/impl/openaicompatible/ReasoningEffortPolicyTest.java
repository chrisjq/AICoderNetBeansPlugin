package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ReasoningEffortPolicyTest {

    @Test
    void clearsUnsupportedPinnedValue() {
        ReasoningEffortPolicy.Decision result = ReasoningEffortPolicy.effective("xhigh", "low", List.of("low"));
        assertEquals(null, result.levelToSendOrNull());
        assertTrue(result.clearPinned());
    }

    @Test
    void omitsUnsupportedInheritedValue() {
        ReasoningEffortPolicy.Decision result = ReasoningEffortPolicy.effective(null, "xhigh", List.of("low"));
        assertEquals(null, result.levelToSendOrNull());
        assertTrue(!result.clearPinned());
    }

    @Test
    void sendsOptimisticallyWhenLevelsUnknown() {
        ReasoningEffortPolicy.Decision result = ReasoningEffortPolicy.effective(null, "high", null);
        assertEquals("high", result.levelToSendOrNull());
    }
}
