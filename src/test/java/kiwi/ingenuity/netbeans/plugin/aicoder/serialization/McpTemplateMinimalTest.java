package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.JsonArray;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The built-in Minimal MCP template: seeded alongside Default in every store, never deletable, and off by default in
 * both pickers.
 */
class McpTemplateMinimalTest {

    @TempDir
    Path temp;

    @Test
    @Timeout(10)
    void newStoreSeedsDefaultAndMinimalWithDefaultFirst() throws Exception {
        List<McpTemplate> loaded = new McpTemplatePersistenceManager(temp).loadMcpTemplates();

        assertEquals(2, loaded.size());
        assertTrue(loaded.get(0).isDefault());
        assertTrue(loaded.get(1).isMinimal());
        assertEquals(McpTemplate.MINIMAL_ID, loaded.get(1).id());
        assertEquals(McpTemplate.MINIMAL_NAME, loaded.get(1).name());
    }

    @Test
    @Timeout(10)
    void existingStoreWithoutMinimalGainsItAndKeepsItsEntries() throws Exception {
        JsonArray stored = new JsonArray();
        stored.add(McpTemplate.createDefault(McpToolConfig.ALL_ENABLED).toJson());
        stored.add(McpTemplate.create("Other", McpToolConfig.ALL_ENABLED).toJson());
        Files.writeString(temp.resolve("mcp-templates.json"), stored.toString());

        List<McpTemplate> loaded = new McpTemplatePersistenceManager(temp).loadMcpTemplates();

        assertEquals(3, loaded.size());
        assertTrue(loaded.stream().anyMatch(McpTemplate::isDefault));
        assertTrue(loaded.stream().anyMatch(t -> "Other".equals(t.name())));
        assertTrue(loaded.stream().anyMatch(McpTemplate::isMinimal), "an existing store gains Minimal");
        assertTrue(new McpTemplatePersistenceManager(temp).loadMcpTemplates().stream()
                .anyMatch(McpTemplate::isMinimal), "the added Minimal is persisted, not just returned");
    }

    @Test
    @Timeout(10)
    void anEditedMinimalSurvivesReloadAndIsNotOverwritten() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        McpTemplate minimal = manager.loadMcpTemplates().stream().filter(McpTemplate::isMinimal).findFirst().orElseThrow();

        McpToolConfig edited = McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.FILE_EDITING, true);
        manager.save(minimal.withNameAndConfig("Renamed Minimal", edited));

        McpTemplate reloaded = new McpTemplatePersistenceManager(temp).loadMcpTemplates().stream()
                .filter(McpTemplate::isMinimal).findFirst().orElseThrow();
        assertEquals("Renamed Minimal", reloaded.name());
        assertEquals(edited, reloaded.config());
        assertEquals(1, new McpTemplatePersistenceManager(temp).loadMcpTemplates().stream()
                     .filter(McpTemplate::isMinimal).count(), "the edited Minimal is never duplicated");
    }

    @Test
    @Timeout(10)
    void deleteOfMinimalIsRefusedAndTheTemplateRemains() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        manager.loadMcpTemplates();

        assertFalse(manager.deleteMcpTemplate(McpTemplate.MINIMAL_ID));
        assertTrue(manager.loadMcpTemplates().stream().anyMatch(McpTemplate::isMinimal));
    }

    @Test
    @Timeout(10)
    void createMinimalTurnsOnFileReadingEditingAndTextSearchAndEverythingElseOff() {
        McpToolConfig config = McpTemplate.createMinimal().config();

        assertFalse(config.isDisabled(McpToolGroupEnum.FILE_READING));
        assertFalse(config.isDisabled(McpToolGroupEnum.FILE_EDITING));
        assertFalse(config.isDisabled(McpToolGroupEnum.TEXT_SEARCH));
        for (McpToolGroupEnum group : McpToolGroupEnum.values()) {
            if (group == McpToolGroupEnum.FILE_READING || group == McpToolGroupEnum.FILE_EDITING
                    || group == McpToolGroupEnum.TEXT_SEARCH || !group.switchable()) {
                continue;
            }
            assertTrue(config.isDisabled(group), group + " must be off in Minimal");
        }
    }

    @Test
    @Timeout(10)
    void minimalIsMinimalNotDefault() {
        McpTemplate minimal = McpTemplate.createMinimal();

        assertTrue(minimal.isMinimal());
        assertFalse(minimal.isDefault());
        assertFalse(McpTemplate.createDefault(McpToolConfig.ALL_ENABLED).isMinimal());
    }

    @Test
    @Timeout(10)
    void aUserTemplateNamedMinimalCoexistsWithTheBuiltInOne() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        manager.loadMcpTemplates();
        McpTemplate user = new McpTemplate("user-minimal", McpTemplate.MINIMAL_NAME, McpToolConfig.ALL_ENABLED,
                                           Instant.now(), Instant.now());
        manager.save(user);

        List<McpTemplate> loaded = manager.loadMcpTemplates();

        assertEquals(3, loaded.size());
        assertEquals(1, loaded.stream().filter(McpTemplate::isMinimal).count());
        McpTemplate storedUser = loaded.stream().filter(t -> "user-minimal".equals(t.id())).findFirst().orElseThrow();
        assertFalse(storedUser.isMinimal(), "a user template named Minimal is not the built-in one");
        assertEquals(McpTemplate.MINIMAL_NAME, storedUser.name());
    }
}
