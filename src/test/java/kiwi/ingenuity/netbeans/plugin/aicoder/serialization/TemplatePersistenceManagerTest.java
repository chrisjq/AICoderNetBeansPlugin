package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.SpecialInstructionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatePersistenceManagerTest {

    private static AiSessionSettings populatedSettings() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setMaxHistory(42);
        settings.setSaveHistory(true);
        settings.setRestrictToProjectFiles(true);
        settings.setAllowInterAiComms(true);
        settings.setAutoNotifyInbox(false);
        settings.setAllowImportantMessages(true);
        settings.setAutoAccept(true);
        settings.setAllowWebRequests(true);
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            settings.setAllowWebRequestAccess(option, option.ordinal() % 2 == 0);
        }
        settings.setAllowDatabaseAccess(true);
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            settings.setAllowDatabaseAccessOption(option, option.ordinal() % 2 == 1);
        }
        settings.setDatabaseRowLimit(73);
        settings.setEnableClipboardAccess(true);
        return settings;
    }

    private static void assertGenericSettings(AiSessionSettings expected, AiSessionSettings actual) {
        assertEquals(expected.maxHistory(), actual.maxHistory());
        assertEquals(expected.saveHistory(), actual.saveHistory());
        assertEquals(expected.restrictToProjectFiles(), actual.restrictToProjectFiles());
        assertEquals(expected.allowInterAiComms(), actual.allowInterAiComms());
        assertEquals(expected.autoNotifyInbox(), actual.autoNotifyInbox());
        assertEquals(expected.allowImportantMessages(), actual.allowImportantMessages());
        assertEquals(expected.autoAccept(), actual.autoAccept());
        assertEquals(expected.allowWebRequests(), actual.allowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            assertEquals(expected.allowWebRequestAccess(option), actual.allowWebRequestAccess(option), option.name());
        }
        assertEquals(expected.allowDatabaseAccess(), actual.allowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            assertEquals(expected.allowDatabaseAccessOption(option), actual.allowDatabaseAccessOption(option), option.name());
        }
        assertEquals(expected.databaseRowLimit(), actual.databaseRowLimit());
        assertEquals(expected.enableClipboardAccess(), actual.enableClipboardAccess());
    }

    @TempDir
    Path temp;

    @Test
    void configTemplateRoundTripsEveryGenericFieldAndPreservesModel() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        AiSessionSettings config = populatedSettings();
        ConfigTemplate saved = ConfigTemplate.create("Fast", config);
        manager.save(saved);

        ConfigTemplate loaded = manager.loadConfigTemplates().get(0);
        assertEquals("Fast", loaded.name());
        assertGenericSettings(config, loaded.settings());

        AiModelSessionSettings target = new AiModelSessionSettings();
        target.setModel("type-specific-model");
        target.setSessionInstructions("unchanged");
        loaded.applyTo(target);
        assertGenericSettings(config, target);
        assertEquals("type-specific-model", target.model());
        assertEquals("unchanged", target.sessionInstructions());
    }

    @Test
    void saveUpdatesTemplateWithoutChangingCreatedTimestamp() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-01T00:00:01Z");
        ConfigTemplate original = new ConfigTemplate("id", "Original", populatedSettings(), created, updated);
        manager.save(original);

        ConfigTemplate replacement = original.withNameAndSettings("Updated", populatedSettings());
        manager.save(replacement);
        ConfigTemplate loaded = manager.loadConfigTemplates().get(0);

        assertEquals(1, manager.loadConfigTemplates().size());
        assertEquals("Updated", loaded.name());
        assertEquals(created, loaded.createdAt());
        assertTrue(loaded.updatedAt().isAfter(updated));
    }

    @Test
    void malformedEntriesAreSkippedWithoutDiscardingValidEntries() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        ConfigTemplate valid = ConfigTemplate.create("Valid", populatedSettings());
        JsonArray entries = new JsonArray();
        entries.add(valid.toJson());
        JsonObject malformed = new JsonObject();
        malformed.addProperty("id", "missing-required-fields");
        entries.add(malformed);
        entries.add("not-an-object");
        Files.writeString(temp.resolve("config-templates.json"), entries.toString());

        assertEquals(1, manager.loadConfigTemplates().size());
        assertEquals("Valid", manager.loadConfigTemplates().get(0).name());
    }

    @Test
    void emptyStoreSeedingIsIdempotentAndDoesNotReplaceUserTemplates() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        List<ConfigTemplate> defaults = manager.saveConfigDefaultsIfEmpty();
        assertEquals(List.of("Coordinator", "CoderPeer", "ReviewerPeer"), defaults.stream()
                .map(ConfigTemplate::name).toList());
        assertTrue(defaults.stream().allMatch(template -> template.settings()
                .allowDatabaseAccessOption(DatabaseAccessOptionEnum.READ_ONLY)));
        assertEquals(3L, (long) manager.saveConfigDefaultsIfEmpty().size());
        ConfigTemplate userTemplate = ConfigTemplate.create("User", populatedSettings());
        manager.save(userTemplate);

        assertEquals(4, manager.saveConfigDefaultsIfEmpty().size());
        assertTrue(manager.loadConfigTemplates().stream().anyMatch(t -> t.id().equals(userTemplate.id())));

        var instructionDefaults = manager.saveSpecialInstructionDefaultsIfEmpty();
        assertEquals(List.of("Coordinator", "CoderPeer", "ReviewerPeer"),
                instructionDefaults.stream().map(SpecialInstructionTemplate::name).toList());
        assertEquals(instructionDefaults, manager.saveSpecialInstructionDefaultsIfEmpty());
    }

    /**
     * Every built-in template pins EVERY web-request option explicitly, including the destination options.
     * <p>
     * These templates hard-deny POST, HEADERS and BODY, so their intent is lockdown. An option left null does not mean
     * "off" — it means "inherit the global default", so a user who switches the global "Allow localhost destinations"
     * on would silently grant network access to every session created from a template that reads as locked down.
     * Pinning them keeps the template self-describing: what it shows is what a session gets, whatever the global
     * happens to be.
     */
    @Test
    void builtInTemplatesPinEveryWebOptionIncludingDestinations() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);

        for (ConfigTemplate template : manager.saveConfigDefaultsIfEmpty()) {
            for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
                assertNotNull(template.settings().allowWebRequestAccess(option),
                        template.name() + " leaves " + option
                        + " unpinned, so it inherits the global default instead of the template's own value");
            }
            assertEquals(Boolean.FALSE,
                    template.settings().allowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST),
                    template.name() + " must deny localhost destinations");
            assertEquals(Boolean.FALSE,
                    template.settings().allowWebRequestAccess(WebRequestAccessOptionEnum.PRIVATE_NETWORKS),
                    template.name() + " must deny private-network destinations");
        }
    }

    @Test
    void instructionTemplateCrudAndEmptyDefaultSeedingAreSafe() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        manager.saveSpecialInstructionDefaultsIfEmpty();
        SpecialInstructionTemplate saved = SpecialInstructionTemplate.create("Review", "Review carefully.");
        manager.save(saved);
        assertEquals("Review carefully.", manager.loadSpecialInstructionTemplates().stream()
                .filter(template -> template.id().equals(saved.id())).findFirst().orElseThrow().body());
        manager.deleteSpecialInstructionTemplate(saved.id());
        assertEquals(3, manager.loadSpecialInstructionTemplates().size());
    }

}
