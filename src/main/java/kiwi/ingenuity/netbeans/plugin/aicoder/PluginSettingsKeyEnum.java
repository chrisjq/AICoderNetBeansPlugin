package kiwi.ingenuity.netbeans.plugin.aicoder;

public enum PluginSettingsKeyEnum {
    MAX_HISTORY("ai.maxHistory", 200),
    SAVE_HISTORY("ai.saveHistory", true),
    SAVE_SESSION_ON_CLOSE_IF_TICKED("ai.saveSessionOnCloseIfTicked", false),
    DEBUG_JSON("ai.debugJson", false),
    MCP_SERVER_PORT("ai.mcpPort", 49167),
    DIFF_CONTEXT_LINES("ai.diffContextLines", 3),
    CHAT_FONT_SIZE("ai.chatFontSize", 13),
    AUTO_ACCEPT("ai.autoAccept", false),
    LOG_TOOL_USE("ai.logToolUse", false),
    AI_ENABLED_PREFIX("ai.enabled."),
    AI_MODEL_PREFIX("ai.model."),
    RESTRICT_TO_PROJECT("ai.session.restrictToProjectFiles", true),
    ALLOW_INTER_AI_COMMS("ai.session.allowInterAiComms", false),
    ALLOW_WEB_REQUESTS("ai.session.allowWebRequests", true),
    ALLOW_WEB_REQUEST_GET("ai.session.allowWebRequests.get", true),
    ALLOW_WEB_REQUEST_POST("ai.session.allowWebRequests.post", false),
    ALLOW_WEB_REQUEST_PUT("ai.session.allowWebRequests.put", false),
    ALLOW_WEB_REQUEST_PATCH("ai.session.allowWebRequests.patch", false),
    ALLOW_WEB_REQUEST_DELETE("ai.session.allowWebRequests.delete", false),
    ALLOW_WEB_REQUEST_HEAD("ai.session.allowWebRequests.head", false),
    ALLOW_WEB_REQUEST_OPTIONS("ai.session.allowWebRequests.options", false),
    ALLOW_WEB_REQUEST_HEADERS("ai.session.allowWebRequests.headers", false),
    ALLOW_WEB_REQUEST_BODY("ai.session.allowWebRequests.body", false),
    AUTO_NOTIFY_INBOX("ai.session.autoNotifyInbox", false),
    ALLOW_IMPORTANT_MESSAGES("ai.session.allowImportantMessages", true),
    INBOX_RETENTION_MINUTES("ai.inbox.retentionMinutes", 60),
    INBOX_MAX_SIZE("ai.inbox.maxSize", 1000),
    LAST_SESSION_AI_TYPE("ai.session.lastAiType", null),
    ALLOW_DATABASE_ACCESS("ai.session.allowDatabaseAccess", false),
    ALLOW_DATABASE_READ_ONLY("ai.session.allowDatabaseAccess.readOnly", true),
    ALLOW_DATABASE_LIST_TABLES("ai.session.allowDatabaseAccess.listTables", false),
    ALLOW_DATABASE_SCHEMA("ai.session.allowDatabaseAccess.schema", false),
    ALLOW_DATABASE_SELECT("ai.session.allowDatabaseAccess.select", false),
    ALLOW_DATABASE_EXECUTE_SQL("ai.session.allowDatabaseAccess.executeSql", false),
    DATABASE_ROW_LIMIT("ai.session.databaseRowLimit", 25),
    ENABLE_CLIPBOARD_ACCESS("ai.session.enableClipboardAccess", false);

    public static PluginSettingsKeyEnum forWebRequestAccessOption(WebRequestAccessOptionEnum option) {
        return switch (option) {
            case GET ->
                ALLOW_WEB_REQUEST_GET;
            case POST ->
                ALLOW_WEB_REQUEST_POST;
            case PUT ->
                ALLOW_WEB_REQUEST_PUT;
            case PATCH ->
                ALLOW_WEB_REQUEST_PATCH;
            case DELETE ->
                ALLOW_WEB_REQUEST_DELETE;
            case HEAD ->
                ALLOW_WEB_REQUEST_HEAD;
            case OPTIONS ->
                ALLOW_WEB_REQUEST_OPTIONS;
            case HEADERS ->
                ALLOW_WEB_REQUEST_HEADERS;
            case BODY ->
                ALLOW_WEB_REQUEST_BODY;
        };
    }

    public static PluginSettingsKeyEnum forDatabaseAccessOption(DatabaseAccessOptionEnum option) {
        return switch (option) {
            case READ_ONLY ->
                ALLOW_DATABASE_READ_ONLY;
            case LIST_TABLES ->
                ALLOW_DATABASE_LIST_TABLES;
            case SCHEMA ->
                ALLOW_DATABASE_SCHEMA;
            case SELECT ->
                ALLOW_DATABASE_SELECT;
            case EXECUTE_SQL ->
                ALLOW_DATABASE_EXECUTE_SQL;
        };
    }

    private final String key;
    private final Object defaultValue;
    private final boolean hasDefaultValue;

    PluginSettingsKeyEnum(String key) {
        this(key, null, false);
    }

    PluginSettingsKeyEnum(String key, Object defaultValue) {
        this(key, defaultValue, true);
    }

    PluginSettingsKeyEnum(String key, Object defaultValue, boolean hasDefaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.hasDefaultValue = hasDefaultValue;
    }

    public String key() {
        return key;
    }

    public boolean hasDefaultValue() {
        return hasDefaultValue;
    }

    public Object defaultValue() {
        return hasDefaultValue ? defaultValue : null;
    }

    public boolean defaultBoolean() {
        return defaultValue(Boolean.class);
    }

    public int defaultInt() {
        return defaultValue(Integer.class);
    }

    public double defaultDouble() {
        return defaultValue(Double.class);
    }

    public String defaultString() {
        return defaultValue(String.class);
    }

    private <T> T defaultValue(Class<T> expectedType) {
        if (!hasDefaultValue) {
            throw new IllegalStateException(name() + " does not define a default value");
        }
        if (defaultValue == null) {
            if (expectedType == String.class) {
                return expectedType.cast(defaultValue);
            }
            throw new IllegalStateException(name() + " default value is null");
        }
        if (!expectedType.isInstance(defaultValue)) {
            throw new IllegalStateException(name() + " default value is a "
                    + defaultValue.getClass().getSimpleName() + ", not a "
                    + expectedType.getSimpleName());
        }
        return expectedType.cast(defaultValue);
    }
}
