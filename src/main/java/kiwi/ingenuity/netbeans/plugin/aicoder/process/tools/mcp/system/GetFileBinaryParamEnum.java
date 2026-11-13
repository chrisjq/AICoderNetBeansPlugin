package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the GetFileBinary MCP tool, shared between its schema() definition and handleResult()
 * argument extraction so the two cannot drift.
 */
public enum GetFileBinaryParamEnum {
    FILE_PATH(McpToolPropertyEnum.FILE_PATH.key()),
    MODE("mode"),
    OFFSET("offset"),
    LENGTH("length"),
    ENTRY("entry");

    private final String key;

    GetFileBinaryParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
