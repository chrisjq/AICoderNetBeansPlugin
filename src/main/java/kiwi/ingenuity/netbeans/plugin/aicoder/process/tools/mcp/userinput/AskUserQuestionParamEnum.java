package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.userinput;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Parameter-name keys for the AskUserQuestionTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum AskUserQuestionParamEnum {
    QUESTIONS(McpToolPropertyEnum.QUESTIONS),
    // Fields of the nested question / option objects. AskUserQuestionTool
    // declares them and QuestionPanel and NotificationUtil read them straight
    // back out, so they were three separate copies of the same spelling with
    // nothing tying them together.
    QUESTION(McpToolPropertyEnum.QUESTION),
    HEADER(McpToolPropertyEnum.HEADER),
    OPTIONS(McpToolPropertyEnum.OPTIONS),
    MULTI_SELECT(McpToolPropertyEnum.MULTI_SELECT),
    LABEL(McpToolPropertyEnum.LABEL),
    DESCRIPTION(McpToolPropertyEnum.DESCRIPTION);

    private final McpToolPropertyEnum property;

    AskUserQuestionParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
