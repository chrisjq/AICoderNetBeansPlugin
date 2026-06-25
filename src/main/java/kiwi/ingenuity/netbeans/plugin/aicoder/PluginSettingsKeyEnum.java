package kiwi.ingenuity.netbeans.plugin.aicoder;

public enum PluginSettingsKeyEnum {
    MAX_HISTORY("ai.maxHistory"),
    SAVE_HISTORY("ai.saveHistory"),
    DEBUG_JSON("ai.debugJson"),
    MCP_SERVER_PORT("ai.mcpPort"),
    DIFF_CONTEXT_LINES("ai.diffContextLines"),
    CHAT_FONT_SIZE("ai.chatFontSize"),
    AUTO_ACCEPT("ai.autoAccept"),
    LOG_TOOL_USE("ai.logToolUse"),
    AI_ENABLED_PREFIX("ai.enabled."),
    AI_MODEL_PREFIX("ai.model."),
    RESTRICT_TO_PROJECT("ai.session.restrictToProjectFiles"),
    ALLOW_INTER_AI_COMMS("ai.session.allowInterAiComms"),
    AUTO_NOTIFY_INBOX("ai.session.autoNotifyInbox"),
    ALLOW_IMPORTANT_MESSAGES("ai.session.allowImportantMessages"),
    INBOX_RETENTION_MINUTES("ai.inbox.retentionMinutes"),
    INBOX_MAX_SIZE("ai.inbox.maxSize"),
    LAST_SESSION_AI_TYPE("ai.session.lastAiType");

    private final String key;

    PluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
