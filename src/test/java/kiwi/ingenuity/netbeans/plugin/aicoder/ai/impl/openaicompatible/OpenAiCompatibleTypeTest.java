package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolRestartFlow;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleTypeTest {

    @Test
    @Timeout(5)
    void constantMatchesTheSpec() {
        var t = AiTypeEnum.OPENAI_COMPATIBLE;
        assertEquals("OpenAI Compatible", t.displayName());
        assertEquals("openai_compatible", t.key());
        assertTrue(t.isEnabledByDefault());
        assertFalse(t.usesLegacyOllamaSettings());
        assertInstanceOf(AiModelSessionSettings.class, t.createDefaultSettings());
        assertSame(t, AiTypeEnum.fromKey("openai_compatible"));
    }

    @Test
    @Timeout(5)
    void isImplementedLikeTheOtherTypes() {
        assertTrue(AiTypeEnum.OPENAI_COMPATIBLE.isImplemented());
    }

    @Test
    @Timeout(5)
    void noRestartPromptForTheNewType() {
        assertFalse(McpToolRestartFlow.shouldPrompt(AiTypeEnum.OPENAI_COMPATIBLE, true,
                                                    java.util.Set.of(), java.util.EnumSet.allOf(
                                                    kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum.class)));
    }
}
