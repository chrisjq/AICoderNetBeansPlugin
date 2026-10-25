package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JComponent;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;

/**
 * Base class for creating and updating AI session settings. Implements a chain of responsibility pattern where
 * subclasses handle specific setting types. Handles deserialization of JSON configuration into mutable settings
 * objects.
 */
public abstract class AiSessionSettingsCreator<E extends AiSessionSettings> {

    private static final Logger LOG = Logger.getLogger(AiSessionSettingsCreator.class.getName());

    public abstract E create();

    /**
     * Creates a transient, typed panel for settings selected while creating a session.
     */
    public AiSessionCreateSettingsPanel<E> createSettingsPanel() {
        return new AiSessionCreateSettingsPanel<>() {
            private final JPanel component = new JPanel();

            @Override
            public JComponent component() {
                return component;
            }

            @Override
            public void load(E settings) {
            }

            @Override
            public void applyTo(E settings) {
            }

            @Override
            public void dispose() {
            }
        };
    }

    public void update(E settings, JsonObject cfgObj) {
        String key = AiSessionSettingsKeyEnum.MAX_HISTORY.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setMaxHistory(cfgObj.get(key).getAsInt());
        }
        key = AiSessionSettingsKeyEnum.SAVE_HISTORY.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setSaveHistory(cfgObj.get(key).getAsBoolean());
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
        key = AiSessionSettingsKeyEnum.ALLOW_IDLE_WATCHER_TIMER.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setAllowIdleWatcherTimer(cfgObj.get(key).getAsBoolean());
        }
        key = AiSessionSettingsKeyEnum.MCP_STEERING.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setMcpSteering(cfgObj.get(key).getAsBoolean());
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
        key = AiSessionSettingsKeyEnum.ALLOW_GIT_ACCESS.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setAllowGitAccess(cfgObj.get(key).getAsBoolean());
        }
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            key = AiSessionSettingsKeyEnum.forGitAccessOption(option).key();
            if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
                settings.setAllowGitAccessOption(option, cfgObj.get(key).getAsBoolean());
            }
        }
        key = AiSessionSettingsKeyEnum.ENABLE_CLIPBOARD_ACCESS.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setEnableClipboardAccess(cfgObj.get(key).getAsBoolean());
        }
        key = AiSessionSettingsKeyEnum.BACKEND_DEBUG_LOG.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setBackendDebugLog(cfgObj.get(key).getAsBoolean());
        }
        key = AiSessionSettingsKeyEnum.MCP_TOOL_CONFIG.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonObject()) {
            // A malformed config must not silently widen access: parseOrNull leaves it null, so the session keeps
            // deriving its tools from the legacy toggles instead.
            settings.setMcpToolConfig(McpToolConfig.parseOrNull(cfgObj.getAsJsonObject(key)));
        }
        key = AiSessionSettingsKeyEnum.MCP_ROUTING_ID.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive() && !cfgObj.get(key).getAsString().isBlank()) {
            String routingId = cfgObj.get(key).getAsString();
            if (AiSessionSettings.isValidMcpRoutingId(routingId)) {
                settings.setMcpRoutingId(routingId);
            }
            else {
                LOG.log(Level.WARNING, "ignoring malformed persisted MCP routing id; a new one will be generated "
                        + "(value starts with \"{0}\")", routingId.substring(0, Math.min(16, routingId.length())));
            }
        }
    }

    public abstract void applyDefaultSettingsFromGlobal(AiSessionSettings settings);

}
