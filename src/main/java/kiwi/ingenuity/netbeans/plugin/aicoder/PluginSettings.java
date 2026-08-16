package kiwi.ingenuity.netbeans.plugin.aicoder;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import org.openide.util.NbPreferences;

public final class PluginSettings {

    private static final Logger LOG = Logger.getLogger(PluginSettings.class.getName());

    // Cache the Preferences node. NbPreferences.forModule triggers NetBeans'
    // Repository/URL-factory lazy init the first time it is touched; on a JVM
    // without --add-opens java.base/java.net (e.g. some run/dev harnesses) that
    // init logs an InaccessibleObjectException. NbPreferences still returns a
    // usable node, but re-resolving it on every call (the MCP server reads
    // isDebugJson() per request, off the EDT) re-triggers that noisy path.
    // Caching resolves it once; the returned node is live, so reads/writes and
    // change listeners still work.
    private static volatile Preferences cachedPrefs;

    private static Preferences prefs() {
        Preferences p = cachedPrefs;
        if (p != null) {
            return p;
        }
        synchronized (PluginSettings.class) {
            if (cachedPrefs == null) {
                try {
                    cachedPrefs = NbPreferences.forModule(PluginSettings.class);
                }
                catch (Throwable t) {
                    // NetBeans preference storage genuinely unavailable — fall
                    // back to a plain JDK node so callers get defaults instead of
                    // an exception. Logged once.
                    LOG.log(Level.WARNING, "NbPreferences unavailable; using fallback prefs (defaults only)", t);
                    cachedPrefs = Preferences.userRoot().node("kiwi/ingenuity/aicoder/plugin");
                }
            }
            return cachedPrefs;
        }
    }

    private static boolean getBoolean(PluginSettingsKeyEnum key) {
        return prefs().getBoolean(key.key(), key.defaultBoolean());
    }

    private static int getInt(PluginSettingsKeyEnum key) {
        return prefs().getInt(key.key(), key.defaultInt());
    }

    private static String getString(PluginSettingsKeyEnum key) {
        return prefs().get(key.key(), key.defaultString());
    }

    public static boolean isSaveHistory() {
        return getBoolean(PluginSettingsKeyEnum.SAVE_HISTORY);
    }

    public static void setSaveHistory(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.SAVE_HISTORY.key(), v);
    }

    public static boolean isSaveSessionOnCloseIfTicked() {
        return getBoolean(PluginSettingsKeyEnum.SAVE_SESSION_ON_CLOSE_IF_TICKED);
    }

    public static void setSaveSessionOnCloseIfTicked(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.SAVE_SESSION_ON_CLOSE_IF_TICKED.key(), v);
    }

    public static int getDiffContextLines() {
        return getInt(PluginSettingsKeyEnum.DIFF_CONTEXT_LINES);
    }

    public static void setDiffContextLines(int v) {
        prefs().putInt(PluginSettingsKeyEnum.DIFF_CONTEXT_LINES.key(), v);
    }

    public static int getChatFontSize() {
        return getInt(PluginSettingsKeyEnum.CHAT_FONT_SIZE);
    }

    public static void setChatFontSize(int v) {
        prefs().putInt(PluginSettingsKeyEnum.CHAT_FONT_SIZE.key(), v);
    }

    public static boolean isAutoAccept() {
        return getBoolean(PluginSettingsKeyEnum.AUTO_ACCEPT);
    }

    public static void setAutoAccept(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.AUTO_ACCEPT.key(), v);
    }

    // ---- Per-AI type ----
    public static boolean isAiEnabled(AiTypeEnum type) {
        boolean defaultEnabled = type.isEnabledByDefault();
        return prefs().getBoolean(PluginSettingsKeyEnum.AI_ENABLED_PREFIX.key() + type.key(), defaultEnabled);
    }

    public static void setAiEnabled(AiTypeEnum type, boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.AI_ENABLED_PREFIX.key() + type.key(), v);
    }

    // ---- Global session defaults ----
    public static boolean isRestrictToProjectFiles() {
        return getBoolean(PluginSettingsKeyEnum.RESTRICT_TO_PROJECT);
    }

    public static void setRestrictToProjectFiles(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.RESTRICT_TO_PROJECT.key(), v);
    }

    public static boolean isAllowWebRequests() {
        return getBoolean(PluginSettingsKeyEnum.ALLOW_WEB_REQUESTS);
    }

    public static void setAllowWebRequests(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.ALLOW_WEB_REQUESTS.key(), v);
    }

    public static boolean isAllowWebRequestAccess(WebRequestAccessOptionEnum option) {
        return getBoolean(PluginSettingsKeyEnum.forWebRequestAccessOption(option));
    }

    public static void setAllowWebRequestAccess(WebRequestAccessOptionEnum option, boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.forWebRequestAccessOption(option).key(), v);
    }

    public static boolean isAllowDatabaseAccess() {
        return getBoolean(PluginSettingsKeyEnum.ALLOW_DATABASE_ACCESS);
    }

    public static void setAllowDatabaseAccess(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.ALLOW_DATABASE_ACCESS.key(), v);
    }

    public static boolean isEnableClipboardAccess() {
        return getBoolean(PluginSettingsKeyEnum.ENABLE_CLIPBOARD_ACCESS);
    }

    public static void setEnableClipboardAccess(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.ENABLE_CLIPBOARD_ACCESS.key(), v);
    }

    public static boolean isAllowDatabaseAccessOption(DatabaseAccessOptionEnum option) {
        return getBoolean(PluginSettingsKeyEnum.forDatabaseAccessOption(option));
    }

    public static void setAllowDatabaseAccessOption(DatabaseAccessOptionEnum option, boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.forDatabaseAccessOption(option).key(), v);
    }

    public static int getDatabaseRowLimit() {
        return getInt(PluginSettingsKeyEnum.DATABASE_ROW_LIMIT);
    }

    public static void setDatabaseRowLimit(int v) {
        prefs().putInt(PluginSettingsKeyEnum.DATABASE_ROW_LIMIT.key(), Math.max(1, v));
    }

    public static boolean isAllowInterAiComms() {
        return getBoolean(PluginSettingsKeyEnum.ALLOW_INTER_AI_COMMS);
    }

    public static void setAllowInterAiComms(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.ALLOW_INTER_AI_COMMS.key(), v);
    }

    public static boolean isAutoNotifyInbox() {
        return getBoolean(PluginSettingsKeyEnum.AUTO_NOTIFY_INBOX);
    }

    public static void setAutoNotifyInbox(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.AUTO_NOTIFY_INBOX.key(), v);
    }

    public static boolean isAllowImportantMessages() {
        return getBoolean(PluginSettingsKeyEnum.ALLOW_IMPORTANT_MESSAGES);
    }

    public static void setAllowImportantMessages(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.ALLOW_IMPORTANT_MESSAGES.key(), v);
    }

    public static int getMaxHistory() {
        return getInt(PluginSettingsKeyEnum.MAX_HISTORY);
    }

    public static void setMaxHistory(int v) {
        prefs().putInt(PluginSettingsKeyEnum.MAX_HISTORY.key(), v);
    }

    public static boolean isDebugJson() {
        return getBoolean(PluginSettingsKeyEnum.DEBUG_JSON);
    }

    public static void setDebugJson(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.DEBUG_JSON.key(), v);
    }

    public static boolean isDebugContext() {
        return getBoolean(PluginSettingsKeyEnum.DEBUG_CONTEXT);
    }

    public static void setDebugContext(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.DEBUG_CONTEXT.key(), v);
    }

    public static int getHookServerPort() {
        return getInt(PluginSettingsKeyEnum.MCP_SERVER_PORT);
    }

    public static void setHookServerPort(int v) {
        prefs().putInt(PluginSettingsKeyEnum.MCP_SERVER_PORT.key(), v);
    }

    public static boolean isLogToolUse() {
        return getBoolean(PluginSettingsKeyEnum.LOG_TOOL_USE);
    }

    public static void setLogToolUse(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.LOG_TOOL_USE.key(), v);
    }

    // ---- Inbox lifecycle ----
    public static int getInboxRetentionMinutes() {
        return getInt(PluginSettingsKeyEnum.INBOX_RETENTION_MINUTES);
    }

    public static void setInboxRetentionMinutes(int v) {
        prefs().putInt(PluginSettingsKeyEnum.INBOX_RETENTION_MINUTES.key(), Math.max(0, v));
    }

    public static int getInboxMaxSize() {
        return getInt(PluginSettingsKeyEnum.INBOX_MAX_SIZE);
    }

    public static void setInboxMaxSize(int v) {
        prefs().putInt(PluginSettingsKeyEnum.INBOX_MAX_SIZE.key(), Math.max(1, v));
    }

    // ---- New-session dialog memory ----
    public static AiTypeEnum getLastSessionAiType() {
        return AiTypeEnum.fromKey(getString(PluginSettingsKeyEnum.LAST_SESSION_AI_TYPE));
    }

    public static void setLastSessionAiType(AiTypeEnum type) {
        if (type != null) {
            prefs().put(PluginSettingsKeyEnum.LAST_SESSION_AI_TYPE.key(), type.key());
        }
    }

    // ---- Context broker global defaults ----
    public static String getContextTrimTrigger() {
        return getString(PluginSettingsKeyEnum.CONTEXT_TRIM_TRIGGER);
    }

    public static void setContextTrimTrigger(String v) {
        prefs().put(PluginSettingsKeyEnum.CONTEXT_TRIM_TRIGGER.key(), v);
    }

    public static String getContextTrimStrategy() {
        return getString(PluginSettingsKeyEnum.CONTEXT_TRIM_STRATEGY);
    }

    public static void setContextTrimStrategy(String v) {
        prefs().put(PluginSettingsKeyEnum.CONTEXT_TRIM_STRATEGY.key(), v);
    }

    public static int getContextTokenThreshold() {
        return getInt(PluginSettingsKeyEnum.CONTEXT_TOKEN_THRESHOLD);
    }

    public static void setContextTokenThreshold(int v) {
        prefs().putInt(PluginSettingsKeyEnum.CONTEXT_TOKEN_THRESHOLD.key(), v);
    }

    public static int getContextTrimTargetPercent() {
        return getInt(PluginSettingsKeyEnum.CONTEXT_TRIM_TARGET_PERCENT);
    }

    public static void setContextTrimTargetPercent(int v) {
        prefs().putInt(PluginSettingsKeyEnum.CONTEXT_TRIM_TARGET_PERCENT.key(), v);
    }

    public static int getContextMaxMessages() {
        return getInt(PluginSettingsKeyEnum.CONTEXT_MAX_MESSAGES);
    }

    public static void setContextMaxMessages(int v) {
        prefs().putInt(PluginSettingsKeyEnum.CONTEXT_MAX_MESSAGES.key(), v);
    }

    public static boolean isContextPersistOnClose() {
        return getBoolean(PluginSettingsKeyEnum.CONTEXT_PERSIST_ON_CLOSE);
    }

    public static void setContextPersistOnClose(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.CONTEXT_PERSIST_ON_CLOSE.key(), v);
    }

    private PluginSettings() {
    }
}
