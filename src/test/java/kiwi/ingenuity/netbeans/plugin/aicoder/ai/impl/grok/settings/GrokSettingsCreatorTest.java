package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class GrokSettingsCreatorTest {

    @Test
    void updateReadsReasoningEffortFromJson() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("model", "grok-4.6");
        cfg.addProperty(GrokSessionSettingsKeyEnum.REASONING_EFFORT.key(), "xhigh");

        GrokSettingsCreator creator = new GrokSettingsCreator();
        GrokSessionSettings settings = creator.create();
        creator.update(settings, cfg);

        assertEquals("grok-4.6", settings.model());
        assertEquals("xhigh", settings.reasoningEffort());
    }

    @Test
    void updateLeavesReasoningEffortNullWhenAbsent() {
        GrokSettingsCreator creator = new GrokSettingsCreator();
        GrokSessionSettings settings = creator.create();
        creator.update(settings, new JsonObject());

        assertNull(settings.reasoningEffort());
    }
}
