package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable per-session selection of disabled, switchable MCP tool groups.
 */
public final class McpToolConfig {

    public static final McpToolConfig ALL_ENABLED = new McpToolConfig(Set.of());

    private final Set<McpToolGroupEnum> disabledGroups;

    private McpToolConfig(Collection<McpToolGroupEnum> disabledGroups) {
        EnumSet<McpToolGroupEnum> copy = EnumSet.noneOf(McpToolGroupEnum.class);
        for (McpToolGroupEnum group : disabledGroups) {
            if (group != null && group.switchable()) {
                copy.add(group);
            }
        }
        this.disabledGroups = Collections.unmodifiableSet(copy);
    }

    public static McpToolConfig withDisabled(Collection<McpToolGroupEnum> groups) {
        return new McpToolConfig(groups);
    }

    public boolean isDisabled(McpToolGroupEnum group) {
        return disabledGroups.contains(group);
    }

    public Set<McpToolGroupEnum> disabledGroups() {
        return disabledGroups;
    }

    public McpToolConfig with(McpToolGroupEnum group, boolean disabled) {
        EnumSet<McpToolGroupEnum> changed = disabledGroups.isEmpty()
                                            ? EnumSet.noneOf(McpToolGroupEnum.class) : EnumSet.copyOf(disabledGroups);
        if (group != null && group.switchable()) {
            if (disabled) {
                changed.add(group);
            }
            else {
                changed.remove(group);
            }
        }
        return new McpToolConfig(changed);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray groups = new JsonArray();
        for (McpToolGroupEnum group : disabledGroups) {
            groups.add(group.name());
        }
        json.add("disabledGroups", groups);
        return json;
    }

    /**
     * Parses a configuration, returning {@code null} when {@code disabledGroups} is absent or not an array instead of
     * silently substituting the permissive {@link #ALL_ENABLED}. Callers use the null to fail closed — falling back to
     * legacy-derived values rather than granting every group. Unknown group names are still skipped, so a store written
     * by a newer version degrades gracefully rather than failing outright.
     */
    public static McpToolConfig parseOrNull(JsonObject json) {
        if (json == null || !json.has("disabledGroups") || !json.get("disabledGroups").isJsonArray()) {
            return null;
        }
        EnumSet<McpToolGroupEnum> groups = EnumSet.noneOf(McpToolGroupEnum.class);
        for (JsonElement element : json.getAsJsonArray("disabledGroups")) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            try {
                groups.add(McpToolGroupEnum.valueOf(element.getAsString()));
            }
            catch (IllegalArgumentException ex) {
                // Forward-compatible: an unknown group is simply unavailable in this version.
            }
        }
        return new McpToolConfig(groups);
    }

    /**
     * Lenient parse that substitutes {@link #ALL_ENABLED} for a missing or malformed value. Prefer
     * {@link #parseOrNull(JsonObject)} wherever a malformed value must not silently widen access.
     */
    public static McpToolConfig fromJson(JsonObject json) {
        McpToolConfig parsed = parseOrNull(json);
        return parsed != null ? parsed : ALL_ENABLED;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof McpToolConfig config
                && disabledGroups.equals(config.disabledGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(disabledGroups);
    }
}
