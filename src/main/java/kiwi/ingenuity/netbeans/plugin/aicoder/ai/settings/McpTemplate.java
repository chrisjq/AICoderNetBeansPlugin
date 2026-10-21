package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;

/**
 * Immutable named MCP tool-exposure template. Persisted separately from {@link ConfigTemplate}, in the store owned by
 * the MCP template persistence manager.
 */
public final class McpTemplate {

    /**
     * Fixed id of the built-in template every new session gets unless another is chosen. Editable, never deletable.
     */
    public static final String DEFAULT_ID = "default";

    private static final String DEFAULT_NAME = "Default";

    public static McpTemplate create(String name, McpToolConfig config) {
        Instant now = Instant.now();
        return new McpTemplate(UUID.randomUUID().toString(), name, config, now, now);
    }

    public static McpTemplate createDefault(McpToolConfig config) {
        Instant now = Instant.now();
        return new McpTemplate(DEFAULT_ID, DEFAULT_NAME, config, now, now);
    }

    public static McpTemplate fromJson(JsonObject value) {
        String id = value.get("id").getAsString();
        String name = value.get("name").getAsString();
        Instant created = instantOr(value, "createdAt", Instant.EPOCH);
        Instant updated = instantOr(value, "updatedAt", created);
        if (!value.has("config") || !value.get("config").isJsonObject()) {
            throw new IllegalArgumentException("MCP template config is missing or malformed");
        }
        McpToolConfig config = McpToolConfig.parseOrNull(value.getAsJsonObject("config"));
        if (config == null) {
            throw new IllegalArgumentException("MCP template config is missing or malformed");
        }
        return new McpTemplate(id, name, config, created, updated);
    }

    private static Instant instantOr(JsonObject value, String field, Instant fallback) {
        if (!value.has(field) || value.get(field).isJsonNull()) {
            return fallback;
        }
        try {
            return Instant.parse(value.get(field).getAsString());
        }
        catch (RuntimeException ex) {
            return fallback;
        }
    }

    private final String id;
    private final String name;
    private final McpToolConfig config;
    private final Instant createdAt;
    private final Instant updatedAt;

    public McpTemplate(String id, String name, McpToolConfig config, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.config = config == null ? McpToolConfig.ALL_ENABLED : config;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public McpToolConfig config() {
        return config;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isDefault() {
        return DEFAULT_ID.equals(id);
    }

    public McpTemplate withNameAndConfig(String newName, McpToolConfig newConfig) {
        return new McpTemplate(id, newName, newConfig, createdAt, Instant.now());
    }

    public JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("name", name);
        value.addProperty("createdAt", createdAt.toString());
        value.addProperty("updatedAt", updatedAt.toString());
        value.add("config", config.toJson());
        return value;
    }

    /**
     * Identity is the id alone: reloading the store produces fresh instances, so without this the session picker could
     * not re-select a template after a refresh, and a renamed template would apply its stale configuration.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof McpTemplate template && id.equals(template.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

}
