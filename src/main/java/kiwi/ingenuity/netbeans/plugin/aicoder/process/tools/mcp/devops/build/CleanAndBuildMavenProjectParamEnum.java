package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the CleanAndBuildMavenProjectTool MCP tool, shared between its schema() definition and
 * handle() argument extraction so the two cannot drift.
 */
public enum CleanAndBuildMavenProjectParamEnum {
    PROJECT_PATH(McpToolPropertyEnum.PROJECT_PATH),
    GOALS(McpToolPropertyEnum.GOALS),
    PROJECT_LIST(McpToolPropertyEnum.PROJECT_LIST),
    ALSO_MAKE(McpToolPropertyEnum.ALSO_MAKE),
    RESUME_FROM(McpToolPropertyEnum.RESUME_FROM),
    SKIP_TESTS(McpToolPropertyEnum.SKIP_TESTS),
    OFFLINE(McpToolPropertyEnum.OFFLINE),
    UPDATE_SNAPSHOTS(McpToolPropertyEnum.UPDATE_SNAPSHOTS),
    PROFILES(McpToolPropertyEnum.PROFILES),
    PROPERTIES(McpToolPropertyEnum.PROPERTIES),
    THREADS(McpToolPropertyEnum.THREADS),
    FAIL_AT_END(McpToolPropertyEnum.FAIL_AT_END);

    private final McpToolPropertyEnum property;

    CleanAndBuildMavenProjectParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
