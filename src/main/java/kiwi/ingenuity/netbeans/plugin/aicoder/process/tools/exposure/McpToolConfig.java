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

    private static final String DISABLED_GROUPS_KEY = "disabledGroups";
    private static final String KNOWN_GROUPS_KEY = "knownGroups";

    /**
     * The groups added after configs were first stored, when only {@code disabledGroups} was written. A stored config
     * without a {@code knownGroups} list is taken to know every group except these. Add a group here only if configs
     * written before it existed must treat it as new; a group added from now on is covered by {@code knownGroups}.
     */
    private static final Set<McpToolGroupEnum> GROUPS_ADDED_AFTER_KNOWN_GROUPS_LIST = Set.of(McpToolGroupEnum.FILE_BINARY);

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

    /**
     * Writes the disabled groups and, beside them, every switchable group that exists now. Only the disabled groups are
     * stored as a choice; the known groups let a later version tell a group the user left on from a group that did not
     * exist when this was saved.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray groups = new JsonArray();
        for (McpToolGroupEnum group : disabledGroups) {
            groups.add(group.name());
        }
        json.add(DISABLED_GROUPS_KEY, groups);
        JsonArray known = new JsonArray();
        for (McpToolGroupEnum group : McpToolGroupEnum.values()) {
            if (group.switchable()) {
                known.add(group.name());
            }
        }
        json.add(KNOWN_GROUPS_KEY, known);
        return json;
    }

    /**
     * Parses a configuration, in which a group the stored config did not know about is on.
     *
     * @see #parseOrNull(JsonObject, boolean)
     */
    public static McpToolConfig parseOrNull(JsonObject json) {
        return parseOrNull(json, false);
    }

    /**
     * Parses a configuration, returning {@code null} when {@code disabledGroups} is absent or not an array instead of
     * silently substituting the permissive {@link #ALL_ENABLED}. Callers use the null to fail closed — falling back to
     * legacy-derived values rather than granting every group. Unknown group names are still skipped, so a store written
     * by a newer version degrades gracefully rather than failing outright.
     *
     * <p>
     * A switchable group that the stored config does not list in {@code knownGroups} is new to it (see
     * {@link #groupsNewTo}). {@code newGroupsDisabled} says what such a group becomes: off for a config that starts
     * minimal, on for every other.
     */
    public static McpToolConfig parseOrNull(JsonObject json, boolean newGroupsDisabled) {
        if (json == null || !json.has(DISABLED_GROUPS_KEY) || !json.get(DISABLED_GROUPS_KEY).isJsonArray()) {
            return null;
        }
        EnumSet<McpToolGroupEnum> groups = EnumSet.noneOf(McpToolGroupEnum.class);
        groups.addAll(readGroups(json.getAsJsonArray(DISABLED_GROUPS_KEY)));
        if (newGroupsDisabled) {
            groups.addAll(groupsNewTo(json));
        }
        return new McpToolConfig(groups);
    }

    /**
     * The switchable groups the stored config did not know about when it was saved: those missing from its
     * {@code knownGroups} list. A config with no such list is an older one, and knows every group except
     * {@link #GROUPS_ADDED_AFTER_KNOWN_GROUPS_LIST}.
     */
    public static Set<McpToolGroupEnum> groupsNewTo(JsonObject json) {
        EnumSet<McpToolGroupEnum> known;
        if (json != null && json.has(KNOWN_GROUPS_KEY) && json.get(KNOWN_GROUPS_KEY).isJsonArray()) {
            known = readGroups(json.getAsJsonArray(KNOWN_GROUPS_KEY));
        }
        else {
            known = EnumSet.allOf(McpToolGroupEnum.class);
            known.removeAll(GROUPS_ADDED_AFTER_KNOWN_GROUPS_LIST);
        }
        EnumSet<McpToolGroupEnum> added = EnumSet.noneOf(McpToolGroupEnum.class);
        for (McpToolGroupEnum group : McpToolGroupEnum.values()) {
            if (group.switchable() && !known.contains(group)) {
                added.add(group);
            }
        }
        return added;
    }

    /**
     * Whether a stored config is missing its {@code knownGroups} list or lacks a group this version has, so that
     * writing it back would record what it now knows.
     */
    public static boolean needsKnownGroupsUpdate(JsonObject json) {
        return json == null || !json.has(KNOWN_GROUPS_KEY) || !json.get(KNOWN_GROUPS_KEY).isJsonArray()
                || !groupsNewTo(json).isEmpty();
    }

    private static EnumSet<McpToolGroupEnum> readGroups(JsonArray array) {
        EnumSet<McpToolGroupEnum> groups = EnumSet.noneOf(McpToolGroupEnum.class);
        for (JsonElement element : array) {
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
        return groups;
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
