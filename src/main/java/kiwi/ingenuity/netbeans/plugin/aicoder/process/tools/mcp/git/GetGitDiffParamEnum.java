package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

/**
 * Parameter-name keys for the GetGitDiffTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum GetGitDiffParamEnum {
    STAGED("staged");

    private final String key;

    GetGitDiffParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
