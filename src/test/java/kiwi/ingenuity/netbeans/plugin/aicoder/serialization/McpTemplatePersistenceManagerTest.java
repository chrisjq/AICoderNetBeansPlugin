package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.LegacyToolGroups;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpTemplatePersistenceManagerTest {

    @TempDir
    Path temp;

    @Test
    void firstLoadSeedsDefaultFromLegacyEffectiveDefaults() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);

        List<McpTemplate> loaded = manager.loadMcpTemplates();

        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).isDefault());
        assertEquals(LegacyToolGroups.derive(null), loaded.get(0).config());
    }

    @Test
    void seedingRunsOnceSoAnEditedDefaultSurvivesReload() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        manager.loadMcpTemplates();

        McpTemplate defaultTemplate = manager.getDefault();
        McpToolConfig edited = McpToolConfig.withDisabled(EnumSet.of(McpToolGroupEnum.GIT_WRITE));
        manager.save(defaultTemplate.withNameAndConfig("Renamed", edited));

        List<McpTemplate> reloaded = manager.loadMcpTemplates();

        assertEquals(1, reloaded.size());
        assertTrue(reloaded.get(0).isDefault());
        assertEquals("Renamed", reloaded.get(0).name());
        assertEquals(edited, reloaded.get(0).config());
        assertTrue(reloaded.get(0).config().isDisabled(McpToolGroupEnum.GIT_WRITE));
    }

    @Test
    void defaultCannotBeDeletedOtherTemplatesCan() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        manager.loadMcpTemplates();
        McpTemplate other = McpTemplate.create("Other", McpToolConfig.ALL_ENABLED);
        manager.save(other);

        assertFalse(manager.deleteMcpTemplate(McpTemplate.DEFAULT_ID));
        assertTrue(manager.loadMcpTemplates().stream().anyMatch(McpTemplate::isDefault));

        assertTrue(manager.deleteMcpTemplate(other.id()));
        List<McpTemplate> afterDelete = manager.loadMcpTemplates();
        assertTrue(afterDelete.stream().noneMatch(t -> t.id().equals(other.id())));
        assertTrue(afterDelete.stream().anyMatch(McpTemplate::isDefault));
    }

    @Test
    void saveThenLoadRoundTripsSeveralTemplates() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        manager.loadMcpTemplates();
        McpToolConfig configA = McpToolConfig.withDisabled(EnumSet.of(McpToolGroupEnum.DATABASE));
        McpToolConfig configB = McpToolConfig.withDisabled(
                EnumSet.of(McpToolGroupEnum.WEB, McpToolGroupEnum.GIT_WRITE, McpToolGroupEnum.CLIPBOARD));
        McpTemplate a = McpTemplate.create("A", configA);
        McpTemplate b = McpTemplate.create("B", configB);
        manager.save(a);
        manager.save(b);

        List<McpTemplate> loaded = manager.loadMcpTemplates();

        assertEquals(3, loaded.size());
        Set<String> ids = new HashSet<>(loaded.stream().map(McpTemplate::id).toList());
        assertEquals(Set.of(McpTemplate.DEFAULT_ID, a.id(), b.id()), ids);
        assertEquals(configA, loaded.stream().filter(t -> t.id().equals(a.id())).findFirst().orElseThrow().config());
        assertEquals(configB, loaded.stream().filter(t -> t.id().equals(b.id())).findFirst().orElseThrow().config());
    }

    @Test
    void corruptStoreLoadsAsFreshSeededDefaultWithoutThrowing() throws Exception {
        Files.writeString(temp.resolve("mcp-templates.json"), "{ this is not valid json ]");

        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);

        List<McpTemplate> loaded = assertDoesNotThrow(manager::loadMcpTemplates);
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).isDefault());
        assertEquals(LegacyToolGroups.derive(null), loaded.get(0).config());
    }

    @Test
    void corruptStoreIsMovedAsideRatherThanOverwritten() throws Exception {
        Files.writeString(temp.resolve("mcp-templates.json"), "{ this is not valid json ]");

        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        manager.loadMcpTemplates();

        try (var entries = Files.list(temp)) {
            assertTrue(entries.anyMatch(p -> p.getFileName().toString().startsWith("mcp-templates.json.corrupt-")),
                       "the corrupt bytes must be preserved under a .corrupt- sidecar name");
        }
        assertTrue(Files.exists(temp.resolve("mcp-templates.json")), "a fresh seeded store is written");
    }

    @Test
    void unreadableStoreServesAnInMemoryDefaultAndLeavesTheFileAlone() throws Exception {
        Path store = temp.resolve("mcp-templates.json");
        Files.createDirectory(store);

        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        List<McpTemplate> loaded = assertDoesNotThrow(manager::loadMcpTemplates);

        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).isDefault());
        assertTrue(Files.isDirectory(store), "a store that merely cannot be read must not be modified");
    }

    @Test
    void missingDefaultIsReseededAndPersisted() throws Exception {
        McpTemplate other = McpTemplate.create("Other", McpToolConfig.ALL_ENABLED);
        JsonArray stored = new JsonArray();
        stored.add(other.toJson());
        Files.writeString(temp.resolve("mcp-templates.json"), stored.toString());

        List<McpTemplate> loaded = new McpTemplatePersistenceManager(temp).loadMcpTemplates();

        assertTrue(loaded.stream().anyMatch(McpTemplate::isDefault));
        assertTrue(loaded.stream().anyMatch(t -> t.id().equals(other.id())));
        assertTrue(new McpTemplatePersistenceManager(temp).loadMcpTemplates().stream()
                .anyMatch(McpTemplate::isDefault), "the re-seeded Default must be persisted");
    }

    @Test
    void getDefaultIsStableAcrossCalls() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);

        McpTemplate first = manager.getDefault();
        McpTemplate second = manager.getDefault();

        assertEquals(first, second);
        assertEquals(1, manager.loadMcpTemplates().stream().filter(McpTemplate::isDefault).count());
    }

    @Test
    void deleteReturnsFalseForAnUnknownId() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        manager.loadMcpTemplates();

        assertFalse(manager.deleteMcpTemplate("no-such-id"));
    }

    @Test
    void firstLoadSeedsDefaultFromEditedGlobalOverrides() throws Exception {
        boolean database = PluginSettings.isAllowDatabaseAccess();
        boolean git = PluginSettings.isAllowGitAccess();
        boolean gitRead = PluginSettings.isAllowGitAccessOption(GitAccessOptionEnum.READ);
        boolean gitWrite = PluginSettings.isAllowGitAccessOption(GitAccessOptionEnum.WRITE);
        boolean web = PluginSettings.isAllowWebRequests();
        boolean clipboard = PluginSettings.isEnableClipboardAccess();
        try {
            PluginSettings.setAllowDatabaseAccess(true);
            PluginSettings.setAllowGitAccess(true);
            PluginSettings.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
            PluginSettings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);
            PluginSettings.setAllowWebRequests(true);
            PluginSettings.setEnableClipboardAccess(true);

            McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
            McpToolConfig seeded = manager.loadMcpTemplates().get(0).config();

            assertFalse(seeded.isDisabled(McpToolGroupEnum.DATABASE));
            assertFalse(seeded.isDisabled(McpToolGroupEnum.GIT_READ));
            assertTrue(seeded.isDisabled(McpToolGroupEnum.GIT_WRITE));
            assertFalse(seeded.isDisabled(McpToolGroupEnum.WEB));
            assertFalse(seeded.isDisabled(McpToolGroupEnum.CLIPBOARD));
        }
        finally {
            PluginSettings.setAllowDatabaseAccess(database);
            PluginSettings.setAllowGitAccess(git);
            PluginSettings.setAllowGitAccessOption(GitAccessOptionEnum.READ, gitRead);
            PluginSettings.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, gitWrite);
            PluginSettings.setAllowWebRequests(web);
            PluginSettings.setEnableClipboardAccess(clipboard);
        }
    }

    @Test
    void malformedConfigEntriesAreSkippedAndMissingDefaultIsReseeded() throws Exception {
        McpTemplate malformedDefault = McpTemplate.createDefault(McpToolConfig.ALL_ENABLED);
        McpTemplate malformedOther = McpTemplate.create("Malformed", McpToolConfig.ALL_ENABLED);
        JsonArray stored = new JsonArray();
        var missingConfig = malformedDefault.toJson();
        missingConfig.remove("config");
        stored.add(missingConfig);
        var missingGroups = malformedOther.toJson();
        missingGroups.add("config", new JsonObject());
        stored.add(missingGroups);
        Files.writeString(temp.resolve("mcp-templates.json"), stored.toString());

        List<McpTemplate> loaded = new McpTemplatePersistenceManager(temp).loadMcpTemplates();

        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).isDefault());
        assertEquals(LegacyToolGroups.derive(null), loaded.get(0).config());
    }

    @Test
    void duplicateIdsKeepTheFirstEntryIncludingDefault() throws Exception {
        McpTemplate firstDefault = McpTemplate.createDefault(
                McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)));
        McpTemplate duplicateDefault = McpTemplate.createDefault(McpToolConfig.ALL_ENABLED);
        JsonArray stored = new JsonArray();
        stored.add(firstDefault.toJson());
        stored.add(duplicateDefault.toJson());
        Files.writeString(temp.resolve("mcp-templates.json"), stored.toString());

        List<McpTemplate> loaded = new McpTemplatePersistenceManager(temp).loadMcpTemplates();

        assertEquals(1, loaded.size());
        assertEquals(firstDefault.config(), loaded.get(0).config());
        assertEquals(1, loaded.stream().filter(McpTemplate::isDefault).count());
    }

    @Test
    void getDefaultReturnsTheSeededTemplateEvenOnAnEmptyStore() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);

        McpTemplate defaultTemplate = assertDoesNotThrow(manager::getDefault);

        assertTrue(defaultTemplate.isDefault());
        assertEquals(LegacyToolGroups.derive(null), defaultTemplate.config());
    }

    @Test
    void unreadableStoreRefusesSaveAndDeleteSoNothingIsOverwritten() throws Exception {
        Path store = temp.resolve("mcp-templates.json");
        Files.createDirectory(store);
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);

        assertThrows(IOException.class,
                     () -> manager.save(McpTemplate.create("New", McpToolConfig.ALL_ENABLED)),
                     "a save must not replace a store this process could not read");
        assertThrows(IOException.class, () -> manager.deleteMcpTemplate("any-id"),
                     "a delete must not replace a store this process could not read");

        assertTrue(Files.isDirectory(store), "the unreadable store must still be there");
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith("mcp-templates.json.corrupt-")),
                        "a read failure is not corruption, so no .corrupt- sidecar may appear");
        }
    }

    @Test
    void entriesSkippedWhileLoadingArePreservedInADroppedSidecarBeforeTheNextPersist() throws Exception {
        JsonObject malformed = new JsonObject();
        malformed.addProperty("id", "dropped-template");
        malformed.addProperty("name", "Dropped");
        JsonArray stored = new JsonArray();
        stored.add(McpTemplate.createDefault(McpToolConfig.ALL_ENABLED).toJson());
        stored.add(malformed);
        Path store = temp.resolve("mcp-templates.json");
        Files.writeString(store, stored.toString());
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);

        manager.save(McpTemplate.create("Fresh", McpToolConfig.ALL_ENABLED));

        Path dropped = findDroppedSidecar();
        JsonArray preserved = JsonParser.parseString(Files.readString(dropped)).getAsJsonArray();
        assertEquals(1, preserved.size(), "the skipped entry must be preserved verbatim");
        assertEquals("dropped-template", preserved.get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(Files.readString(store).contains("dropped-template"),
                    "the skipped entry must not survive in the live store");

        manager.save(McpTemplate.create("Fresh 2", McpToolConfig.ALL_ENABLED));
        try (var entries = Files.list(temp)) {
            assertEquals(1, entries.filter(p -> p.getFileName().toString()
                         .startsWith("mcp-templates.json.dropped-")).count(),
                         "the sidecar is written once, before the first persist after the skip");
        }
    }

    private Path findDroppedSidecar() throws Exception {
        try (var entries = Files.list(temp)) {
            return entries.filter(p -> p.getFileName().toString().startsWith("mcp-templates.json.dropped-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no .dropped- sidecar was written"));
        }
    }

}
