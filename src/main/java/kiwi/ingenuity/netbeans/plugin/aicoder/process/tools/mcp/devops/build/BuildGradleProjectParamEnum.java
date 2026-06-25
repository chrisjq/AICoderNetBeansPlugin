package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

/**
 * Parameter-name keys for the BuildGradleProjectTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum BuildGradleProjectParamEnum {
    PROJECT_PATH("projectPath");

    private final String key;

    BuildGradleProjectParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
