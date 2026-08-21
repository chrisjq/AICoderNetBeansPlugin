package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GetJavadocTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetJavadocParamEnum {
    CLASS_NAME(McpToolPropertyEnum.CLASS_NAME),
    MEMBER_NAME(McpToolPropertyEnum.MEMBER_NAME);

    private final McpToolPropertyEnum property;

    GetJavadocParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
