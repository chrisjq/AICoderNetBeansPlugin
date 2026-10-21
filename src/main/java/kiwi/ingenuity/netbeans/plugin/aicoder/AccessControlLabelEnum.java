package kiwi.ingenuity.netbeans.plugin.aicoder;

public enum AccessControlLabelEnum {
    RESTRICT_TO_PROJECT_FILES("Restrict to open project files"),
    ALLOW_WEB_REQUESTS("Allow web requests"),
    ALLOW_WEB_REQUEST_GET("Allow GET"),
    ALLOW_WEB_REQUEST_POST("Allow POST"),
    ALLOW_WEB_REQUEST_PUT("Allow PUT"),
    ALLOW_WEB_REQUEST_PATCH("Allow PATCH"),
    ALLOW_WEB_REQUEST_DELETE("Allow DELETE"),
    ALLOW_WEB_REQUEST_HEAD("Allow HEAD"),
    ALLOW_WEB_REQUEST_OPTIONS("Allow OPTIONS"),
    ALLOW_WEB_REQUEST_HEADERS("Allow custom headers"),
    ALLOW_WEB_REQUEST_BODY("Allow request bodies"),
    ALLOW_WEB_REQUEST_LOCALHOST("Allow localhost destinations"),
    ALLOW_WEB_REQUEST_PRIVATE_NETWORKS("Allow private network destinations"),
    ALLOW_INTER_AI_COMMS("Allow inter-AI communication"),
    AUTO_NOTIFY_INBOX("Auto-notify on incoming messages"),
    ALLOW_IMPORTANT_MESSAGES("Allow important messages, interrupt this session"),
    ALLOW_IDLE_WATCHER_TIMER("Allow idle AI watcher timer"),
    MCP_STEERING("Auto-deny unsupported tool calls"),
    ALLOW_CLIPBOARD_ACCESS("Allow clipboard access"),
    ALLOW_DATABASE_ACCESS("Allow database access"),
    ALLOW_DATABASE_READ_ONLY("Read-only (no write support implemented)"),
    ALLOW_DATABASE_LIST_TABLES("Allow table listing"),
    ALLOW_DATABASE_SCHEMA("Allow table schema lookups"),
    ALLOW_DATABASE_SELECT("Allow SELECT on a table"),
    ALLOW_DATABASE_EXECUTE_SQL("Allow arbitrary SELECT queries"),
    ALLOW_GIT_ACCESS("Allow git access"),
    ALLOW_GIT_READ("Allow read-only git commands"),
    ALLOW_GIT_WRITE("Allow git commands that modify the repository");

    private final String label;

    AccessControlLabelEnum(String label) {
        this.label = label;
    }

    public String displayLabel() {
        return label;
    }
}
