package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ConfigTemplateTest {

    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456789Z");
    private static final Instant UPDATED = Instant.parse("2026-02-03T04:05:06.987654321Z");

    @Test
    void defaultTemplateHasFixedIdAndIsRecognised() {
        ConfigTemplate template = ConfigTemplate.createDefault(new AiSessionSettings());

        assertEquals(ConfigTemplate.DEFAULT_ID, template.id());
        assertTrue(template.isDefault());
        assertFalse(ConfigTemplate.create("Other", new AiSessionSettings()).isDefault());
    }

    @Test
    void templateDoesNotCopyMcpToolConfig() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)));

        ConfigTemplate template = ConfigTemplate.create("test", settings);

        assertNull(template.settings().mcpToolConfig());
    }

    @Test
    void createDefaultIsNamedDefault() {
        ConfigTemplate template = ConfigTemplate.createDefault(new AiSessionSettings());

        assertEquals("Default", template.name());
        assertEquals(ConfigTemplate.DEFAULT_ID, template.id());
    }

    @Test
    void copyValuesCopiesEveryGenericFieldButNotRoutingIdOrToolConfig() {
        AiSessionSettings target = new AiSessionSettings();

        ConfigTemplate.copyValues(fullySetSource(), target);

        assertFullyPopulated(target);
        assertNull(target.mcpRoutingId());
        assertNull(target.mcpToolConfig());
    }

    @Test
    void applyToCopiesTheTemplateValuesIntoTheTarget() {
        ConfigTemplate template = ConfigTemplate.create("apply", fullySetSource());
        AiSessionSettings target = new AiSessionSettings();

        template.applyTo(target);

        assertFullyPopulated(target);
        assertNull(target.mcpToolConfig());
    }

    @Test
    void settingsReturnsADefensiveCopy() {
        ConfigTemplate template = ConfigTemplate.create("copy", fullySetSource());

        template.settings().setMaxHistory(99);

        assertEquals(Integer.valueOf(7), template.settings().maxHistory());
    }

    @Test
    void toJsonThenFromJsonPreservesIdNameTimestampsAndEveryField() {
        ConfigTemplate original = new ConfigTemplate("tpl-1", "Round trip", fullySetSource(), CREATED, UPDATED);
        JsonObject json = original.toJson();

        ConfigTemplate loaded = ConfigTemplate.fromJson(json);

        assertEquals("tpl-1", loaded.id());
        assertEquals("Round trip", loaded.name());
        assertEquals(CREATED, loaded.createdAt());
        assertEquals(UPDATED, loaded.updatedAt());
        assertFullyPopulated(loaded.settings());
        assertNull(loaded.settings().sessionInstructions());
    }

    @Test
    void withNameAndSettingsKeepsIdAndCreatedAtAndAdvancesUpdatedAt() {
        ConfigTemplate original = new ConfigTemplate("tpl-2", "Old", fullySetSource(), CREATED, UPDATED);

        ConfigTemplate renamed = original.withNameAndSettings("New", new AiSessionSettings());

        assertEquals("tpl-2", renamed.id());
        assertEquals("New", renamed.name());
        assertEquals(CREATED, renamed.createdAt());
        assertTrue(renamed.updatedAt().isAfter(UPDATED));
    }

    @Test
    void fromJsonWithMissingTimestampsStillLoadsTheEntry() {
        JsonObject json = new ConfigTemplate("tpl-3", "No timestamps", fullySetSource(), CREATED, UPDATED).toJson();
        json.remove("createdAt");
        json.remove("updatedAt");

        ConfigTemplate loaded = ConfigTemplate.fromJson(json);

        assertEquals("tpl-3", loaded.id());
        assertEquals("No timestamps", loaded.name());
        assertEquals(Instant.EPOCH, loaded.createdAt());
        assertEquals(Instant.EPOCH, loaded.updatedAt());
        assertFullyPopulated(loaded.settings());
    }

    @Test
    void fromJsonWithAnInvalidTimestampStillLoadsTheEntry() {
        JsonObject json = new ConfigTemplate("tpl-4", "Bad timestamp", fullySetSource(), CREATED, UPDATED).toJson();
        json.addProperty("createdAt", "not-an-instant");
        json.addProperty("updatedAt", "also-not-an-instant");

        ConfigTemplate loaded = ConfigTemplate.fromJson(json);

        assertEquals("tpl-4", loaded.id());
        assertEquals("Bad timestamp", loaded.name());
        assertEquals(Instant.EPOCH, loaded.createdAt());
        assertEquals(Instant.EPOCH, loaded.updatedAt());
        assertFullyPopulated(loaded.settings());
    }

    private static AiSessionSettings fullySetSource() {
        AiSessionSettings source = new AiSessionSettings();
        source.setMaxHistory(7);
        source.setSaveHistory(true);
        source.setRestrictToProjectFiles(true);
        source.setAllowInterAiComms(true);
        source.setAutoNotifyInbox(true);
        source.setAllowImportantMessages(true);
        source.setAllowIdleWatcherTimer(true);
        source.setMcpSteering(true);
        source.setAutoAccept(true);
        source.setAllowWebRequests(true);
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            source.setAllowWebRequestAccess(option, true);
        }
        source.setAllowDatabaseAccess(true);
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            source.setAllowDatabaseAccessOption(option, true);
        }
        source.setAllowGitAccess(true);
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            source.setAllowGitAccessOption(option, true);
        }
        source.setDatabaseRowLimit(123);
        source.setEnableClipboardAccess(true);
        source.setMcpRoutingId("routing-id-0001");
        source.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)));
        return source;
    }

    private static void assertFullyPopulated(AiSessionSettings s) {
        assertEquals(Integer.valueOf(7), s.maxHistory());
        assertEquals(Boolean.TRUE, s.saveHistory());
        assertEquals(Boolean.TRUE, s.restrictToProjectFiles());
        assertEquals(Boolean.TRUE, s.allowInterAiComms());
        assertEquals(Boolean.TRUE, s.autoNotifyInbox());
        assertEquals(Boolean.TRUE, s.allowImportantMessages());
        assertEquals(Boolean.TRUE, s.allowIdleWatcherTimer());
        assertEquals(Boolean.TRUE, s.mcpSteering());
        assertEquals(Boolean.TRUE, s.autoAccept());
        assertEquals(Boolean.TRUE, s.allowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            assertEquals(Boolean.TRUE, s.allowWebRequestAccess(option), option.name());
        }
        assertEquals(Boolean.TRUE, s.allowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            assertEquals(Boolean.TRUE, s.allowDatabaseAccessOption(option), option.name());
        }
        assertEquals(Boolean.TRUE, s.allowGitAccess());
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertEquals(Boolean.TRUE, s.allowGitAccessOption(option), option.name());
        }
        assertEquals(Integer.valueOf(123), s.databaseRowLimit());
        assertEquals(Boolean.TRUE, s.enableClipboardAccess());
    }
}
