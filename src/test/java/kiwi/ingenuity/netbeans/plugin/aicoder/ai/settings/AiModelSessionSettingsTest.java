package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiModelSessionSettingsTest {

    @Test
    void nineArgConstructor_setsAllowWebRequests() {
        AiModelSessionSettings cfg = new AiModelSessionSettings(
                null, null, null, null, null, null, "gpt-5", true, false);
        assertEquals(false, cfg.effectiveAllowWebRequests());
        assertEquals("gpt-5", cfg.model());
    }

    @Test
    void setAutoAccept_preservesAllowWebRequests() {
        AiModelSessionSettings cfg = new AiModelSessionSettings(
                null, null, null, null, null, null, "gpt-5", null, true);
        cfg.setAutoAccept(false);
        assertEquals(true, cfg.effectiveAllowWebRequests());
        assertEquals("gpt-5", cfg.model());
    }
}
