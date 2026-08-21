package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

/**
 * MCP JSON-RPC protocol keys served by this plugin. Values are an external
 * contract and must never change.
 */
public enum McpProtocolKeyEnum {
    /**
     * JSON-RPC request identifier.
     */
    ID("id"),
    /**
     * JSON-RPC method name.
     */
    METHOD("method"),
    /**
     * JSON-RPC parameters.
     */
    PARAMS("params"),
    /**
     * MCP protocol version.
     */
    PROTOCOL_VERSION("protocolVersion"),
    /**
     * MCP tools capability or result.
     */
    TOOLS("tools"),
    /**
     * MCP capability object.
     */
    CAPABILITIES("capabilities"),
    /**
     * MCP tool or server name.
     */
    NAME("name"),
    /**
     * Server version.
     */
    VERSION("version"),
    /**
     * Server information.
     */
    SERVER_INFO("serverInfo"),
    /**
     * Server instructions.
     */
    INSTRUCTIONS("instructions"),
    /**
     * Tool-call argument object.
     */
    ARGUMENTS("arguments");

    private final String key;

    McpProtocolKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
