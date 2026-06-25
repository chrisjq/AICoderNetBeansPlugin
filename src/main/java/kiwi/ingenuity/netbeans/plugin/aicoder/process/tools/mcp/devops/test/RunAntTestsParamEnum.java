package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

/**
 * Parameter-name keys for the RunAntTestsTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum RunAntTestsParamEnum {
    TEST_CLASS("testClass"),
    PROJECT_PATH("projectPath");

    private final String key;

    RunAntTestsParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
