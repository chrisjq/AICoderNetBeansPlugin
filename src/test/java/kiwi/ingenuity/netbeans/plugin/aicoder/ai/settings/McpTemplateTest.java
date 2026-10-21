package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.EnumSet;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class McpTemplateTest {

    @Test
    void jsonRoundTripsEveryFieldAndConfigDisabledGroups() {
        McpToolConfig config = McpToolConfig.withDisabled(
                EnumSet.of(McpToolGroupEnum.DATABASE, McpToolGroupEnum.CLIPBOARD, McpToolGroupEnum.GIT_WRITE));
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-02T03:04:05Z");
        McpTemplate original = new McpTemplate("id-1", "Locked down", config, created, updated);

        McpTemplate loaded = McpTemplate.fromJson(original.toJson());

        assertEquals("id-1", loaded.id());
        assertEquals("Locked down", loaded.name());
        assertEquals(created, loaded.createdAt());
        assertEquals(updated, loaded.updatedAt());
        assertEquals(config, loaded.config());
        assertTrue(loaded.config().isDisabled(McpToolGroupEnum.DATABASE));
        assertTrue(loaded.config().isDisabled(McpToolGroupEnum.CLIPBOARD));
        assertTrue(loaded.config().isDisabled(McpToolGroupEnum.GIT_WRITE));
        assertFalse(loaded.config().isDisabled(McpToolGroupEnum.WEB));
    }

    @Test
    void defaultTemplateHasFixedIdAndIsRecognised() {
        McpTemplate template = McpTemplate.createDefault(McpToolConfig.ALL_ENABLED);

        assertEquals(McpTemplate.DEFAULT_ID, template.id());
        assertTrue(template.isDefault());
        assertFalse(McpTemplate.create("Other", McpToolConfig.ALL_ENABLED).isDefault());
    }

    @Test
    void withNameAndConfigKeepsIdAndCreatedTimestampAndRefreshesUpdated() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-02T00:00:00Z");
        McpTemplate original = new McpTemplate("id-1", "Original", McpToolConfig.ALL_ENABLED, created, updated);

        McpTemplate renamed = original.withNameAndConfig("Renamed",
                                                         McpToolConfig.withDisabled(EnumSet.of(McpToolGroupEnum.WEB)));

        assertEquals("id-1", renamed.id());
        assertEquals("Renamed", renamed.name());
        assertEquals(created, renamed.createdAt());
        assertTrue(renamed.updatedAt().isAfter(updated));
        assertTrue(renamed.config().isDisabled(McpToolGroupEnum.WEB));
    }

    @Test
    void missingOrMalformedConfigIsRejected() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        McpTemplate template = new McpTemplate("id-1", "Bad config", McpToolConfig.ALL_ENABLED, now, now);

        var absent = template.toJson();
        absent.remove("config");
        assertThrows(IllegalArgumentException.class, () -> McpTemplate.fromJson(absent));

        JsonObject withoutGroups = template.toJson();
        withoutGroups.add("config", new JsonObject());
        assertThrows(IllegalArgumentException.class, () -> McpTemplate.fromJson(withoutGroups));

        JsonObject notAnObject = template.toJson();
        notAnObject.addProperty("config", "nonsense");
        assertThrows(IllegalArgumentException.class, () -> McpTemplate.fromJson(notAnObject));
    }

    @Test
    void equalityIsByIdSoAReloadedTemplateReselects() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-02T00:00:00Z");
        McpTemplate original = new McpTemplate("id-1", "Original", McpToolConfig.ALL_ENABLED, created, updated);
        McpTemplate reloaded = new McpTemplate("id-1", "Renamed",
                                               McpToolConfig.withDisabled(EnumSet.of(McpToolGroupEnum.WEB)),
                                               created.plusSeconds(5), updated.plusSeconds(5));

        assertEquals(original, reloaded);
        assertEquals(original.hashCode(), reloaded.hashCode());
        assertNotEquals(original, new McpTemplate("id-2", "Original", McpToolConfig.ALL_ENABLED, created, updated));
    }

    @Test
    void missingOrUnparseableTimestampsFallBackInsteadOfDroppingTheTemplate() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-02T00:00:00Z");
        McpTemplate template = new McpTemplate("id-1", "Old", McpToolConfig.ALL_ENABLED, created, updated);

        JsonObject withoutStamps = template.toJson();
        withoutStamps.remove("createdAt");
        withoutStamps.remove("updatedAt");
        McpTemplate missing = McpTemplate.fromJson(withoutStamps);
        assertEquals("id-1", missing.id());
        assertEquals(McpToolConfig.ALL_ENABLED, missing.config());
        assertEquals(Instant.EPOCH, missing.createdAt());
        assertEquals(Instant.EPOCH, missing.updatedAt());

        JsonObject unparseableStamps = template.toJson();
        unparseableStamps.addProperty("createdAt", "not-an-instant");
        unparseableStamps.addProperty("updatedAt", "also-not-an-instant");
        McpTemplate garbage = McpTemplate.fromJson(unparseableStamps);
        assertEquals(Instant.EPOCH, garbage.createdAt());
        assertEquals(Instant.EPOCH, garbage.updatedAt());

        JsonObject unparseableUpdated = template.toJson();
        unparseableUpdated.addProperty("updatedAt", "not-an-instant");
        McpTemplate onlyUpdatedBad = McpTemplate.fromJson(unparseableUpdated);
        assertEquals(created, onlyUpdatedBad.createdAt());
        assertEquals(created, onlyUpdatedBad.updatedAt());
    }

}
