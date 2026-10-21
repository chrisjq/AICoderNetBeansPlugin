package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolConfigTest {

    @Test
    void roundTripsDisabledGroupsAndIgnoresUnknownGroups() {
        McpToolConfig original = McpToolConfig.withDisabled(
                Set.of(McpToolGroupEnum.FILE_READING, McpToolGroupEnum.WEB));
        JsonObject json = original.toJson();
        json.getAsJsonArray("disabledGroups").add("FUTURE_GROUP");

        McpToolConfig loaded = McpToolConfig.fromJson(json);

        assertEquals(original, loaded);
        assertTrue(loaded.isDisabled(McpToolGroupEnum.FILE_READING));
        assertTrue(loaded.isDisabled(McpToolGroupEnum.WEB));
    }

    @Test
    void nonSwitchableGroupsCannotBeDisabled() {
        McpToolConfig config = McpToolConfig.withDisabled(
                Set.of(McpToolGroupEnum.ALWAYS_ON, McpToolGroupEnum.BUILD_QUEUE));

        assertEquals(McpToolConfig.ALL_ENABLED, config);
        assertEquals(McpToolConfig.ALL_ENABLED, config.with(McpToolGroupEnum.ALWAYS_ON, true));
        assertEquals(McpToolConfig.ALL_ENABLED, config.with(McpToolGroupEnum.BUILD_QUEUE, true));
    }

    @Test
    void withTogglesOnlyTheRequestedSwitchableGroup() {
        McpToolConfig disabled = McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.DATABASE, true);
        assertTrue(disabled.isDisabled(McpToolGroupEnum.DATABASE));

        McpToolConfig enabled = disabled.with(McpToolGroupEnum.DATABASE, false);
        assertFalse(enabled.isDisabled(McpToolGroupEnum.DATABASE));
    }

    @Test
    void parseOrNullReturnsNullForMissingOrMalformedGroups() {
        assertNull(McpToolConfig.parseOrNull(null));
        assertNull(McpToolConfig.parseOrNull(new JsonObject()));

        JsonObject notAnArray = new JsonObject();
        notAnArray.addProperty("disabledGroups", "WEB");
        assertNull(McpToolConfig.parseOrNull(notAnArray));
    }

    @Test
    void parseOrNullReadsGroupsAndSkipsUnknownOnes() {
        JsonObject json = McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)).toJson();
        json.getAsJsonArray("disabledGroups").add("FUTURE_GROUP");

        McpToolConfig parsed = McpToolConfig.parseOrNull(json);

        assertNotNull(parsed);
        assertTrue(parsed.isDisabled(McpToolGroupEnum.WEB));
        assertFalse(parsed.isDisabled(McpToolGroupEnum.FILE_READING));
    }

    @Test
    void allEnabledHasNoDisabledGroups() {
        assertTrue(McpToolConfig.ALL_ENABLED.disabledGroups().isEmpty());
    }

    @Test
    void disabledGroupsIsUnmodifiable() {
        McpToolConfig config = McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB));

        assertThrows(UnsupportedOperationException.class,
                     () -> config.disabledGroups().add(McpToolGroupEnum.DATABASE));
    }

    @Test
    void withDoesNotMutateTheOriginal() {
        McpToolConfig original = McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB));

        McpToolConfig changed = original.with(McpToolGroupEnum.WEB, false);

        assertTrue(original.isDisabled(McpToolGroupEnum.WEB));
        assertFalse(changed.isDisabled(McpToolGroupEnum.WEB));
    }

    @Test
    void parseOrNullReturnsNullWhenDisabledGroupsIsJsonNull() {
        JsonObject json = new JsonObject();
        json.add("disabledGroups", JsonNull.INSTANCE);

        assertNull(McpToolConfig.parseOrNull(json));
    }

    @Test
    void parseOrNullReturnsAConfigWithNothingDisabledForAnEmptyArray() {
        JsonObject json = new JsonObject();
        json.add("disabledGroups", new JsonArray());

        McpToolConfig parsed = McpToolConfig.parseOrNull(json);

        assertNotNull(parsed);
        assertTrue(parsed.disabledGroups().isEmpty());
    }

    @Test
    void parseOrNullSkipsNumbersBooleansAndNestedArrays() {
        JsonArray groups = new JsonArray();
        groups.add(5);
        groups.add(true);
        JsonArray nested = new JsonArray();
        nested.add("FILE_READING");
        groups.add(nested);
        JsonObject json = new JsonObject();
        json.add("disabledGroups", groups);

        McpToolConfig parsed = McpToolConfig.parseOrNull(json);

        assertNotNull(parsed);
        assertTrue(parsed.disabledGroups().isEmpty());
    }

    @Test
    void parseOrNullDoesNotDisableAlwaysOn() {
        JsonObject json = new JsonObject();
        JsonArray groups = new JsonArray();
        groups.add("ALWAYS_ON");
        groups.add("WEB");
        json.add("disabledGroups", groups);

        McpToolConfig parsed = McpToolConfig.parseOrNull(json);

        assertNotNull(parsed);
        assertFalse(parsed.isDisabled(McpToolGroupEnum.ALWAYS_ON));
        assertTrue(parsed.isDisabled(McpToolGroupEnum.WEB));
    }

    @Test
    void fromJsonFallsBackToAllEnabledForNullOrEmptyJson() {
        assertEquals(McpToolConfig.ALL_ENABLED, McpToolConfig.fromJson(null));
        assertEquals(McpToolConfig.ALL_ENABLED, McpToolConfig.fromJson(new JsonObject()));
    }

    @Test
    void equalConfigsHaveEqualHashCodes() {
        McpToolConfig first = McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB, McpToolGroupEnum.FILE_READING));
        McpToolConfig second = McpToolConfig.withDisabled(List.of(McpToolGroupEnum.FILE_READING, McpToolGroupEnum.WEB));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void equalsIsFalseForNull() {
        assertFalse(McpToolConfig.ALL_ENABLED.equals(null));
    }
}
