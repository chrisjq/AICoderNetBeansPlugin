package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AiSessionSettingsCreatorTest {

    // Key literals intentionally duplicated from AiSessionSettingsKeyEnum:
    // they document the on-disk format, so an accidental enum change fails here.
    private static AiSessionSettingsCreator<AiSessionSettings> baseCreator() {
        return new AiSessionSettingsCreator<>() {
            @Override
            public AiSessionSettings create() {
                return new AiSessionSettings();
            }
        };
    }

    @Test
    void updatePopulatesAllBaseFields() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("maxHistory", 42);
        cfg.addProperty("restrictToProjectFiles", true);
        cfg.addProperty("allowInterAiComms", false);
        cfg.addProperty("autoNotifyInbox", true);
        cfg.addProperty("allowImportantMessages", false);
        cfg.addProperty("sessionInstructions", "be brief");
        cfg.addProperty("autoAccept", true);
        cfg.addProperty("allowWebRequests", false);
        cfg.addProperty("allowWebRequestGet", true);
        cfg.addProperty("allowWebRequestPost", false);
        cfg.addProperty("allowWebRequestHeaders", false);
        cfg.addProperty("allowWebRequestBody", true);

        AiSessionSettingsCreator<AiSessionSettings> creator = baseCreator();
        AiSessionSettings settings = creator.create();
        creator.update(settings, cfg);

        assertEquals(42, settings.maxHistory());
        assertEquals(true, settings.restrictToProjectFiles());
        assertEquals(false, settings.allowInterAiComms());
        assertEquals(true, settings.autoNotifyInbox());
        assertEquals(false, settings.allowImportantMessages());
        assertEquals("be brief", settings.sessionInstructions());
        assertEquals(true, settings.autoAccept());
        assertEquals(false, settings.allowWebRequests());
        assertEquals(true,
                settings.allowWebRequestAccess(WebRequestAccessOptionEnum.GET));
        assertEquals(false,
                settings.allowWebRequestAccess(WebRequestAccessOptionEnum.POST));
        assertEquals(false,
                settings.allowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS));
        assertEquals(true,
                settings.allowWebRequestAccess(WebRequestAccessOptionEnum.BODY));
    }

    @Test
    void updateLeavesAbsentKeysNull() {
        AiSessionSettingsCreator<AiSessionSettings> creator = baseCreator();
        AiSessionSettings settings = creator.create();
        creator.update(settings, new JsonObject());

        assertNull(settings.maxHistory());
        assertNull(settings.restrictToProjectFiles());
        assertNull(settings.allowInterAiComms());
        assertNull(settings.autoNotifyInbox());
        assertNull(settings.allowImportantMessages());
        assertNull(settings.sessionInstructions());
        assertNull(settings.autoAccept());
        assertNull(settings.allowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            assertNull(settings.allowWebRequestAccess(option));
        }
    }

    @Test
    void updateIgnoresNonPrimitiveValues() {
        JsonObject cfg = new JsonObject();
        cfg.add("maxHistory", new JsonObject());
        cfg.add("sessionInstructions", new JsonObject());
        cfg.add("autoAccept", new JsonObject());
        cfg.add("allowWebRequestHeaders", new JsonObject());

        AiSessionSettingsCreator<AiSessionSettings> creator = baseCreator();
        AiSessionSettings settings = creator.create();
        creator.update(settings, cfg);

        assertNull(settings.maxHistory());
        assertNull(settings.sessionInstructions());
        assertNull(settings.autoAccept());
        assertNull(settings.allowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS));
    }

    @Test
    void populateJsonObjectThenUpdateRoundTrips() {
        AiSessionSettings original = new AiSessionSettings(7, true, false, true,
                false, "round trip", true, false);
        original.setAllowWebRequestAccess(WebRequestAccessOptionEnum.GET, true);
        original.setAllowWebRequestAccess(WebRequestAccessOptionEnum.POST, false);
        original.setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS, false);
        original.setAllowWebRequestAccess(WebRequestAccessOptionEnum.BODY, true);
        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);

        AiSessionSettingsCreator<AiSessionSettings> creator = baseCreator();
        AiSessionSettings loaded = creator.create();
        creator.update(loaded, cfg);

        assertEquals(original.maxHistory(), loaded.maxHistory());
        assertEquals(original.restrictToProjectFiles(), loaded.restrictToProjectFiles());
        assertEquals(original.allowInterAiComms(), loaded.allowInterAiComms());
        assertEquals(original.autoNotifyInbox(), loaded.autoNotifyInbox());
        assertEquals(original.allowImportantMessages(), loaded.allowImportantMessages());
        assertEquals(original.sessionInstructions(), loaded.sessionInstructions());
        assertEquals(original.autoAccept(), loaded.autoAccept());
        assertEquals(original.allowWebRequests(), loaded.allowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            assertEquals(original.allowWebRequestAccess(option),
                    loaded.allowWebRequestAccess(option));
        }
    }

    @Test
    void populateJsonObjectOmitsNullFields() {
        JsonObject cfg = new JsonObject();
        new AiSessionSettings().populateJsonObject(cfg);
        assertTrue(cfg.entrySet().isEmpty());
    }
}
