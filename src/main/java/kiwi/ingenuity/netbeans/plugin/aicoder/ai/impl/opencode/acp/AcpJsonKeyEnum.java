package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

/**
 * JSON object field names used by OpenCode's Agent Client Protocol wire
 * messages. Values are the exact ACP field names and must not be changed.
 */
public enum AcpJsonKeyEnum {
    JSONRPC("jsonrpc", "JSON-RPC protocol version field"),
    ID("id", "Request, response, or configuration option identifier"),
    METHOD("method", "JSON-RPC method field"),
    PARAMS("params", "JSON-RPC request parameters"),
    ERROR("error", "JSON-RPC error object"),
    CODE("code", "JSON-RPC error code"),
    MESSAGE("message", "Error or message field"),
    RESULT("result", "JSON-RPC result object"),
    SESSION_ID("sessionId", "ACP session identifier"),
    UPDATE("update", "Session update object"),
    LOCATIONS("locations", "Tool-call source locations"),
    PATH("path", "File path"),
    CONTENT("content", "Content or content block"),
    RAW_INPUT("rawInput", "Raw tool input object"),
    FILEPATH("filepath", "Raw tool input file path"),
    COMMAND("command", "Raw tool input command"),
    TYPE("type", "Object or content type"),
    NEW_TEXT("newText", "Full proposed file content"),
    SESSION_UPDATE("sessionUpdate", "Session update kind"),
    USED("used", "Used context amount"),
    SIZE("size", "Context size"),
    MESSAGE_ID("messageId", "Agent message identifier"),
    TEXT("text", "Text content"),
    TITLE("title", "Tool-call title"),
    KIND("kind", "Tool-call kind"),
    TOOL_CALL("toolCall", "Permission request tool call"),
    OUTCOME("outcome", "Permission outcome object or selection"),
    OPTION_ID("optionId", "Selected permission option"),
    PERMISSION("permission", "Permission configuration object"),
    EDIT("edit", "Edit permission configuration"),
    BASH("bash", "Bash permission configuration"),
    EXTERNAL_DIRECTORY("external_directory", "External-directory permission configuration"),
    TASK("task", "Sub-agent permission configuration"),
    FS("fs", "Filesystem capability object"),
    READ_TEXT_FILE("readTextFile", "Read-file capability"),
    WRITE_TEXT_FILE("writeTextFile", "Write-file capability"),
    TERMINAL("terminal", "Terminal capability"),
    NAME("name", "Client or MCP server name"),
    VERSION("version", "Client version"),
    PROTOCOL_VERSION("protocolVersion", "ACP protocol version"),
    CLIENT_CAPABILITIES("clientCapabilities", "ACP client capabilities"),
    CLIENT_INFO("clientInfo", "ACP client information"),
    CWD("cwd", "Session working directory"),
    MCP_SERVERS("mcpServers", "MCP server configurations"),
    PROMPT("prompt", "Session prompt content"),
    URL("url", "MCP server endpoint"),
    HEADERS("headers", "MCP server HTTP headers"),
    CONFIG_OPTIONS("configOptions", "ACP configuration options"),
    CONFIG_ID("configId", "Configuration option identifier"),
    CURRENT_VALUE("currentValue", "Current configuration value"),
    OPTIONS("options", "Configuration option choices"),
    VALUE("value", "Configuration or option value");

    private final String key;
    private final String description;

    AcpJsonKeyEnum(String key, String description) {
        this.key = key;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String description() {
        return description;
    }
}
