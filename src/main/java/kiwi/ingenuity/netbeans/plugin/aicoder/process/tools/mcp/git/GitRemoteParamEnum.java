package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GitRemoteTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GitRemoteParamEnum {
    ACTION(McpToolPropertyEnum.ACTION),
    NAME(McpToolPropertyEnum.NAME),
    URL(McpToolPropertyEnum.URL);

    private final McpToolPropertyEnum property;

    GitRemoteParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
