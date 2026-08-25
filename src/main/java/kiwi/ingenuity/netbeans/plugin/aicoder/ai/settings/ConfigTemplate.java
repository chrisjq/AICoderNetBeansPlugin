package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;

/**
 * Immutable generic session-settings snapshot used when creating a session.
 */
public final class ConfigTemplate {

    public static ConfigTemplate create(String name, AiSessionSettings settings) {
        Instant now = Instant.now();
        return new ConfigTemplate(UUID.randomUUID().toString(), name, settings, now, now);
    }

    public static ConfigTemplate fromJson(JsonObject value) {
        String id = value.get("id").getAsString();
        String name = value.get("name").getAsString();
        Instant created = Instant.parse(value.get("createdAt").getAsString());
        Instant updated = Instant.parse(value.get("updatedAt").getAsString());
        AiSessionSettings config = new AiSessionSettings();
        if (value.has("config") && value.get("config").isJsonObject()) {
            new AiSessionSettingsCreator<AiSessionSettings>() {
                @Override
                public AiSessionSettings create() {
                    return new AiSessionSettings();
                }

                @Override
                public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
                }
            }.update(config, value.getAsJsonObject("config"));
        }
        config.setSessionInstructions(null);
        return new ConfigTemplate(id, name, config, created, updated);
    }

    public static AiSessionSettings copy(AiSessionSettings source) {
        AiSessionSettings result = new AiSessionSettings();
        copyValues(source, result);
        return result;
    }

    public static void copyValues(AiSessionSettings source, AiSessionSettings target) {
        target.setMaxHistory(source.effectiveMaxHistory());
        target.setSaveHistory(source.effectiveSaveHistory());
        target.setRestrictToProjectFiles(source.effectiveRestrictToProjectFiles());
        target.setAllowInterAiComms(source.effectiveAllowInterAiComms());
        target.setAutoNotifyInbox(source.effectiveAutoNotifyInbox());
        target.setAllowImportantMessages(source.effectiveAllowImportantMessages());
        target.setAutoAccept(source.effectiveAutoAccept());
        target.setAllowWebRequests(source.effectiveAllowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            target.setAllowWebRequestAccess(option, source.effectiveAllowWebRequestAccess(option));
        }
        target.setAllowDatabaseAccess(source.effectiveAllowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            target.setAllowDatabaseAccessOption(option, source.effectiveAllowDatabaseAccessOption(option));
        }
        target.setAllowGitAccess(source.effectiveAllowGitAccess());
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            target.setAllowGitAccessOption(option, source.effectiveAllowGitAccessOption(option));
        }
        target.setDatabaseRowLimit(source.effectiveDatabaseRowLimit());
        target.setEnableClipboardAccess(source.effectiveEnableClipboardAccess());
    }

    private final String id;
    private final String name;
    private final AiSessionSettings settings;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ConfigTemplate(String id, String name, AiSessionSettings settings, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.settings = copy(settings);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public AiSessionSettings settings() {
        return copy(settings);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public ConfigTemplate withNameAndSettings(String newName, AiSessionSettings newSettings) {
        return new ConfigTemplate(id, newName, newSettings, createdAt, Instant.now());
    }

    public void applyTo(AiSessionSettings target) {
        copyValues(settings, target);
    }

    public JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("name", name);
        value.addProperty("createdAt", createdAt.toString());
        value.addProperty("updatedAt", updatedAt.toString());
        JsonObject config = new JsonObject();
        settings.populateJsonObject(config);
        config.remove(AiSessionSettingsKeyEnum.SESSION_INSTRUCTIONS.key());
        value.add("config", config);
        return value;
    }

}
