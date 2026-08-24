package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the FilterFileContentTool MCP tool, shared between its schema() definition and handle()
 * argument extraction so the two cannot drift.
 */
public enum FilterFileContentParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    PATTERN(McpToolPropertyEnum.PATTERN),
    IS_REGEX(McpToolPropertyEnum.IS_REGEX),
    CASE_SENSITIVE(McpToolPropertyEnum.CASE_SENSITIVE),
    CONTEXT_LINES(McpToolPropertyEnum.CONTEXT_LINES),
    MAX_MATCHES(McpToolPropertyEnum.MAX_MATCHES);

    private final McpToolPropertyEnum property;

    FilterFileContentParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
