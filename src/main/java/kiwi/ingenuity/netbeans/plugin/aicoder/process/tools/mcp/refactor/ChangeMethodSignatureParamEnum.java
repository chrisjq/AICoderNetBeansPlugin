package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the ChangeMethodSignatureTool MCP tool, shared
 * between its schema() definition and handle() argument extraction so the two
 * cannot drift.
 */
public enum ChangeMethodSignatureParamEnum {
    PARAMETERS(McpToolPropertyEnum.PARAMETERS),
    OVERLOAD_METHOD(McpToolPropertyEnum.OVERLOAD_METHOD),
    FILE_PATH(McpToolPropertyEnum.FILE_PATH),
    LINE(McpToolPropertyEnum.LINE),
    METHOD_NAME(McpToolPropertyEnum.METHOD_NAME),
    RETURN_TYPE(McpToolPropertyEnum.RETURN_TYPE),
    // Fields of each entry in the nested parameters array. The schema declares
    // them and handle() reads them back, so they must not drift.
    NAME(McpToolPropertyEnum.NAME),
    TYPE(McpToolPropertyEnum.TYPE),
    ORIGINAL_INDEX(McpToolPropertyEnum.ORIGINAL_INDEX),
    DEFAULT_VALUE(McpToolPropertyEnum.DEFAULT_VALUE);

    private final McpToolPropertyEnum property;

    ChangeMethodSignatureParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
