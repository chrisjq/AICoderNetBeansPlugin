package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AiSessionConfigTest {

    @Test
    void defaultsHasAllNullFields() {
        AbstractAiSessionSettings cfg = AbstractAiSessionSettings.defaults();
        assertNull(cfg.maxHistory());
        assertNull(cfg.restrictToProjectFiles());
        assertNull(cfg.allowInterAiComms());
        assertNull(cfg.autoNotifyInbox());
        assertNull(cfg.allowImportantMessages());
        assertNull(cfg.sessionInstructions());
    }

    @Test
    void explicitValueOverridesDefault() {
        AbstractAiSessionSettings cfg = new AbstractAiSessionSettings(null, false, true, null, null, null);
        assertFalse(cfg.restrictToProjectFiles());
        assertTrue(cfg.allowInterAiComms());
    }

    @Test
    void sessionInstructionsStoredAndReturned() {
        AbstractAiSessionSettings cfg = new AbstractAiSessionSettings(null, null, null, null, null, "my instructions");
        assertEquals("my instructions", cfg.sessionInstructions());
    }

    @Test
    void modelConfigGetModelFragmentIncludesModel() {
        AbstractAiModelSessionSettings cfg = new AbstractAiModelSessionSettings(
                null, null, null, null, null, null, "claude-opus-4-5", "default-model");
        assertEquals(", model: claude-opus-4-5", cfg.getAdditionalInfo());
    }

    @Test
    void modelConfigEffectiveModelFallsBackToDefault() {
        AbstractAiModelSessionSettings cfg = new AbstractAiModelSessionSettings(
                null, null, null, null, null, null, null, "fallback-model");
        assertEquals("fallback-model", cfg.model());
        assertEquals(", model: fallback-model", cfg.getAdditionalInfo());
    }
}
