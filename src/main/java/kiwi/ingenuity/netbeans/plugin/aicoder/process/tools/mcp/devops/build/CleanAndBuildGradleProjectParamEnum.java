package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the CleanAndBuildGradleProjectTool MCP tool, shared between its schema() definition and
 * handle() argument extraction so the two cannot drift.
 */
public enum CleanAndBuildGradleProjectParamEnum {
    PROJECT_PATH(McpToolPropertyEnum.PROJECT_PATH),
    TASKS(McpToolPropertyEnum.TASKS),
    SKIP_TESTS(McpToolPropertyEnum.SKIP_TESTS),
    OFFLINE(McpToolPropertyEnum.OFFLINE),
    REFRESH_DEPENDENCIES(McpToolPropertyEnum.REFRESH_DEPENDENCIES),
    PROPERTIES(McpToolPropertyEnum.PROPERTIES),
    SYSTEM_PROPERTIES(McpToolPropertyEnum.SYSTEM_PROPERTIES),
    PARALLEL(McpToolPropertyEnum.PARALLEL),
    CONTINUE_ON_FAILURE(McpToolPropertyEnum.CONTINUE_ON_FAILURE);

    private final McpToolPropertyEnum property;

    CleanAndBuildGradleProjectParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
