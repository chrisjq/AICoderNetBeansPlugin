package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

/**
 * Parameter-name keys for the BuildMavenProjectTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum BuildMavenProjectParamEnum {
    PROJECT_PATH("projectPath");

    private final String key;

    BuildMavenProjectParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
