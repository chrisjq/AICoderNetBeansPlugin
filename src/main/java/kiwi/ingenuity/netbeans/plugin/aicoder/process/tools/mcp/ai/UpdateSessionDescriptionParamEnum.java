package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the UpdateSessionDescriptionTool MCP tool, shared
 * between its schema() definition and handle() argument extraction so the two
 * cannot drift.
 */
public enum UpdateSessionDescriptionParamEnum {
    SESSION_ID(McpToolPropertyEnum.SESSION_ID),
    SECRET_KEY(McpToolPropertyEnum.SECRET_KEY),
    DESCRIPTION(McpToolPropertyEnum.DESCRIPTION);

    private final McpToolPropertyEnum property;

    UpdateSessionDescriptionParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
