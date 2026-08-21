package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Shared parameter-name key used by every git MCP tool to let the caller
 * explicitly target a specific git repository or project root. This parameter
 * is required on every git tool: NetBeans' "main project" (or first open
 * project) notion is ambiguous — and can be plain wrong — whenever multiple
 * projects/repositories are open at the same time, or when the git repository
 * lives outside any open project's directory.
 */
public enum GitCommonParamEnum {
    PROJECT_PATH(McpToolPropertyEnum.PROJECT_PATH);

    private final McpToolPropertyEnum property;

    GitCommonParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
