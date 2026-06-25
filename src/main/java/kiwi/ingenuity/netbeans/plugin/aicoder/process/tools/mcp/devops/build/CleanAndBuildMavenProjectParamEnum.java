package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

/**
 * Parameter-name keys for the CleanAndBuildMavenProjectTool MCP tool, shared
 * between its schema() definition and handle() argument extraction so the two
 * cannot drift.
 */
public enum CleanAndBuildMavenProjectParamEnum {
    PROJECT_PATH("projectPath");

    private final String key;

    CleanAndBuildMavenProjectParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
