package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the FindUsagesTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum FindUsagesParamEnum {
    CLASS_NAME(McpToolPropertyEnum.CLASS_NAME),
    MEMBER_NAME(McpToolPropertyEnum.MEMBER_NAME),
    FIND_SUBCLASSES(McpToolPropertyEnum.FIND_SUBCLASSES),
    DIRECT_SUBCLASSES_ONLY(McpToolPropertyEnum.DIRECT_SUBCLASSES_ONLY),
    SEARCH_IN_COMMENTS(McpToolPropertyEnum.SEARCH_IN_COMMENTS);

    private final McpToolPropertyEnum property;

    FindUsagesParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
