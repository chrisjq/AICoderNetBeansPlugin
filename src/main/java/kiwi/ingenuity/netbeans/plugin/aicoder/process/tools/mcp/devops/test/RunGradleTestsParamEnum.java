package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

/**
 * Parameter-name keys for the RunGradleTestsTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum RunGradleTestsParamEnum {
    TEST_CLASS("testClass"),
    PROJECT_PATH("projectPath");

    private final String key;

    RunGradleTestsParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
