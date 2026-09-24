package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiTopComponentMcpSteeringWiringTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java");

    private static String source() throws IOException {
        return Files.readString(SOURCE);
    }

    private static String bodyOf(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    void eventIsRecordedWithoutUserVisibleOutput() throws IOException {
        String source = source();
        String branch = withoutComments(bodyOf(source,
                "else if (event instanceof McpSteeringRefusalEvent steeringEvent) {",
                "else if (event instanceof AskUserQuestionEvent aqe) {"));
        assertTrue(branch.contains("pendingMcpSteerings.addAll(steeringEvent.refusals())"));
        for (String forbidden : List.of("addSystemMessage", "addUserMessage", "setStatusMessage",
                "showMessage", "NotifyDescriptor", "conversationPanel")) {
            assertFalse(branch.contains(forbidden), "event branch must remain invisible: " + forbidden);
        }
    }

    @Test
    void noticeUsesAgentOnlyJoinAndOwnBudget() throws IOException {
        String source = source();
        assertTrue(source.contains("consumeMcpSteeringNotice()"));
        assertTrue(source.contains("joinAgentNotices(consumeMcpSteeringNotice(), interrupt)"));
        assertTrue(source.contains("joinAgentNotices(consumeMcpSteeringNotice(), explanation)"));
        assertTrue(source.contains("mcpSteeringBudget.tryAcquire()"));
    }

    @Test
    void budgetResetsOnlyOnUserSubmission() throws IOException {
        String source = source();
        assertTrue(source.matches("(?s).*if \\(userInitiated\\) \\{.{0,500}mcpSteeringBudget\\.reset\\(\\).*"));
        assertTrue(source.indexOf("mcpSteeringBudget.reset()") == source.lastIndexOf("mcpSteeringBudget.reset()"));
    }

    @Test
    void structuralFollowUpAndCancellationGuardArePresent() throws IOException {
        String source = source();
        assertTrue(source.contains("refusals.isEmpty()"));
        assertTrue(source.contains("cancelledTurnJustCompleted"));
        assertFalse(source.contains("lastSteeringDenialAtMillis"));
    }
}
