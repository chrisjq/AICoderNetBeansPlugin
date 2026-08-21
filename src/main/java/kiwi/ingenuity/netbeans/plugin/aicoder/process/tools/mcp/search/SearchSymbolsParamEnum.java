package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the SearchSymbolsTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum SearchSymbolsParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    NAME(McpToolPropertyEnum.NAME),
    KIND(McpToolPropertyEnum.KIND),
    INCLUDE_DEPS(McpToolPropertyEnum.INCLUDE_DEPS);

    private final McpToolPropertyEnum property;

    SearchSymbolsParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
