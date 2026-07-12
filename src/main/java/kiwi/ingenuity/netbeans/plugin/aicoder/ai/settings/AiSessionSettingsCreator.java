package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;

/**
 * Base class for creating and updating AI session settings. Implements a chain
 * of responsibility pattern where subclasses handle specific setting types.
 * Handles deserialization of JSON configuration into mutable settings objects.
 */
public abstract class AiSessionSettingsCreator<E extends AiSessionSettings> {

    public abstract E create();

    public void update(E settings, JsonObject cfgObj) {
        String key = AiSessionSettingsKeyEnum.MAX_HISTORY.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setMaxHistory(cfgObj.get(key).getAsInt());
        }
        key = AiSessionSettingsKeyEnum.RESTRICT_TO_PROJECT_FILES.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setRestrictToProjectFiles(cfgObj.get(key).getAsBoolean());
        }
        key = AiSessionSettingsKeyEnum.ALLOW_INTER_AI_COMMS.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setAllowInterAiComms(cfgObj.get(key).getAsBoolean());
        }
        key = AiSessionSettingsKeyEnum.AUTO_NOTIFY_INBOX.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setAutoNotifyInbox(cfgObj.get(key).getAsBoolean());
        }
        key = AiSessionSettingsKeyEnum.ALLOW_IMPORTANT_MESSAGES.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setAllowImportantMessages(cfgObj.get(key).getAsBoolean());
        }
        key = AiSessionSettingsKeyEnum.SESSION_INSTRUCTIONS.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setSessionInstructions(cfgObj.get(key).getAsString());
        }
        key = AiSessionSettingsKeyEnum.AUTO_ACCEPT.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setAutoAccept(cfgObj.get(key).getAsBoolean());
        }
        key = AiSessionSettingsKeyEnum.ALLOW_WEB_REQUESTS.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setAllowWebRequests(cfgObj.get(key).getAsBoolean());
        }
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            key = AiSessionSettingsKeyEnum.forWebRequestAccessOption(option).key();
            if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
                settings.setAllowWebRequestAccess(option, cfgObj.get(key).getAsBoolean());
            }
        }
        key = AiSessionSettingsKeyEnum.ALLOW_DATABASE_ACCESS.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setAllowDatabaseAccess(cfgObj.get(key).getAsBoolean());
        }
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            key = AiSessionSettingsKeyEnum.forDatabaseAccessOption(option).key();
            if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
                settings.setAllowDatabaseAccessOption(option, cfgObj.get(key).getAsBoolean());
            }
        }
        key = AiSessionSettingsKeyEnum.DATABASE_ROW_LIMIT.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setDatabaseRowLimit(cfgObj.get(key).getAsInt());
        }
    }

}
