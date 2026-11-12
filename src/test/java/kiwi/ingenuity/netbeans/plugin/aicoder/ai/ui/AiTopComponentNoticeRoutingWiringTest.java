package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiTopComponentNoticeRoutingWiringTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java");

    private static String source() throws IOException {
        return Files.readString(SOURCE);
    }

    @Test
    void obsoleteInboxDeferralAndTurnFlagsAreAbsent() throws IOException {
        String source = source();
        assertFalse(source.contains("InboxNoticeDeferral"));
        assertFalse(source.contains("flushPendingInboxMessages"));
        assertFalse(source.contains("assistantTurnActive"));
        assertFalse(source.contains("pendingNewlineBeforeText"));
    }

    @Test
    void inboxRenderHasNoBusyGateAndDeliveryDrawsBeforeFlush() throws IOException {
        String source = source();
        int inbox = source.indexOf("private void handleInboxMessageOnEdt");
        int next = source.indexOf("private void drawInboxMessageIfNew", inbox);
        String handler = source.substring(inbox, next);
        assertFalse(handler.contains("busy"));
        int pending = source.indexOf("pendingNotifications.add(notification)");
        int deferred = source.indexOf("deferredNotifications.add(notification)");
        int later = Math.max(pending, deferred);
        int invoke = source.indexOf("SwingUtilities.invokeLater", later);
        int draw = source.indexOf("drawInboxMessageIfNew", invoke);
        int flush = source.indexOf("flushPendingNotifications()", invoke);
        assertTrue(invoke > later);
        assertTrue(draw > invoke && flush > draw);
    }

    @Test
    void asyncNoticePathsUseTheSequencerAndInstructionsAreRecordedOnHandover() throws IOException {
        String source = source();
        assertTrue(source.contains("assistantStream.showSystemNotice(notice)"));
        assertTrue(source.contains("assistantStream.showSystemNotice(se.text())"));
        assertTrue(source.contains("assistantStream.showSystemNotice(sn.text())"));
        int handover = source.indexOf("Runnable handedOver");
        int record = source.indexOf("recordInstructionsDelivered(sessionInstructions)", handover);
        assertTrue(record > handover);
    }

    @Test
    void permissionToolAndMultiBranchesMarkTheBreakAndSplitTheStream() throws IOException {
        String source = source();
        for (String branch : List.of("event instanceof PermissionEvent pe", "event instanceof MultiPermissionEvent mpe",
                                     "event instanceof ToolUseEvent tu")) {
            String body = branchBody(source, branch);
            assertTrue(body.contains("assistantStream.markParagraphBreak()"), branch + " must mark the paragraph break");
            assertTrue(body.contains("assistantStream.splitNow()"), branch + " must split the stream");
        }
    }

    @Test
    void askAndConfirmBranchesSplitTheStreamBeforeTheyWait() throws IOException {
        String source = source();
        for (String branch : List.of("event instanceof AskUserQuestionEvent aqe", "event instanceof ConfirmEvent ce")) {
            assertTrue(branchBody(source, branch).contains("assistantStream.splitNow()"), branch + " must split the stream");
        }
    }

    @Test
    void midTurnSitesCloseAndDrawBeforeTheirRow() throws IOException {
        String source = source();
        assertClosesAndDraws(branchBody(source, "event instanceof ConfirmEvent ce"), 1, "auto-accept confirm");
        assertClosesAndDraws(methodBody(source, "private void showDiff("), 1, "showDiff");
        assertClosesAndDraws(methodBody(source, "private void showPermissionDiff("), 1, "auto-accept permission");
        assertClosesAndDraws(methodBody(source, "private void finishPermissionDiff("), 1, "finishPermissionDiff");
        assertClosesAndDraws(methodBody(source, "private void showMultiPermissionReview("), 2, "both multi-review paths");
    }

    @Test
    void onlyTheDeliberateRowsAddDirectlyToTheConversation() throws IOException {
        String source = source();
        assertEquals(1, occurrences(source, "conversationPanel.addSystemMessage(msg)"), "launch refusal");
        assertEquals(1, occurrences(source, "conversationPanel.addSystemMessage(\"Could not find pasted file"),
                     "pasted-file warning before send");
        assertEquals(1, occurrences(source, "conversationPanel.addSystemMessage(\"Delivered pending inbox messages\")"),
                     "pre-send row in handleSubmit");
        assertEquals(1, occurrences(source, "conversationPanel.addSystemMessage(\"Session Instructions Sent\")"),
                     "pre-send row in handleSubmit");
        assertEquals(2, occurrences(source, "conversationPanel.addSystemMessage(NotificationUtil.formatToolActionQuestion("),
                     "tool-action rows, each right after a close");
        assertEquals(1, occurrences(source, "conversationPanel.addSystemMessage(text)"), "the sequencer sink");
        assertFalse(source.contains("conversationPanel.addSystemMessage(\n"), "no multi-line direct row");
    }

    @Test
    void theClosePathDrawsQueuedRowsBeforeTheHistoryIsSaved() throws IOException {
        String source = source();
        int reset = source.indexOf("assistantStream.reset()");
        int save = source.lastIndexOf("saveHistory();", reset);
        int endTurn = source.lastIndexOf("assistantStream.endTurn();", save);
        assertTrue(endTurn >= 0 && save - endTurn < 300 && save < reset,
                   "the close path must call endTurn just before saveHistory, so queued rows reach the transcript");
    }

    private static void assertClosesAndDraws(String body, int expected, String site) {
        assertEquals(expected, occurrences(body, "assistantStream.closeAndDrawNow()"), site + " must close and draw");
        assertFalse(body.contains("assistantStream.endTurn()"), site + " must not use endTurn mid-turn");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method not found: " + signature);
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end);
    }

    @Test
    void onlyTheSequencerSinkFinalisesTheAssistantBubble() throws IOException {
        assertEquals(1, occurrences(source(), "conversationPanel.finaliseAssistantMessage()"),
                     "the sink in the sequencer wiring is the one place that finalises the bubble");
    }

    private static String branchBody(String source, String marker) {
        int handler = source.indexOf("private void handleEvent(");
        assertTrue(handler >= 0, "handleEvent not found");
        int start = source.indexOf(marker, handler);
        assertTrue(start >= 0, "branch not found: " + marker);
        int end = source.indexOf("else if (event instanceof", start + marker.length());
        return source.substring(start, end < 0 ? source.length() : end);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
