package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;

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
    ALLOW_WEB_REQUESTS("allowWebRequests"),
    /**
     * Whether to allow GET requests
     */
    ALLOW_WEB_REQUEST_GET("allowWebRequestGet"),
    /**
     * Whether to allow POST requests
     */
    ALLOW_WEB_REQUEST_POST("allowWebRequestPost"),
    /**
     * Whether to allow PUT requests
     */
    ALLOW_WEB_REQUEST_PUT("allowWebRequestPut"),
    /**
     * Whether to allow PATCH requests
     */
    ALLOW_WEB_REQUEST_PATCH("allowWebRequestPatch"),
    /**
     * Whether to allow DELETE requests
     */
    ALLOW_WEB_REQUEST_DELETE("allowWebRequestDelete"),
    /**
     * Whether to allow HEAD requests
     */
    ALLOW_WEB_REQUEST_HEAD("allowWebRequestHead"),
    /**
     * Whether to allow OPTIONS requests
     */
    ALLOW_WEB_REQUEST_OPTIONS("allowWebRequestOptions"),
    /**
     * Whether to allow custom headers
     */
    ALLOW_WEB_REQUEST_HEADERS("allowWebRequestHeaders"),
    /**
     * Whether to allow request bodies
     */
    ALLOW_WEB_REQUEST_BODY("allowWebRequestBody"),
    /**
     * Whether to allow database access at all
     */
    ALLOW_DATABASE_ACCESS("allowDatabaseAccess"),
    /**
     * Read-only flag (no write support implemented; kept as a real setting
     * rather than hardcoded so enforcement stays honest)
     */
    ALLOW_DATABASE_READ_ONLY("allowDatabaseReadOnly"),
    /**
     * Whether to allow table listing
     */
    ALLOW_DATABASE_LIST_TABLES("allowDatabaseListTables"),
    /**
     * Whether to allow table schema lookups
     */
    ALLOW_DATABASE_SCHEMA("allowDatabaseSchema"),
    /**
     * Whether to allow table data reads
     */
    ALLOW_DATABASE_SELECT("allowDatabaseSelect"),
    /**
     * Whether to allow arbitrary SELECT query execution
     */
    ALLOW_DATABASE_EXECUTE_SQL("allowDatabaseExecuteSql"),
    /**
     * Maximum rows returned by a database read
     */
    DATABASE_ROW_LIMIT("databaseRowLimit");

    public static AiSessionSettingsKeyEnum forWebRequestAccessOption(WebRequestAccessOptionEnum option) {
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

    public static AiSessionSettingsKeyEnum forDatabaseAccessOption(DatabaseAccessOptionEnum option) {
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
