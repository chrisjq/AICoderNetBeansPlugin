package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

/**
 * Parameter-name keys for the DownloadMavenJavadocTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum DownloadMavenJavadocParamEnum {
    PROJECT_PATH("projectPath");

    private final String key;

    DownloadMavenJavadocParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
