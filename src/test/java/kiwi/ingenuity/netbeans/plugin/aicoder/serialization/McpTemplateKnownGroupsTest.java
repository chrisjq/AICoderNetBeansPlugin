package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A group added after a template was saved takes the template's starting state: off in the built-in Minimal template,
 * on everywhere else. The store records which groups a config knew, so the choice is made once and a later change by
 * the user is kept.
 */
class McpTemplateKnownGroupsTest {

    @TempDir
    Path temp;

    /**
     * The JSON of a template as an earlier version wrote it: no {@code knownGroups}, and no {@code FILE_BINARY} among
     * the disabled groups because the group did not exist.
     */
    private static JsonObject legacy(McpTemplate template) {
        JsonObject json = template.toJson();
        JsonObject config = json.getAsJsonObject("config");
        config.remove("knownGroups");
        JsonArray disabled = new JsonArray();
        for (JsonElement element : config.getAsJsonArray("disabledGroups")) {
            if (!McpToolGroupEnum.FILE_BINARY.name().equals(element.getAsString())) {
                disabled.add(element);
            }
        }
        config.add("disabledGroups", disabled);
        return json;
    }

    private void store(JsonObject... templates) throws Exception {
        JsonArray array = new JsonArray();
        for (JsonObject template : templates) {
            array.add(template);
        }
        Files.writeString(temp.resolve("mcp-templates.json"), array.toString());
    }

    private List<McpTemplate> load() throws Exception {
        return new McpTemplatePersistenceManager(temp).loadMcpTemplates();
    }

    private static McpTemplate byId(List<McpTemplate> templates, String id) {
        return templates.stream().filter(t -> id.equals(t.id())).findFirst().orElseThrow();
    }

    private static Set<String> disabledNames(McpToolConfig config) {
        Set<String> names = new HashSet<>();
        config.disabledGroups().forEach(group -> names.add(group.name()));
        return names;
    }

    @Test
    @Timeout(10)
    void aLegacyMinimalKeepsFileBinaryOffAndEverythingElseAsItWas() throws Exception {
        McpTemplate minimal = McpTemplate.createMinimal();
        store(McpTemplate.createDefault(McpToolConfig.ALL_ENABLED).toJson(), legacy(minimal));

        McpToolConfig loaded = byId(load(), McpTemplate.MINIMAL_ID).config();

        assertTrue(loaded.isDisabled(McpToolGroupEnum.FILE_BINARY));
        Set<String> expected = disabledNames(minimal.config());
        assertEquals(expected, disabledNames(loaded));
        assertFalse(loaded.isDisabled(McpToolGroupEnum.FILE_READING));
        assertFalse(loaded.isDisabled(McpToolGroupEnum.FILE_EDITING));
        assertFalse(loaded.isDisabled(McpToolGroupEnum.TEXT_SEARCH));
    }

    @Test
    @Timeout(10)
    void aLegacyDefaultAndAUserTemplateHaveFileBinaryOn() throws Exception {
        McpTemplate user = McpTemplate.create("Mine", McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.WEB, true));
        store(legacy(McpTemplate.createDefault(McpToolConfig.ALL_ENABLED)), legacy(user),
              legacy(McpTemplate.createMinimal()));

        List<McpTemplate> loaded = load();

        assertFalse(byId(loaded, McpTemplate.DEFAULT_ID).config().isDisabled(McpToolGroupEnum.FILE_BINARY));
        McpToolConfig mine = byId(loaded, user.id()).config();
        assertFalse(mine.isDisabled(McpToolGroupEnum.FILE_BINARY));
        assertTrue(mine.isDisabled(McpToolGroupEnum.WEB), "the user's own choices are untouched");
    }

    @Test
    @Timeout(10)
    void theMigrationIsWrittenBackWithKnownGroups() throws Exception {
        store(legacy(McpTemplate.createDefault(McpToolConfig.ALL_ENABLED)), legacy(McpTemplate.createMinimal()));

        load();

        JsonArray written = JsonParser.parseString(Files.readString(temp.resolve("mcp-templates.json")))
                .getAsJsonArray();
        assertEquals(2, written.size());
        for (JsonElement element : written) {
            JsonObject config = element.getAsJsonObject().getAsJsonObject("config");
            assertNotNull(config.getAsJsonArray("knownGroups"), "knownGroups is written for every template");
            assertTrue(config.getAsJsonArray("knownGroups").contains(
                    JsonParser.parseString("\"" + McpToolGroupEnum.FILE_BINARY.name() + "\"")));
        }
        JsonObject minimalConfig = null;
        for (JsonElement element : written) {
            if (McpTemplate.MINIMAL_ID.equals(element.getAsJsonObject().get("id").getAsString())) {
                minimalConfig = element.getAsJsonObject().getAsJsonObject("config");
            }
        }
        assertNotNull(minimalConfig);
        assertTrue(minimalConfig.getAsJsonArray("disabledGroups").contains(
                JsonParser.parseString("\"" + McpToolGroupEnum.FILE_BINARY.name() + "\"")),
                   "the migrated Minimal is stored with FILE_BINARY off");
    }

    @Test
    @Timeout(10)
    void aMinimalThatTheUserSwitchedFileBinaryOnInStaysOn() throws Exception {
        McpTemplatePersistenceManager manager = new McpTemplatePersistenceManager(temp);
        McpTemplate minimal = byId(manager.loadMcpTemplates(), McpTemplate.MINIMAL_ID);
        manager.save(minimal.withNameAndConfig(minimal.name(),
                                               minimal.config().with(McpToolGroupEnum.FILE_BINARY, false)));

        McpTemplate reloaded = byId(new McpTemplatePersistenceManager(temp).loadMcpTemplates(),
                                    McpTemplate.MINIMAL_ID);
        McpTemplate reloadedAgain = byId(new McpTemplatePersistenceManager(temp).loadMcpTemplates(),
                                         McpTemplate.MINIMAL_ID);

        assertFalse(reloaded.config().isDisabled(McpToolGroupEnum.FILE_BINARY));
        assertFalse(reloadedAgain.config().isDisabled(McpToolGroupEnum.FILE_BINARY));
    }

    @Test
    @Timeout(10)
    void aMinimalSavedWithKnownGroupsAndFileBinaryOnIsNotChanged() throws Exception {
        McpTemplate minimal = McpTemplate.createMinimal();
        McpTemplate withBinary = minimal.withNameAndConfig(minimal.name(),
                                                           minimal.config().with(McpToolGroupEnum.FILE_BINARY, false));
        store(McpTemplate.createDefault(McpToolConfig.ALL_ENABLED).toJson(), withBinary.toJson());

        assertFalse(byId(load(), McpTemplate.MINIMAL_ID).config().isDisabled(McpToolGroupEnum.FILE_BINARY));
    }

    @Test
    @Timeout(10)
    void toJsonRecordsEverySwitchableGroup() {
        JsonArray known = McpToolConfig.ALL_ENABLED.toJson().getAsJsonArray("knownGroups");

        Set<String> names = new HashSet<>();
        known.forEach(element -> names.add(element.getAsString()));
        for (McpToolGroupEnum group : McpToolGroupEnum.values()) {
            assertEquals(group.switchable(), names.contains(group.name()), group.name());
        }
    }

    @Test
    @Timeout(10)
    void aGroupMissingFromKnownGroupsIsNewAndFollowsTheTemplateDefault() {
        JsonObject json = McpToolConfig.ALL_ENABLED.toJson();
        JsonArray known = new JsonArray();
        json.getAsJsonArray("knownGroups").forEach(element -> {
            if (!McpToolGroupEnum.WEB.name().equals(element.getAsString())) {
                known.add(element);
            }
        });
        json.add("knownGroups", known);

        assertFalse(McpToolConfig.parseOrNull(json).isDisabled(McpToolGroupEnum.WEB));
        assertTrue(McpToolConfig.parseOrNull(json, true).isDisabled(McpToolGroupEnum.WEB));
        assertEquals(Set.of(McpToolGroupEnum.WEB), McpToolConfig.groupsNewTo(json));
    }

    @Test
    @Timeout(10)
    void aLegacyConfigKnowsEveryGroupExceptFileBinary() {
        JsonObject json = new JsonObject();
        json.add("disabledGroups", new JsonArray());

        assertEquals(Set.of(McpToolGroupEnum.FILE_BINARY), McpToolConfig.groupsNewTo(json));
        assertTrue(McpToolConfig.needsKnownGroupsUpdate(json));
        assertFalse(McpToolConfig.needsKnownGroupsUpdate(McpToolConfig.ALL_ENABLED.toJson()));
    }

    @Test
    @Timeout(10)
    void aSessionConfigWithoutKnownGroupsParsesAsBefore() {
        JsonObject json = new JsonObject();
        JsonArray disabled = new JsonArray();
        disabled.add(McpToolGroupEnum.WEB.name());
        json.add("disabledGroups", disabled);

        McpToolConfig parsed = McpToolConfig.parseOrNull(json);

        assertEquals(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)), parsed);
        assertFalse(parsed.isDisabled(McpToolGroupEnum.FILE_BINARY));
    }

    @Test
    @Timeout(10)
    void aTemplateRoundTripKeepsTheConfigEqual() throws Exception {
        McpTemplate user = new McpTemplate("u1", "Mine", McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.WEB, true),
                                           Instant.now(), Instant.now());

        McpTemplate parsed = McpTemplate.fromJson(user.toJson());

        assertEquals(user.config(), parsed.config());
    }
}
