package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the SearchInFilesTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum SearchInFilesParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    QUERY(McpToolPropertyEnum.QUERY),
    FILE_PATTERN(McpToolPropertyEnum.FILE_PATTERN),
    CASE_SENSITIVE(McpToolPropertyEnum.CASE_SENSITIVE),
    IS_REGEX(McpToolPropertyEnum.IS_REGEX);

    private final McpToolPropertyEnum property;

    SearchInFilesParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
