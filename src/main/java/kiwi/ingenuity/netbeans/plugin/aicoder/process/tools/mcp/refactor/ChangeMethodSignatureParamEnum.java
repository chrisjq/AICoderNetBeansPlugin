package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

/**
 * Parameter-name keys for the ChangeMethodSignatureTool MCP tool, shared
 * between its schema() definition and handle() argument extraction so the two
 * cannot drift.
 */
public enum ChangeMethodSignatureParamEnum {
    PARAMETERS("parameters"),
    OVERLOAD_METHOD("overloadMethod"),
    FILE_PATH("filePath"),
    LINE("line"),
    METHOD_NAME("methodName"),
    RETURN_TYPE("returnType");

    private final String key;

    ChangeMethodSignatureParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
