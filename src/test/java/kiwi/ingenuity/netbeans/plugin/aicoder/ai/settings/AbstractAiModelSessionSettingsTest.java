package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AbstractAiModelSessionSettingsTest {

    @Test
    void nineArgConstructor_setsAllowWebRequests() {
        AbstractAiModelSessionSettings cfg = new AbstractAiModelSessionSettings(
                null, null, null, null, null, null, "gpt-5", true, false);
        assertEquals(false, cfg.effectiveAllowWebRequests());
        assertEquals("gpt-5", cfg.model());
    }

    @Test
    void withAutoAccept_preservesAllowWebRequests() {
        AbstractAiModelSessionSettings cfg = new AbstractAiModelSessionSettings(
                null, null, null, null, null, null, "gpt-5", null, true);
        AbstractAiSessionSettings updated = cfg.withAutoAccept(false);
        assertTrue(updated instanceof AbstractAiModelSessionSettings);
        assertEquals(true, updated.effectiveAllowWebRequests());
        assertEquals("gpt-5", ((AbstractAiModelSessionSettings) updated).model());
    }
}
