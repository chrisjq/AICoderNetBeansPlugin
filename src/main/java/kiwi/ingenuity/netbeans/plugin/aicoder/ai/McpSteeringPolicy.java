package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;

/**
 * Shared automatic steering policy: maps backend-specific tool categories to IDE MCP equivalents. Used by
 * backends that auto-deny native tool calls when MCP steering is enabled.
 *
 * <p>
 * <b>Why specific tool names, not "use MCP tools"?</b> An agent that is refused tells the user what happened.
 * If the refusal message names the replacement tool, the agent learns what to do next and can retry. If it
 * says only "use MCP tools", the agent has no idea which one and gives up. The replacement tool names are
 * drawn from {@link McpToolEnum} constants, not string literals — a tool rename updates this text
 * automatically rather than silently rotting it.
 *
 * <p>
 * <b>Why must the text read as automatic policy?</b> Some backends wrap this text with "The user rejected
 * this tool call. User feedback: <our text>", and if our text does not contradict that framing, the agent
 * turns around and tells the user they refused something they never saw. This is a real observed failure (see
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent}). Every category must state
 * that the refusal was automatic and the user was never asked.
 */
public final class McpSteeringPolicy {

    public enum Category {
        READ, PATH, URL, WRITE, SHELL, UNKNOWN
    }

    private static final String AUTOMATIC
            = "Refused automatically by this IDE — the user was not asked and did not reject this. ";

    /**
     * Returns steering feedback text naming the MCP tools to use instead of a native tool call. Text is built
     * from {@link McpToolEnum} constants so tool renames update automatically. Every category begins with the
     * automatic-policy framing and then names specific replacement tools.
     */
    public static String steeringFeedbackFor(Category category) {
        return switch (category) {
            case READ ->
                AUTOMATIC + "Use "
                + McpToolEnum.GET_FILE_CONTENT.toolName() + " to read a file, or "
                + McpToolEnum.SEARCH_IN_FILES.toolName() + " / "
                + McpToolEnum.SEARCH_TYPES.toolName() + " / "
                + McpToolEnum.GET_PROJECT_STRUCTURE.toolName() + " to locate one.";

            case PATH ->
                AUTOMATIC + "Use "
                + McpToolEnum.GET_PROJECT_STRUCTURE.toolName() + " to inspect the project tree, "
                + McpToolEnum.FIND_FILE.toolName() + " to locate a file, and "
                + McpToolEnum.GET_FILE_CONTENT.toolName() + " to read it.";

            case URL ->
                AUTOMATIC + "Use "
                + McpToolEnum.WEB_REQUEST.toolName() + " instead; it also honours this session's web-access settings.";

            case WRITE ->
                AUTOMATIC + "Use "
                + McpToolEnum.APPLY_EDIT.toolName() + " / "
                + McpToolEnum.WRITE_FILE.toolName() + " (or "
                + McpToolEnum.SAVE_FILE.toolName() + " for new files) instead; these route through the user's diff panel for review.";

            case SHELL ->
                AUTOMATIC + "Use the task-shaped MCP equivalents instead: "
                + McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " / "
                + McpToolEnum.BUILD_GRADLE_PROJECT.toolName() + " / "
                + McpToolEnum.BUILD_ANT_PROJECT.toolName() + " to build, "
                + McpToolEnum.RUN_MAVEN_TESTS.toolName() + " / "
                + McpToolEnum.RUN_GRADLE_TESTS.toolName() + " / "
                + McpToolEnum.RUN_ANT_TESTS.toolName() + " to test, the Git* tools for version control, "
                + McpToolEnum.DELETE_FILE.toolName() + " / "
                + McpToolEnum.COPY_FILE.toolName() + " / "
                + McpToolEnum.MOVE_FILE.toolName() + " for file management, or "
                + McpToolEnum.SEARCH_IN_FILES.toolName() + " to search.";

            case UNKNOWN ->
                AUTOMATIC + "Call " + McpToolEnum.GET_INSTRUCTIONS.toolName()
                + " for the complete list of tools this IDE provides.";
        };
    }

    private McpSteeringPolicy() {
    }
}
