package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.userinput;

/**
 * Parameter-name keys for the AskUserQuestionTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum AskUserQuestionParamEnum {
    QUESTIONS("questions");

    private final String key;

    AskUserQuestionParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
