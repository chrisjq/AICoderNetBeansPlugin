package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class AbstractAiSessionSettingsTest {

    @Test
    void effectiveAllowWebRequests_explicitTrueOverridesGlobal() {
        AbstractAiSessionSettings cfg = new AbstractAiSessionSettings(
                null, null, null, null, null, null, null, true);
        assertEquals(true, cfg.effectiveAllowWebRequests());
    }

    @Test
    void effectiveAllowWebRequests_explicitFalseOverridesGlobal() {
        AbstractAiSessionSettings cfg = new AbstractAiSessionSettings(
                null, null, null, null, null, null, null, false);
        assertEquals(false, cfg.effectiveAllowWebRequests());
    }

    @Test
    void effectiveAllowWebRequests_nullFallsThroughToGlobalDefault() {
        AbstractAiSessionSettings cfg = new AbstractAiSessionSettings(
                null, null, null, null, null, null, null, null);
        assertEquals(PluginSettings.isAllowWebRequests(), cfg.effectiveAllowWebRequests());
    }

    @Test
    void withAutoAccept_preservesAllowWebRequests() {
        AbstractAiSessionSettings cfg = new AbstractAiSessionSettings(
                null, null, null, null, null, null, null, true);
        AbstractAiSessionSettings updated = cfg.withAutoAccept(true);
        assertEquals(true, updated.effectiveAllowWebRequests());
    }
}
