package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AiTopComponentLaunchTest {

    @Test
    @Timeout(10)
    void disabledOllamaStillLaunches() {
        assertFalse(AiTopComponent.shouldRefuseDisabledType(AiTypeEnum.OLLAMA_LOCAL, false));
    }

    @Test
    @Timeout(10)
    void otherDisabledTypeIsRefused() {
        assertTrue(AiTopComponent.shouldRefuseDisabledType(AiTypeEnum.CODEX, false));
        assertFalse(AiTopComponent.shouldRefuseDisabledType(AiTypeEnum.CODEX, true));
    }
}
