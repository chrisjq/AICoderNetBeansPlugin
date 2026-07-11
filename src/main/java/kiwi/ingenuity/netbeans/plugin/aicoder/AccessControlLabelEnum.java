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
    ALLOW_IMPORTANT_MESSAGES("Allow important messages (interrupt receiving session)", "Allow important messages, interrupt this session");

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
