package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys shared by FindFileTool's schema and handler.
 */
public enum FindFileParamEnum {
    DIRECTORY_PATH(McpToolPropertyEnum.DIRECTORY_PATH),
    PATTERN(McpToolPropertyEnum.PATTERN),
    IS_REGEX(McpToolPropertyEnum.IS_REGEX),
    CASE_SENSITIVE(McpToolPropertyEnum.CASE_SENSITIVE),
    MAX_MATCHES(McpToolPropertyEnum.MAX_MATCHES);

    private final McpToolPropertyEnum property;

    FindFileParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
