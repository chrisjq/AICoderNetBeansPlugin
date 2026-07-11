package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

/**
 * Enumerates configuration keys for AiSessionSettings. Defines all JSON config
 * keys used when persisting and loading session settings.
 */
public enum AiSessionSettingsKeyEnum {
    /**
     * Maximum conversation history size
     */
    MAX_HISTORY("maxHistory"),
    /**
     * Whether to restrict searches to project files
     */
    RESTRICT_TO_PROJECT_FILES("restrictToProjectFiles"),
    /**
     * Whether to allow inter-AI communications
     */
    ALLOW_INTER_AI_COMMS("allowInterAiComms"),
    /**
     * Whether to auto-notify inbox of new messages
     */
    AUTO_NOTIFY_INBOX("autoNotifyInbox"),
    /**
     * Whether to allow important messages
     */
    ALLOW_IMPORTANT_MESSAGES("allowImportantMessages"),
    /**
     * Custom instructions for the session
     */
    SESSION_INSTRUCTIONS("sessionInstructions"),
    /**
     * Whether to auto-accept changes
     */
    AUTO_ACCEPT("autoAccept"),
    /**
     * Whether to allow web requests
     */
    ALLOW_WEB_REQUESTS("allowWebRequests");

    /**
     * The JSON key string
     */
    private final String key;

    AiSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
