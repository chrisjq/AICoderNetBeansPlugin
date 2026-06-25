package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.navigate;

/**
 * Parameter-name keys for the NavigateToLineTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum NavigateToLineParamEnum {
    FILE_PATH("filePath"),
    LINE("line");

    private final String key;

    NavigateToLineParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
