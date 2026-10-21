package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
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
        settings.setAllowGitAccess(false);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        settings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);
        settings.setEnableClipboardAccess(true);
        settings.setAllowIdleWatcherTimer(false);
        settings.setMcpSteering(false);
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
        assertEquals(expected.allowGitAccess(), actual.allowGitAccess(), "allowGitAccess");
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertEquals(expected.allowGitAccessOption(option),
                         actual.allowGitAccessOption(option), option.name());
        }
        assertEquals(expected.enableClipboardAccess(), actual.enableClipboardAccess());
        assertEquals(expected.allowIdleWatcherTimer(), actual.allowIdleWatcherTimer(), "allowIdleWatcherTimer");
        assertEquals(expected.mcpSteering(), actual.mcpSteering(), "mcpSteering");
    }

    @TempDir
    Path temp;

    @Test
    void configTemplateRoundTripsEveryGenericFieldAndPreservesModel() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        AiSessionSettings config = populatedSettings();
        ConfigTemplate saved = ConfigTemplate.create("Fast", config);
        manager.save(saved);

        ConfigTemplate loaded = manager.loadConfigTemplates().stream()
                .filter(template -> template.id().equals(saved.id()))
                .findFirst().orElseThrow();
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
        ConfigTemplate loaded = manager.loadConfigTemplates().stream()
                .filter(template -> template.id().equals(original.id()))
                .findFirst().orElseThrow();

        assertEquals(2, manager.loadConfigTemplates().size());
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

        assertEquals(2, manager.loadConfigTemplates().size());
        assertEquals("Valid", manager.loadConfigTemplates().stream()
                     .filter(template -> template.id().equals(valid.id()))
                     .findFirst().orElseThrow().name());
    }

    @Test
    void emptyStoreSeedingIsIdempotentAndDoesNotReplaceUserTemplates() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        List<ConfigTemplate> defaults = manager.saveConfigDefaultsIfEmpty();
        assertEquals(List.of("Default", "Coordinator", "CoderPeer", "ReviewerPeer"), defaults.stream()
                     .map(ConfigTemplate::name).toList());
        assertTrue(defaults.stream().filter(template -> !template.isDefault()).allMatch(template -> template.settings()
                .allowDatabaseAccessOption(DatabaseAccessOptionEnum.READ_ONLY)));
        assertEquals(4L, (long) manager.saveConfigDefaultsIfEmpty().size());
        ConfigTemplate userTemplate = ConfigTemplate.create("User", populatedSettings());
        manager.save(userTemplate);

        assertEquals(5, manager.saveConfigDefaultsIfEmpty().size());
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
            if (template.isDefault()) {
                continue;
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
    void builtInTemplatesPinGitAccessWithReviewerReadOnly() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        manager.saveConfigDefaultsIfEmpty();

        Map<String, ConfigTemplate> byName = new HashMap<>();
        for (ConfigTemplate template : manager.loadConfigTemplates()) {
            byName.put(template.name(), template);
        }

        for (ConfigTemplate template : byName.values()) {
            if (template.isDefault()) {
                continue;
            }
            assertEquals(Boolean.TRUE, template.settings().allowGitAccess(), template.name());
            assertEquals(Boolean.TRUE,
                         template.settings().allowGitAccessOption(GitAccessOptionEnum.READ),
                         template.name());
        }

        assertEquals(Boolean.TRUE,
                     byName.get("Coordinator").settings().allowGitAccessOption(GitAccessOptionEnum.WRITE));
        assertEquals(Boolean.TRUE,
                     byName.get("CoderPeer").settings().allowGitAccessOption(GitAccessOptionEnum.WRITE));
        assertEquals(Boolean.FALSE,
                     byName.get("ReviewerPeer").settings().allowGitAccessOption(GitAccessOptionEnum.WRITE),
                     "reviewer templates must not be able to mutate the repository");
    }

    @Test
    void defaultConfigTemplateIsSeededFromEffectiveGlobalDefaults() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        AiSessionSettings expected = new AiSessionSettings();
        expected.applyDefaultSettingsFromGlobal();

        ConfigTemplate template = manager.getDefault();

        assertTrue(template.isDefault());
        assertGenericSettings(expected, template.settings());
    }

    @Test
    void defaultConfigTemplateUsesGlobalOverridesOnlyWhenSeeded() throws Exception {
        int maxHistory = PluginSettings.getMaxHistory();
        boolean web = PluginSettings.isAllowWebRequests();
        boolean clipboard = PluginSettings.isEnableClipboardAccess();
        try {
            PluginSettings.setMaxHistory(987);
            PluginSettings.setAllowWebRequests(false);
            PluginSettings.setEnableClipboardAccess(true);

            ConfigTemplate template = new TemplatePersistenceManager(temp).getDefault();

            assertEquals(987, template.settings().maxHistory());
            assertEquals(Boolean.FALSE, template.settings().allowWebRequests());
            assertEquals(Boolean.TRUE, template.settings().enableClipboardAccess());
        }
        finally {
            PluginSettings.setMaxHistory(maxHistory);
            PluginSettings.setAllowWebRequests(web);
            PluginSettings.setEnableClipboardAccess(clipboard);
        }
    }

    @Test
    void editedDefaultSurvivesReloadAndCannotBeDeleted() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        ConfigTemplate defaultTemplate = manager.getDefault();
        ConfigTemplate edited = defaultTemplate.withNameAndSettings("My defaults", populatedSettings());
        manager.save(edited);

        assertFalse(manager.deleteConfigTemplate(ConfigTemplate.DEFAULT_ID));
        ConfigTemplate reloaded = manager.getDefault();

        assertEquals(ConfigTemplate.DEFAULT_ID, reloaded.id());
        assertEquals("My defaults", reloaded.name());
        assertGenericSettings(populatedSettings(), reloaded.settings());
    }

    @Test
    void missingDefaultIsReseededEvenAfterInitialSeed() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        manager.getDefault();
        ConfigTemplate custom = ConfigTemplate.create("Custom", populatedSettings());
        JsonArray stored = new JsonArray();
        stored.add(custom.toJson());
        Files.writeString(temp.resolve("config-templates.json"), stored.toString());

        List<ConfigTemplate> reloaded = manager.loadConfigTemplates();

        assertTrue(reloaded.stream().anyMatch(ConfigTemplate::isDefault));
        assertTrue(reloaded.stream().anyMatch(template -> template.id().equals(custom.id())));
    }

    @Test
    void defaultTemplateKeepsTheGlobalLocalhostAndGitValuesItWasSeededWith() throws Exception {
        AiSessionSettings effective = new AiSessionSettings();
        ConfigTemplate template = new TemplatePersistenceManager(temp).getDefault();

        assertEquals(Boolean.valueOf(effective.effectiveAllowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST)),
                     template.settings().allowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST));
        assertEquals(Boolean.valueOf(effective.effectiveAllowGitAccess()), template.settings().allowGitAccess());
    }

    @Test
    void getDefaultIsStableAcrossCalls() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);

        ConfigTemplate first = manager.getDefault();
        ConfigTemplate second = manager.getDefault();

        assertEquals(first.id(), second.id());
        assertEquals(first.name(), second.name());
        assertEquals(1, manager.loadConfigTemplates().stream().filter(ConfigTemplate::isDefault).count());
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

    @Test
    void malformedConfigEntriesArePreservedBeforeNextSave() throws Exception {
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);
        ConfigTemplate valid = ConfigTemplate.create("Valid", populatedSettings());
        JsonArray entries = new JsonArray();
        entries.add(valid.toJson());
        JsonObject malformed = new JsonObject();
        malformed.addProperty("id", "malformed");
        entries.add(malformed);
        Files.writeString(temp.resolve("config-templates.json"), entries.toString());

        assertEquals(2, manager.loadConfigTemplates().size());
        manager.save(valid.withNameAndSettings("Updated", populatedSettings()));

        assertTrue(Files.list(temp)
                .anyMatch(path -> path.getFileName().toString().startsWith("config-templates.json.dropped-")));
    }

    @Test
    void corruptConfigStoreIsQuarantinedAndReseededWithDefaultAndShippedTemplates() throws Exception {
        String corrupt = "{ this is not valid json ]";
        Path store = temp.resolve("config-templates.json");
        Files.writeString(store, corrupt);

        List<ConfigTemplate> loaded = new TemplatePersistenceManager(temp).loadConfigTemplates();

        assertTrue(loaded.stream().anyMatch(ConfigTemplate::isDefault), "the Default must be re-seeded");
        assertEquals(List.of("Coordinator", "CoderPeer", "ReviewerPeer"),
                     loaded.stream().filter(t -> !t.isDefault()).map(ConfigTemplate::name).toList(),
                     "the shipped templates must be re-seeded after a quarantine");

        List<Path> names;
        try (var entries = Files.list(temp)) {
            names = entries.toList();
        }
        Path quarantined = names.stream().filter(path -> path.getFileName().toString()
                .startsWith("config-templates.json.corrupt-"))
                .findFirst().orElse(null);
        assertFalse(names.stream().anyMatch(path -> path.getFileName().toString().endsWith(".seeded")),
                    "no marker file may be written; seeding is decided by the Default entry");
        assertNotNull(quarantined, "the corrupt bytes must be preserved under a .corrupt- sidecar name");
        assertEquals(corrupt, Files.readString(quarantined), "the sidecar holds the original bytes verbatim");
        assertTrue(Files.exists(store), "a fresh seeded store is written in place");
    }

    @Test
    void unreadableConfigStoreServesInMemoryDefaultAndSaveDeleteThrowNamingThePath() throws Exception {
        Path store = temp.resolve("config-templates.json");
        Files.createDirectory(store);
        TemplatePersistenceManager manager = new TemplatePersistenceManager(temp);

        List<ConfigTemplate> loaded = manager.loadConfigTemplates();
        assertEquals(1, loaded.size(), "an unreadable store still yields the single in-memory Default");
        assertTrue(loaded.get(0).isDefault());

        IOException saveError = assertThrows(IOException.class,
                                             () -> manager.save(ConfigTemplate.create("New", populatedSettings())));
        assertTrue(saveError.getMessage().contains(store.toString()),
                   "the save failure must name the store path: " + saveError.getMessage());
        IOException deleteError = assertThrows(IOException.class, () -> manager.deleteConfigTemplate("any-id"));
        assertTrue(deleteError.getMessage().contains(store.toString()),
                   "the delete failure must name the store path: " + deleteError.getMessage());

        assertTrue(Files.isDirectory(store), "the unreadable store must be left untouched");
        List<Path> names;
        try (var entries = Files.list(temp)) {
            names = entries.toList();
        }
        assertFalse(names.stream().anyMatch(path -> path.getFileName().toString().endsWith(".seeded")),
                    "no marker file may be written");
        assertFalse(names.stream().anyMatch(path -> path.getFileName().toString().contains(".corrupt-")),
                    "a read failure is not corruption, so no .corrupt- sidecar may appear");
    }

}
