package kiwi.ingenuity.netbeans.plugin.aicoder;

public enum AccessControlLabelEnum {
    RESTRICT_TO_PROJECT_FILES("Restrict AI to project files by default", "Restrict to project files"),
    ALLOW_WEB_REQUESTS("Allow AI web requests by default", "Allow web requests"),
    ALLOW_WEB_REQUEST_GET("Allow GET by default", "Allow GET"),
    ALLOW_WEB_REQUEST_POST("Allow POST by default", "Allow POST"),
    ALLOW_WEB_REQUEST_PUT("Allow PUT by default", "Allow PUT"),
    ALLOW_WEB_REQUEST_PATCH("Allow PATCH by default", "Allow PATCH"),
    ALLOW_WEB_REQUEST_DELETE("Allow DELETE by default", "Allow DELETE"),
    ALLOW_WEB_REQUEST_HEAD("Allow HEAD by default", "Allow HEAD"),
    ALLOW_WEB_REQUEST_OPTIONS("Allow OPTIONS by default", "Allow OPTIONS"),
    ALLOW_WEB_REQUEST_HEADERS("Allow custom headers by default", "Allow custom headers"),
    ALLOW_WEB_REQUEST_BODY("Allow request bodies by default", "Allow request bodies"),
    ALLOW_INTER_AI_COMMS("Allow inter-AI communication by default", "Allow inter-AI communication"),
    AUTO_NOTIFY_INBOX("Auto-notify AI sessions on inbox messages", "Auto-notify on incoming messages"),
    ALLOW_IMPORTANT_MESSAGES("Allow important messages (interrupt receiving session)", "Allow important messages, interrupt this session"),
    ALLOW_DATABASE_ACCESS("Allow AI database access by default", "Allow database access"),
    ALLOW_DATABASE_READ_ONLY("Read-only (no write support implemented)", "Read-only (no write support implemented)"),
    ALLOW_DATABASE_LIST_TABLES("Allow table listing by default", "Allow table listing"),
    ALLOW_DATABASE_SCHEMA("Allow table schema lookups by default", "Allow table schema lookups"),
    ALLOW_DATABASE_SELECT("Allow Select Query by default", "Allow Select Query"),
    ALLOW_DATABASE_EXECUTE_SQL("Allow arbitrary SELECT queries by default", "Allow arbitrary SELECT queries");

    private final String globalLabel;
    private final String sessionLabel;

    AccessControlLabelEnum(String globalLabel, String sessionLabel) {
        this.globalLabel = globalLabel;
        this.sessionLabel = sessionLabel;
    }

    public String globalLabel() {
        return globalLabel;
    }

    public String sessionLabel(boolean globalEnabled) {
        return sessionLabel + " (global: " + (globalEnabled ? "on" : "off") + ")";
    }

    public String displayLabel() {
        return sessionLabel;
    }
}
