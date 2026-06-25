package kiwi.ingenuity.netbeans.plugin.aicoder;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import org.openide.util.NbPreferences;

public final class PluginSettings {

    private static final Logger LOG = Logger.getLogger(PluginSettings.class.getName());

    public static final int DEFAULT_MCP_SERVER_PORT = 6969;

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

    public static boolean isSaveHistory() {
        return prefs().getBoolean(PluginSettingsKeyEnum.SAVE_HISTORY.key(), true);
    }

    public static void setSaveHistory(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.SAVE_HISTORY.key(), v);
    }

    public static int getDiffContextLines() {
        return prefs().getInt(PluginSettingsKeyEnum.DIFF_CONTEXT_LINES.key(), 3);
    }

    public static void setDiffContextLines(int v) {
        prefs().putInt(PluginSettingsKeyEnum.DIFF_CONTEXT_LINES.key(), v);
    }

    public static int getChatFontSize() {
        return prefs().getInt(PluginSettingsKeyEnum.CHAT_FONT_SIZE.key(), 13);
    }

    public static void setChatFontSize(int v) {
        prefs().putInt(PluginSettingsKeyEnum.CHAT_FONT_SIZE.key(), v);
    }

    public static boolean isAutoAccept() {
        return prefs().getBoolean(PluginSettingsKeyEnum.AUTO_ACCEPT.key(), false);
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
        return prefs().getBoolean(PluginSettingsKeyEnum.RESTRICT_TO_PROJECT.key(), true);
    }

    public static void setRestrictToProjectFiles(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.RESTRICT_TO_PROJECT.key(), v);
    }

    public static boolean isAllowInterAiComms() {
        return prefs().getBoolean(PluginSettingsKeyEnum.ALLOW_INTER_AI_COMMS.key(), false);
    }

    public static void setAllowInterAiComms(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.ALLOW_INTER_AI_COMMS.key(), v);
    }

    public static boolean isAutoNotifyInbox() {
        return prefs().getBoolean(PluginSettingsKeyEnum.AUTO_NOTIFY_INBOX.key(), false);
    }

    public static void setAutoNotifyInbox(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.AUTO_NOTIFY_INBOX.key(), v);
    }

    public static boolean isAllowImportantMessages() {
        return prefs().getBoolean(PluginSettingsKeyEnum.ALLOW_IMPORTANT_MESSAGES.key(), true);
    }

    public static void setAllowImportantMessages(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.ALLOW_IMPORTANT_MESSAGES.key(), v);
    }

    public static int getMaxHistory() {
        return prefs().getInt(PluginSettingsKeyEnum.MAX_HISTORY.key(), 200);
    }

    public static void setMaxHistory(int v) {
        prefs().putInt(PluginSettingsKeyEnum.MAX_HISTORY.key(), v);
    }

    public static boolean isDebugJson() {
        return prefs().getBoolean(PluginSettingsKeyEnum.DEBUG_JSON.key(), false);
    }

    public static void setDebugJson(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.DEBUG_JSON.key(), v);
    }

    public static int getHookServerPort() {
        return prefs().getInt(PluginSettingsKeyEnum.MCP_SERVER_PORT.key(), DEFAULT_MCP_SERVER_PORT);
    }

    public static void setHookServerPort(int v) {
        prefs().putInt(PluginSettingsKeyEnum.MCP_SERVER_PORT.key(), v);
    }

    public static boolean isLogToolUse() {
        return prefs().getBoolean(PluginSettingsKeyEnum.LOG_TOOL_USE.key(), false);
    }

    public static void setLogToolUse(boolean v) {
        prefs().putBoolean(PluginSettingsKeyEnum.LOG_TOOL_USE.key(), v);
    }

    // ---- Inbox lifecycle ----
    public static int getInboxRetentionMinutes() {
        return prefs().getInt(PluginSettingsKeyEnum.INBOX_RETENTION_MINUTES.key(), 60);
    }

    public static void setInboxRetentionMinutes(int v) {
        prefs().putInt(PluginSettingsKeyEnum.INBOX_RETENTION_MINUTES.key(), Math.max(0, v));
    }

    public static int getInboxMaxSize() {
        return prefs().getInt(PluginSettingsKeyEnum.INBOX_MAX_SIZE.key(), 1000);
    }

    public static void setInboxMaxSize(int v) {
        prefs().putInt(PluginSettingsKeyEnum.INBOX_MAX_SIZE.key(), Math.max(1, v));
    }

    // ---- New-session dialog memory ----
    public static AiTypeEnum getLastSessionAiType() {
        return AiTypeEnum.fromKey(prefs().get(PluginSettingsKeyEnum.LAST_SESSION_AI_TYPE.key(), null));
    }

    public static void setLastSessionAiType(AiTypeEnum type) {
        if (type != null) {
            prefs().put(PluginSettingsKeyEnum.LAST_SESSION_AI_TYPE.key(), type.key());
        }
    }

    private PluginSettings() {
    }
}
