package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class GrokSessionSettingsTest {

    @Test
    void populateJsonObjectOmitsReasoningEffortWhenUnset() {
        GrokSessionSettings settings = new GrokSessionSettings();
        JsonObject cfg = new JsonObject();

        settings.populateJsonObject(cfg);

        assertFalse(cfg.has(GrokSessionSettingsKeyEnum.REASONING_EFFORT.key()),
                    "unset reasoning effort must not appear in the persisted config at all");
    }

    @Test
    void populateJsonObjectIncludesReasoningEffortWhenSet() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setReasoningEffort("high");
        JsonObject cfg = new JsonObject();

        settings.populateJsonObject(cfg);

        assertEquals("high", cfg.get(GrokSessionSettingsKeyEnum.REASONING_EFFORT.key()).getAsString());
    }
}
