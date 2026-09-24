package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.McpSteeringPolicy.Category;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
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
}
