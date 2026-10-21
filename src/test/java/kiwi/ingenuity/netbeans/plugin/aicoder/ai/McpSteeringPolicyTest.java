package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.McpSteeringPolicy.Category;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpSteeringPolicyTest {

    @Test
    void everyCategoryReturnsNonBlankText() {
        for (Category category : Category.values()) {
            String feedback = McpSteeringPolicy.steeringFeedbackFor(category);
            assertNotNull(feedback, "Category " + category + " should return text");
            assertFalse(feedback.isBlank(), "Category " + category + " should return non-blank text");
        }
    }

    @Test
    void everyCategoryStatesRefusalIsAutomatic() {
        for (Category category : Category.values()) {
            String feedback = McpSteeringPolicy.steeringFeedbackFor(category);
            assertTrue(feedback.contains("Refused automatically") || feedback.contains("was not asked"),
                       "Category " + category + " must state refusal is automatic, not user decision");
            assertTrue(feedback.contains("was not asked") && feedback.contains("did not reject"),
                       "Category " + category + " must state the user was not asked and did not make this decision");
        }
    }

    @Test
    void everyCategoryStatesUserWasNotAsked() {
        String notAskedPhrase = "the user was not asked and did not reject";
        for (Category category : Category.values()) {
            String feedback = McpSteeringPolicy.steeringFeedbackFor(category);
            assertTrue(feedback.contains(notAskedPhrase),
                       "Category " + category + " must state user was not asked");
        }
    }

    @Test
    void readCategoryNamesExpectedTools() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.READ);
        assertTrue(feedback.contains(McpToolEnum.GET_FILE_CONTENT.toolName()));
        assertTrue(feedback.contains(McpToolEnum.SEARCH_IN_FILES.toolName()));
        assertTrue(feedback.contains(McpToolEnum.SEARCH_TYPES.toolName()));
        assertTrue(feedback.contains(McpToolEnum.GET_PROJECT_STRUCTURE.toolName()));
    }

    @Test
    void pathCategoryNamesExpectedTools() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.PATH);
        assertTrue(feedback.contains(McpToolEnum.GET_PROJECT_STRUCTURE.toolName()));
        assertTrue(feedback.contains(McpToolEnum.FIND_FILE.toolName()));
        assertTrue(feedback.contains(McpToolEnum.GET_FILE_CONTENT.toolName()));
    }

    @Test
    void urlCategoryNamesExpectedTools() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.URL);
        assertTrue(feedback.contains(McpToolEnum.WEB_REQUEST.toolName()));
    }

    @Test
    void urlCategoryMentionsWebAccessSettings() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.URL);
        assertTrue(feedback.contains("web-access settings"));
    }

    @Test
    void writeCategoryNamesExpectedTools() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.WRITE);
        assertTrue(feedback.contains(McpToolEnum.APPLY_EDIT.toolName()));
        assertTrue(feedback.contains(McpToolEnum.WRITE_FILE.toolName()));
        assertTrue(feedback.contains(McpToolEnum.SAVE_FILE.toolName()));
    }

    @Test
    void writeCategoryMentionsDiffPanel() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.WRITE);
        assertTrue(feedback.contains("diff panel"));
    }

    @Test
    void shellCategoryNamesBuildTools() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.SHELL);
        assertTrue(feedback.contains(McpToolEnum.BUILD_MAVEN_PROJECT.toolName()));
        assertTrue(feedback.contains(McpToolEnum.BUILD_GRADLE_PROJECT.toolName()));
        assertTrue(feedback.contains(McpToolEnum.BUILD_ANT_PROJECT.toolName()));
    }

    @Test
    void shellCategoryNamesTestTools() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.SHELL);
        assertTrue(feedback.contains(McpToolEnum.RUN_MAVEN_TESTS.toolName()));
        assertTrue(feedback.contains(McpToolEnum.RUN_GRADLE_TESTS.toolName()));
        assertTrue(feedback.contains(McpToolEnum.RUN_ANT_TESTS.toolName()));
    }

    @Test
    void shellCategoryNamesGitAndSearch() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.SHELL);
        assertTrue(feedback.contains("Git*"));
        assertTrue(feedback.contains(McpToolEnum.SEARCH_IN_FILES.toolName()));
    }

    @Test
    void unknownCategoryNamesExpectedTools() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.UNKNOWN);
        assertTrue(feedback.contains(McpToolEnum.GET_INSTRUCTIONS.toolName()));
    }

    @Test
    void noHiddenToolsKeepsEveryCategorysEstablishedText() {
        for (Category category : Category.values()) {
            assertEquals(McpSteeringPolicy.steeringFeedbackFor(category),
                         McpSteeringPolicy.steeringFeedbackFor(category, Set.of()));
        }
    }

    /**
     * A hidden set whose tools are all OUTSIDE the category must not change that category's wording: exactly the same
     * visible replacements were already named, so the established text is the right one. Each entry hides a tool that
     * no other category's text mentions ({@code GIT_TOOLS} is exercised against UNKNOWN, whose only replacement is
     * GET_INSTRUCTIONS, so even a hidden Git tool leaves its text intact).
     */
    @Test
    void hiddenToolsOutsideTheCategoryKeepThatCategorysEstablishedText() {
        Map<Category, McpToolEnum> unrelated = Map.of(
                Category.READ, McpToolEnum.WEB_REQUEST,
                Category.PATH, McpToolEnum.APPLY_EDIT,
                Category.URL, McpToolEnum.GET_FILE_CONTENT,
                Category.WRITE, McpToolEnum.WEB_REQUEST,
                Category.SHELL, McpToolEnum.GET_FILE_CONTENT,
                Category.UNKNOWN, McpToolEnum.BUILD_MAVEN_PROJECT);
        for (Category category : Category.values()) {
            String feedback = McpSteeringPolicy.steeringFeedbackFor(category, Set.of(unrelated.get(category)));
            assertEquals(McpSteeringPolicy.steeringFeedbackFor(category), feedback, category.toString());
        }
    }

    @Test
    void aHiddenGitToolDoesNotChangeTheUnknownCategorysText() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.UNKNOWN,
                                                                Set.of(McpToolGroupEnum.GIT_TOOLS.iterator().next()));
        assertEquals(McpSteeringPolicy.steeringFeedbackFor(Category.UNKNOWN), feedback);
    }

    @Test
    void aHiddenToolOutsideShellDoesNotDropTheGitClause() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.SHELL, Set.of(McpToolEnum.WEB_REQUEST));
        assertEquals(McpSteeringPolicy.steeringFeedbackFor(Category.SHELL), feedback);
        assertTrue(feedback.contains("Git*"));
    }

    @Test
    void partiallyHiddenReadFeedbackNamesOnlyVisibleTools() {
        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.READ,
                                                                Set.of(McpToolEnum.GET_FILE_CONTENT, McpToolEnum.SEARCH_TYPES));
        assertFalse(feedback.contains(McpToolEnum.GET_FILE_CONTENT.toolName()));
        assertFalse(feedback.contains(McpToolEnum.SEARCH_TYPES.toolName()));
        assertTrue(feedback.contains(McpToolEnum.SEARCH_IN_FILES.toolName()));
        assertTrue(feedback.contains(McpToolEnum.GET_PROJECT_STRUCTURE.toolName()));
    }

    @Test
    void fullyHiddenCategoriesUseNoReplacementFeedback() {
        Set<McpToolEnum> allTools = Set.of(McpToolEnum.values());
        for (Category category : Category.values()) {
            String feedback = McpSteeringPolicy.steeringFeedbackFor(category, allTools);
            assertTrue(feedback.startsWith("Refused automatically"), category.toString());
            assertTrue(feedback.contains("No IDE replacement for this action is enabled in this session"), category.toString());
        }
    }

    @Test
    void shellFeedbackOmitsGitClauseWhenBothGitGroupsAreHidden() {
        Set<McpToolEnum> gitTools = McpToolGroupEnum.GIT_TOOLS;

        String feedback = McpSteeringPolicy.steeringFeedbackFor(Category.SHELL, gitTools);

        assertFalse(feedback.contains("Git*"));
        assertTrue(feedback.contains(McpToolEnum.SEARCH_IN_FILES.toolName()));
    }

    /**
     * Every tool the full (legacy) message names must be in that category's replacement set, or a session hiding just
     * that tool would be told about it anyway. Matching is whole-word (\b on both sides) so a shorter tool name that is
     * a substring of a longer one in the text — BuildProject inside BuildMavenProject, GetCurrentFile inside
     * GetCurrentFileContent — does not count.
     */
    @Test
    void everyToolNamedInACategorysMessageIsInItsReplacementSet() {
        for (Category category : Category.values()) {
            String text = McpSteeringPolicy.steeringFeedbackFor(category);
            List<McpToolEnum> named = toolsNamedAsWholeWords(text);
            List<McpToolEnum> missing = new ArrayList<>(named);
            missing.removeAll(McpSteeringPolicy.replacementTools(category));
            assertTrue(missing.isEmpty(), category + ": message names " + missing + " but the replacement set does not");
        }
    }

    /**
     * The converse: every tool in a category's replacement set (except the Git tools, which the SHELL text covers
     * collectively as "the Git* tools") must actually be named in that category's message, so the set can never list a
     * tool the text never tells the agent about.
     */
    @Test
    void everyNonGitToolInAReplacementSetIsNamedInItsMessage() {
        for (Category category : Category.values()) {
            String text = McpSteeringPolicy.steeringFeedbackFor(category);
            for (McpToolEnum tool : McpSteeringPolicy.replacementTools(category)) {
                if (McpToolGroupEnum.GIT_TOOLS.contains(tool)) {
                    continue;
                }
                assertTrue(toolsNamedAsWholeWords(text).contains(tool),
                           category + ": replacement set has " + tool.toolName() + " but the message does not name it");
            }
            if (category == Category.SHELL) {
                assertTrue(text.contains("Git*"), "SHELL must cover the Git tools as a group: " + text);
            }
        }
    }

    /**
     * The {@link McpToolEnum} constants whose {@code toolName()} occurs in {@code text} as a whole word.
     * Pure-alphanumeric names, so regex {@code \b} is exactly the non-identifier boundary the task asks for.
     */
    private static List<McpToolEnum> toolsNamedAsWholeWords(String text) {
        List<McpToolEnum> named = new ArrayList<>();
        for (McpToolEnum tool : McpToolEnum.values()) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(tool.toolName()) + "\\b").matcher(text);
            if (m.find()) {
                named.add(tool);
            }
        }
        return named;
    }
}
