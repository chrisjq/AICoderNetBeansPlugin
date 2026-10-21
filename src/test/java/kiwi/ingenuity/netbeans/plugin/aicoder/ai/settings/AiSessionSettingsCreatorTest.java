package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
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

            @Override
            public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
                // No-op: this test exercises update()/JSON parsing, not global defaults.
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
        cfg.addProperty("allowGitAccess", false);
        cfg.addProperty("allowGitRead", true);
        cfg.addProperty("allowGitWrite", false);

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
        assertEquals(false, settings.allowGitAccess());
        assertEquals(true,
                     settings.allowGitAccessOption(GitAccessOptionEnum.READ));
        assertEquals(false,
                     settings.allowGitAccessOption(GitAccessOptionEnum.WRITE));
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
        assertNull(settings.allowGitAccess());
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertNull(settings.allowGitAccessOption(option));
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
        original.setAllowGitAccess(false);
        original.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        original.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);
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
        assertEquals(original.allowGitAccess(), loaded.allowGitAccess());
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertEquals(original.allowGitAccessOption(option),
                         loaded.allowGitAccessOption(option));
        }
    }

    @Test
    void populateJsonObjectOmitsNullFields() {
        JsonObject cfg = new JsonObject();
        new AiSessionSettings().populateJsonObject(cfg);
        assertTrue(cfg.entrySet().isEmpty());
    }

    @Test
    void mcpToolConfigRoundTripsAndIsOmittedWhenNull() {
        AiSessionSettings original = new AiSessionSettings();
        original.setMcpToolConfig(kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig
                .withDisabled(java.util.Set.of(
                        kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum.WEB)));

        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);

        AiSessionSettings loaded = baseCreator().create();
        baseCreator().update(loaded, cfg);

        assertEquals(original.mcpToolConfig(), loaded.mcpToolConfig());
        assertTrue(cfg.has("mcpToolConfig"));
    }

    @Test
    void malformedMcpToolConfigLeavesTheFieldNullSoLegacyTogglesStillApply() {
        JsonObject withoutGroups = new JsonObject();
        withoutGroups.add("mcpToolConfig", new JsonObject());
        AiSessionSettings fromObject = baseCreator().create();
        baseCreator().update(fromObject, withoutGroups);
        assertNull(fromObject.mcpToolConfig());

        JsonObject notAnObject = new JsonObject();
        notAnObject.addProperty("mcpToolConfig", "nonsense");
        AiSessionSettings fromPrimitive = baseCreator().create();
        baseCreator().update(fromPrimitive, notAnObject);
        assertNull(fromPrimitive.mcpToolConfig());
    }

    @Test
    void mcpRoutingIdIsAbsentUntilEnsuredThenStable() {
        AiSessionSettings settings = new AiSessionSettings();
        assertNull(settings.mcpRoutingId());

        String first = settings.ensureMcpRoutingId();

        assertNotNull(first);
        assertFalse(first.isBlank());
        assertEquals(first, settings.mcpRoutingId());
        assertEquals(first, settings.ensureMcpRoutingId(), "once generated it never changes");
        assertNotEquals(first, new AiSessionSettings().ensureMcpRoutingId(), "each session gets its own id");
    }

    @Test
    void malformedPersistedMcpRoutingIdIsIgnoredAndRegenerated() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("mcpRoutingId", "bad/id%value");
        AiSessionSettings loaded = baseCreator().create();
        baseCreator().update(loaded, cfg);

        assertNull(loaded.mcpRoutingId());
        assertTrue(loaded.ensureMcpRoutingId().matches("[A-Za-z0-9-]{8,64}"));
    }

    @Test
    void setterRejectsMalformedMcpRoutingIdButAllowsClearing() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpRoutingId("route-1234");
        settings.setMcpRoutingId("bad/id%value");
        assertEquals("route-1234", settings.mcpRoutingId());
        settings.setMcpRoutingId(null);
        assertNull(settings.mcpRoutingId());
    }

    @Test
    void mcpRoutingIdSurvivesAJsonRoundTrip() {
        AiSessionSettings original = new AiSessionSettings();
        String id = original.ensureMcpRoutingId();

        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);
        AiSessionSettings loaded = baseCreator().create();
        baseCreator().update(loaded, cfg);

        assertEquals("mcpRoutingId", AiSessionSettingsKeyEnum.MCP_ROUTING_ID.key());
        assertEquals(id, cfg.get("mcpRoutingId").getAsString());
        assertEquals(id, loaded.mcpRoutingId());
        assertEquals(id, loaded.ensureMcpRoutingId(), "a restored session keeps its address");
    }

    @Test
    void mcpRoutingIdIsOmittedFromJsonUntilGenerated() {
        JsonObject cfg = new JsonObject();
        new AiSessionSettings().populateJsonObject(cfg);
        assertFalse(cfg.has("mcpRoutingId"));
    }

    @Test
    void aBlankPersistedRoutingIdIsIgnoredAndRegeneratedOnDemand() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("mcpRoutingId", " ");
        AiSessionSettings loaded = baseCreator().create();
        baseCreator().update(loaded, cfg);

        assertNull(loaded.mcpRoutingId());
        assertFalse(loaded.ensureMcpRoutingId().isBlank());
    }

    @Test
    void configTemplatesDoNotCopyTheRoutingId() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.ensureMcpRoutingId();

        ConfigTemplate template = ConfigTemplate.create("t", settings);

        assertNull(template.settings().mcpRoutingId());
    }
}
