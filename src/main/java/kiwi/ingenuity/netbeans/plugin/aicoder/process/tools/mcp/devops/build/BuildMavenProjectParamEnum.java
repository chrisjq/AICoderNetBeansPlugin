package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the BuildMavenProjectTool MCP tool, shared between
 * its schema() definition and handle() argument extraction so the two cannot
 * drift.
 */
public enum BuildMavenProjectParamEnum {
    PROJECT_PATH(McpToolPropertyEnum.PROJECT_PATH);

    private final McpToolPropertyEnum property;

    BuildMavenProjectParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
