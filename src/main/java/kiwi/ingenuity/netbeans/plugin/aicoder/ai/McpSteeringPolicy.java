package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.HiddenToolTextFilter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * Shared automatic steering policy: maps backend-specific tool categories to IDE MCP equivalents. Used by backends that
 * auto-deny native tool calls when MCP steering is enabled.
 *
 * <p>
 * <b>Why specific tool names, not "use MCP tools"?</b> An agent that is refused tells the user what happened. If the
 * refusal message names the replacement tool, the agent learns what to do next and can retry. If it says only "use MCP
 * tools", the agent has no idea which one and gives up. The replacement tool names are drawn from {@link McpToolEnum}
 * constants, not string literals — a tool rename updates this text automatically rather than silently rotting it.
 *
 * <p>
 * <b>Why must the text read as automatic policy?</b> Some backends wrap this text with "The user rejected this tool
 * call. User feedback: <our text>", and if our text does not contradict that framing, the agent turns around and tells
 * the user they refused something they never saw. This is a real observed failure (see
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent}). Every category must state that the
 * refusal was automatic and the user was never asked.
 */
public final class McpSteeringPolicy {

    public enum Category {
        READ,
        PATH,
        URL,
        WRITE,
        SHELL,
        UNKNOWN
    }

    private static final String AUTOMATIC
            = "Refused automatically by this IDE — the user was not asked and did not reject this. ";

    /**
     * Returns steering feedback text naming the MCP tools to use instead of a native tool call. Text is built from
     * {@link McpToolEnum} constants so tool renames update automatically. Every category begins with the
     * automatic-policy framing and then names specific replacement tools.
     */
    public static String steeringFeedbackFor(Category category) {
        return steeringFeedbackFor(category, Set.of());
    }

    /**
     * Returns steering feedback that names only tools enabled for this session.
     */
    public static String steeringFeedbackFor(Category category, Set<McpToolEnum> hidden) {
        Set<McpToolEnum> suppressed = hidden == null ? Set.of() : hidden;
        if (!mentionsAnyHiddenTool(category, suppressed)) {
            return legacyFeedbackFor(category);
        }
        List<String> clauses = new ArrayList<>();
        switch (category) {
            case READ -> {
                addClause(clauses, visible(suppressed, McpToolEnum.GET_FILE_CONTENT), "to read a file");
                addClause(clauses, visible(suppressed, McpToolEnum.SEARCH_IN_FILES, McpToolEnum.SEARCH_TYPES,
                                           McpToolEnum.GET_PROJECT_STRUCTURE), "to locate one");
            }
            case PATH -> {
                addClause(clauses, visible(suppressed, McpToolEnum.GET_PROJECT_STRUCTURE), "to inspect the project tree");
                addClause(clauses, visible(suppressed, McpToolEnum.FIND_FILE), "to locate a file");
                addClause(clauses, visible(suppressed, McpToolEnum.GET_FILE_CONTENT), "to read it");
            }
            case URL ->
                addClause(clauses, visible(suppressed, McpToolEnum.WEB_REQUEST),
                          "instead; it also honours this session's web-access settings");
            case WRITE ->
                addClause(clauses, visible(suppressed, McpToolEnum.APPLY_EDIT, McpToolEnum.WRITE_FILE,
                                           McpToolEnum.SAVE_FILE),
                          "instead; these route through the user's diff panel for review");
            case SHELL -> {
                addClause(clauses, visible(suppressed, McpToolEnum.BUILD_MAVEN_PROJECT, McpToolEnum.BUILD_GRADLE_PROJECT,
                                           McpToolEnum.BUILD_ANT_PROJECT), "to build");
                addClause(clauses, visible(suppressed, McpToolEnum.RUN_MAVEN_TESTS, McpToolEnum.RUN_GRADLE_TESTS,
                                           McpToolEnum.RUN_ANT_TESTS), "to test");
                if (McpToolGroupEnum.GIT_TOOLS.stream().anyMatch(t -> !suppressed.contains(t))) {
                    clauses.add("the Git* tools for version control");
                }
                addClause(clauses, visible(suppressed, McpToolEnum.DELETE_FILE, McpToolEnum.COPY_FILE,
                                           McpToolEnum.MOVE_FILE), "for file management");
                addClause(clauses, visible(suppressed, McpToolEnum.CREATE_DIRECTORY, McpToolEnum.DELETE_DIRECTORY),
                          "for directories");
                addClause(clauses, visible(suppressed, McpToolEnum.SEARCH_IN_FILES), "to search");
            }
            case UNKNOWN ->
                addClause(clauses, visible(suppressed, McpToolEnum.GET_INSTRUCTIONS),
                          "for the complete list of tools this IDE provides");
        }
        return AUTOMATIC + (clauses.isEmpty()
                            ? "No IDE replacement for this action is enabled in this session; ask the user if you need it."
                            : "Use " + String.join(", ", clauses) + ".");
    }

    private static String legacyFeedbackFor(Category category) {
        return switch (category) {
            case READ ->
                AUTOMATIC + "Use " + McpToolEnum.GET_FILE_CONTENT.toolName() + " to read a file, or "
                + McpToolEnum.SEARCH_IN_FILES.toolName() + " / " + McpToolEnum.SEARCH_TYPES.toolName() + " / "
                + McpToolEnum.GET_PROJECT_STRUCTURE.toolName() + " to locate one.";
            case PATH ->
                AUTOMATIC + "Use " + McpToolEnum.GET_PROJECT_STRUCTURE.toolName()
                + " to inspect the project tree, " + McpToolEnum.FIND_FILE.toolName() + " to locate a file, and "
                + McpToolEnum.GET_FILE_CONTENT.toolName() + " to read it.";
            case URL ->
                AUTOMATIC + "Use " + McpToolEnum.WEB_REQUEST.toolName()
                + " instead; it also honours this session's web-access settings.";
            case WRITE ->
                AUTOMATIC + "Use " + McpToolEnum.APPLY_EDIT.toolName() + " / "
                + McpToolEnum.WRITE_FILE.toolName() + " (or " + McpToolEnum.SAVE_FILE.toolName()
                + " for new files) instead; these route through the user's diff panel for review.";
            case SHELL ->
                AUTOMATIC + "Use the task-shaped MCP equivalents instead: "
                + McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " / " + McpToolEnum.BUILD_GRADLE_PROJECT.toolName()
                + " / " + McpToolEnum.BUILD_ANT_PROJECT.toolName() + " to build, "
                + McpToolEnum.RUN_MAVEN_TESTS.toolName() + " / " + McpToolEnum.RUN_GRADLE_TESTS.toolName() + " / "
                + McpToolEnum.RUN_ANT_TESTS.toolName() + " to test, the Git* tools for version control, "
                + McpToolEnum.DELETE_FILE.toolName() + " / " + McpToolEnum.COPY_FILE.toolName() + " / "
                + McpToolEnum.MOVE_FILE.toolName() + " for file management, "
                + McpToolEnum.CREATE_DIRECTORY.toolName() + " / " + McpToolEnum.DELETE_DIRECTORY.toolName()
                + " for directories, or " + McpToolEnum.SEARCH_IN_FILES.toolName() + " to search.";
            case UNKNOWN ->
                AUTOMATIC + "Call " + McpToolEnum.GET_INSTRUCTIONS.toolName()
                + " for the complete list of tools this IDE provides.";
        };
    }

    /**
     * The tools each category's replacement text names — exactly the ones whose being hidden changes that text. Kept
     * here, beside {@link #legacyFeedbackFor}, so the set and the wording cannot drift apart: adding a tool to the text
     * without adding it here would silently leave the full-hidden wording in use. SHELL includes
     * {@link McpToolGroupEnum#GIT_TOOLS} because its "the Git* tools" clause depends on them.
     */
    static Set<McpToolEnum> replacementTools(Category category) {
        return switch (category) {
            case READ ->
                Set.of(McpToolEnum.GET_FILE_CONTENT, McpToolEnum.SEARCH_IN_FILES, McpToolEnum.SEARCH_TYPES,
                       McpToolEnum.GET_PROJECT_STRUCTURE);
            case PATH ->
                Set.of(McpToolEnum.GET_PROJECT_STRUCTURE, McpToolEnum.FIND_FILE, McpToolEnum.GET_FILE_CONTENT);
            case URL ->
                Set.of(McpToolEnum.WEB_REQUEST);
            case WRITE ->
                Set.of(McpToolEnum.APPLY_EDIT, McpToolEnum.WRITE_FILE, McpToolEnum.SAVE_FILE);
            case SHELL -> {
                Set<McpToolEnum> tools = new HashSet<>(Set.of(
                        McpToolEnum.BUILD_MAVEN_PROJECT, McpToolEnum.BUILD_GRADLE_PROJECT, McpToolEnum.BUILD_ANT_PROJECT,
                        McpToolEnum.RUN_MAVEN_TESTS, McpToolEnum.RUN_GRADLE_TESTS, McpToolEnum.RUN_ANT_TESTS,
                        McpToolEnum.DELETE_FILE, McpToolEnum.COPY_FILE, McpToolEnum.MOVE_FILE,
                        McpToolEnum.CREATE_DIRECTORY, McpToolEnum.DELETE_DIRECTORY, McpToolEnum.SEARCH_IN_FILES));
                tools.addAll(McpToolGroupEnum.GIT_TOOLS);
                yield tools;
            }
            case UNKNOWN ->
                Set.of(McpToolEnum.GET_INSTRUCTIONS);
        };
    }

    /**
     * Whether the category's own replacement text would change for a session hiding {@code hidden}. False when none of
     * the tools the text names is hidden, in which case the established wording is used unchanged.
     */
    private static boolean mentionsAnyHiddenTool(Category category, Set<McpToolEnum> hidden) {
        return replacementTools(category).stream().anyMatch(hidden::contains);
    }

    private static List<String> visible(Set<McpToolEnum> hidden, McpToolEnum... tools) {
        return Arrays.stream(tools).filter(tool -> !hidden.contains(tool)).map(McpToolEnum::toolName).toList();
    }

    private static void addClause(List<String> clauses, List<String> tools, String suffix) {
        if (!tools.isEmpty()) {
            clauses.add(String.join(" / ", tools) + " " + suffix);
        }
    }

    public static String steeringFeedbackFor(Category category, AbstractAiSession session) {
        if (session == null || session.getSettings() == null) {
            return steeringFeedbackFor(category, Set.of());
        }
        Map<McpToolEnum, McpToolInterface> all = session.getMcpToolHandlers();
        Set<McpToolEnum> hidden = HiddenToolTextFilter.hiddenFrom(all,
                                                                  McpToolAccess.effectiveTools(session.getSettings(), all));
        return steeringFeedbackFor(category, hidden);
    }

    private McpSteeringPolicy() {
    }
}
