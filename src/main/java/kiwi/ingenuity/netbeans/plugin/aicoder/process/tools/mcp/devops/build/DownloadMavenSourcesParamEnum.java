package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

/**
 * Parameter-name keys for the DownloadMavenSourcesTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum DownloadMavenSourcesParamEnum {
    PROJECT_PATH("projectPath");

    private final String key;

    DownloadMavenSourcesParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
