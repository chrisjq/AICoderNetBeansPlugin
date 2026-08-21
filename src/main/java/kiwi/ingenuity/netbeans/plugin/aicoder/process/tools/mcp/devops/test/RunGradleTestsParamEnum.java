package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the RunGradleTestsTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum RunGradleTestsParamEnum {
    TEST_CLASS(McpToolPropertyEnum.TEST_CLASS),
    PROJECT_PATH(McpToolPropertyEnum.PROJECT_PATH);

    private final McpToolPropertyEnum property;

    RunGradleTestsParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
