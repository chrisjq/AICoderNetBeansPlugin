package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AiSessionConfigTest {

    @Test
    void defaultsHasAllNullFields() {
        AiSessionSettings cfg = new AiSessionSettings();
        assertNull(cfg.maxHistory());
        assertNull(cfg.restrictToProjectFiles());
        assertNull(cfg.allowInterAiComms());
        assertNull(cfg.autoNotifyInbox());
        assertNull(cfg.allowImportantMessages());
        assertNull(cfg.sessionInstructions());
    }

    @Test
    void explicitValueOverridesDefault() {
        AiSessionSettings cfg = new AiSessionSettings(null, false, true, null, null, null, null, null);
        assertFalse(cfg.restrictToProjectFiles());
        assertTrue(cfg.allowInterAiComms());
    }

    @Test
    void sessionInstructionsStoredAndReturned() {
        AiSessionSettings cfg = new AiSessionSettings(null, null, null, null, null, "my instructions", null, null);
        assertEquals("my instructions", cfg.sessionInstructions());
    }

    @Test
    void modelConfigGetModelFragmentIncludesModel() {
        AiModelSessionSettings cfg = new AiModelSessionSettings(
                null, null, null, null, null, null, "claude-opus-4-5", null, null);
        assertEquals(", model: claude-opus-4-5", cfg.getAdditionalInfo());
    }

    @Test
    void modelConfigModelIsNullWhenNotSet() {
        AiModelSessionSettings cfg = new AiModelSessionSettings(
                null, null, null, null, null, null, null, null, null);
        assertNull(cfg.model());
    }
}
