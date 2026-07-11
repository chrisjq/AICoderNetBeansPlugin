package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSettingsCreator;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AiModelSessionSettingsCreatorTest {

    @Test
    void updateSetsModelFromJson() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("model", "claude-opus-4-8");

        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings settings = creator.create();
        creator.update(settings, cfg);

        assertEquals("claude-opus-4-8", settings.model());
    }

    @Test
    void updateLeavesModelNullWhenAbsent() {
        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings settings = creator.create();
        creator.update(settings, new JsonObject());

        assertNull(settings.model());
    }

    @Test
    void updateIgnoresNonPrimitiveModel() {
        JsonObject cfg = new JsonObject();
        cfg.add("model", new JsonObject());

        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings settings = creator.create();
        creator.update(settings, cfg);

        assertNull(settings.model());
    }

    @Test
    void updateSetsBaseFieldsAlongsideModel() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("model", "claude-sonnet-5");
        cfg.addProperty("maxHistory", 9);
        cfg.addProperty("allowWebRequests", true);

        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings settings = creator.create();
        creator.update(settings, cfg);

        assertEquals("claude-sonnet-5", settings.model());
        assertEquals(9, settings.maxHistory());
        assertEquals(true, settings.allowWebRequests());
    }

    @Test
    void modelRoundTripsThroughPopulateJsonObject() {
        AiModelSessionSettings original = new AiModelSessionSettings(
                null, null, null, null, null, null, "claude-fable-5", null, null);
        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);

        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings loaded = creator.create();
        creator.update(loaded, cfg);

        assertEquals("claude-fable-5", loaded.model());
    }
}
