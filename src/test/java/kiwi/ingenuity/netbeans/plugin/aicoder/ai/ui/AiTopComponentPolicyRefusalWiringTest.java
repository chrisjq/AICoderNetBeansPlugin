package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * How {@code AiTopComponent} answers a {@link PolicyRefusalEvent}: with an agent-only turn, that the user cannot see,
 * bounded so the agent cannot keep itself running.
 *
 * <p>
 * Source-level where it must be, because {@code AiTopComponent} eagerly builds a real backend and cannot be
 * instantiated in a unit test; built from occurrence counts and relative order, never from a marker's position, like
 * {@link AiTopComponentInboxInterruptWiringTest}. Everything that can run on real output does.
 *
 * <p>
 * INVISIBILITY IS PINNED HERE, not left to a comment: "nothing reaches the user" is the kind of requirement that passes
 * review and then regresses quietly.
 */
class AiTopComponentPolicyRefusalWiringTest {

    private static final String SOURCE_PATH
            = "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java";

    private static final List<String> USER_VISIBLE_CALLS = List.of("addSystemMessage", "addUserMessage",
                                                                   "setStatusMessage", "showMessage", "NotifyDescriptor",
                                                                   "conversationPanel", "infoBar", "setDisplayName",
                                                                   "setToolTipText");

    private static String readSource() throws IOException {
        return Files.readString(Path.of(SOURCE_PATH));
    }

    private static int countOf(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /**
     * The body of the declaration that starts with {@code signature}: from it to the next declaration marker.
     */
    private static String bodyOf(String source, String signature, String nextMarker) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "could not locate " + signature);
        int end = source.indexOf(nextMarker, start + signature.length());
        assertTrue(end > start, "could not locate the end of " + signature);
        return source.substring(start, end);
    }

    // ---- Invisibility ----
    /**
     * The whole notice goes to the agent-only channel. On real output: a turn whose visible text is empty and whose
     * hidden text is the notice is composed as the block alone, with no visible text ahead of it and none of the notice
     * outside the block.
     */
    @Test
    void theNoticeReachesTheModelAsAHiddenOnlyTurnWithNoVisibleText() {
        String notice = PolicyRefusalEvent.compose(List.of(
                new PolicyRefusalEvent.Refusal("/etc/hostname", "Access denied: /etc/hostname is outside scope.")));

        String prompt = AiTopComponent.composeAgentBlock("", notice);

        assertTrue(prompt.startsWith("<SYSTEM:"), "a hidden-only turn starts directly with the block: " + prompt);
        assertTrue(prompt.contains(notice), "and the agent still gets the whole notice: " + prompt);
        assertEquals("", AiTopComponent.groupedVisibleText(List.of()),
                     "nothing joins the visible text of a batch: the notice is not a notification at all");
        assertEquals("", AiTopComponent.systemMessageText(List.of()));
    }

    /**
     * The event handler records and does nothing else: no transcript line, no status, no history, no tab change.
     */
    @Test
    void theEventHandlerOnlyRecordsAndTouchesNothingTheUserSees() throws IOException {
        String source = readSource();
        String branch = bodyOf(source, "else if (event instanceof PolicyRefusalEvent refusalEvent) {",
                               "else if (event instanceof AskUserQuestionEvent aqe) {");

        assertTrue(branch.contains("pendingPolicyRefusals.addAll(refusalEvent.refusals());"), branch);
        String code = withoutComments(branch);
        for (String forbidden : USER_VISIBLE_CALLS) {
            assertFalse(code.contains(forbidden), "the handler must not touch what the user sees: " + forbidden);
        }
        assertEquals(1, countOf(code, ";"), "one statement and nothing else: " + code);
    }

    /**
     * Building the notice touches nothing the user sees either, spent budget or not.
     */
    @Test
    void buildingTheNoticeTouchesNothingTheUserSees() throws IOException {
        String source = readSource();
        String body = withoutComments(bodyOf(source, "private String consumePolicyRefusalNotice() {",
                                             "static String joinAgentNotices("));

        for (String forbidden : USER_VISIBLE_CALLS) {
            assertFalse(body.contains(forbidden), "must not touch what the user sees: " + forbidden);
        }
    }

    /**
     * The notice is only ever handed on as the AGENT-ONLY text: assigned to the variables that become the third
     * argument of {@code submitNotificationTurn} and the agent-only argument of {@code combinedAgentOnlyText}, never to
     * the visible one.
     */
    @Test
    void theNoticeIsOnlyEverJoinedIntoTheAgentOnlyText() throws IOException {
        String source = readSource();

        assertEquals(1, countOf(source, "private String consumePolicyRefusalNotice()"), "declared once");
        assertEquals(2, countOf(source, "consumePolicyRefusalNotice()") - 1,
                     "and reached from exactly two places: the flush and the empty-queue path");
        assertEquals(1, countOf(source, "interrupt = joinAgentNotices(consumePolicyRefusalNotice(), interrupt);"),
                     "the flush folds it into the agent-only explanation");
        assertEquals(1, countOf(source, "explanation = joinAgentNotices(consumePolicyRefusalNotice(), explanation);"),
                     "and so does the empty-queue path");
        assertEquals(1, countOf(source, "combinedAgentOnlyText(deliverable, interrupt)"),
                     "the flush's agent-only text is where that explanation goes, and nowhere else");
        assertEquals(1, countOf(source,
                                "submitNotificationTurn(NotificationTypeEnum.INBOX_INTERRUPT_NOTICE, null, explanation)"),
                     "the empty-queue turn has NO visible text, only the explanation");
        assertEquals(0, countOf(source, "submitNotificationTurn(NotificationTypeEnum.INBOX_INTERRUPT_NOTICE, explanation"),
                     "the explanation is never the visible argument");
    }

    /**
     * The visible half of the flush's turn is composed from the delivered notifications alone, before the explanation
     * exists, so nothing folded into the explanation can become visible.
     */
    @Test
    void theFlushsVisibleTextIsComposedFromTheNotificationsBeforeAnyExplanationExists() throws IOException {
        String source = readSource();

        int visible = source.indexOf("String text = groupedVisibleText(deliverable);");
        int explanation = source.indexOf("String interrupt = consumeInboxInterruptExplanation()");
        int refusal = source.indexOf("interrupt = joinAgentNotices(consumePolicyRefusalNotice(), interrupt);");
        assertTrue(visible >= 0 && explanation > visible && refusal > explanation,
                   "the visible text is fixed first; the explanation, with the notice, is built after it");
    }

    // ---- Notices when both apply ----
    @Test
    void theRefusalNoticeComesBeforeTheMailExplanationWhenBothApplyAndNeitherIsLostWhenOnlyOneDoes() {
        assertEquals("refusal\n\nmail", AiTopComponent.joinAgentNotices("refusal", "mail"));
        assertEquals("refusal", AiTopComponent.joinAgentNotices("refusal", null));
        assertEquals("mail", AiTopComponent.joinAgentNotices(null, "mail"));
        assertEquals("mail", AiTopComponent.joinAgentNotices("  ", "mail"), "blank counts as absent");
        assertEquals("refusal", AiTopComponent.joinAgentNotices("refusal", ""));
        assertNull(AiTopComponent.joinAgentNotices(null, null));
        assertNull(AiTopComponent.joinAgentNotices(" ", "\n"));
    }

    // ---- The loop guard ----
    /**
     * CONSUME BEFORE THE GATE, like the mail flag: the pending refusals are cleared before the budget is asked, so a
     * refusal is never reported on a later turn whether or not the agent may be resumed now.
     */
    @Test
    void thePendingRefusalsAreClearedBeforeTheBudgetIsAsked() throws IOException {
        String body = bodyOf(readSource(), "private String consumePolicyRefusalNotice() {", "static String joinAgentNotices(");

        int cleared = body.indexOf("pendingPolicyRefusals.clear()");
        int gate = body.indexOf("policyRefusalBudget.tryAcquire()");
        assertTrue(cleared >= 0 && gate > cleared, "clear first, then spend: " + body);
        assertEquals(1, countOf(readSource(), "policyRefusalBudget.tryAcquire()"), "the budget is spent in one place only");
    }

    /**
     * THE LOAD-BEARING RULE: the budget is refilled only when the USER submits. The resume turn arrives at
     * {@code handleSubmit} with {@code userInitiated=false}, so it can never refill its own budget.
     */
    @Test
    void theBudgetIsRefilledOnlyWhenTheUserSubmits() throws IOException {
        String source = readSource();

        assertEquals(1, countOf(source, "policyRefusalBudget.reset()"), "refilled in exactly one place");
        int guard = source.indexOf("if (userInitiated) {");
        int reset = source.indexOf("policyRefusalBudget.reset()");
        assertTrue(guard >= 0 && reset > guard && reset - guard < 900,
                   "and that place is the user-initiated branch of handleSubmit");
        assertFalse(withoutComments(source.substring(guard, reset)).contains("else"),
                    "no branch boundary between the guard and the reset");
        assertEquals(1, countOf(source, "submitNotificationTurn(NotificationTypeEnum.NEW_INBOX_MESSAGE"),
                     "the flush is a notification turn, submitted as not user-initiated");
        assertTrue(source.contains("handleSubmit(visible, false, agentOnlyText)"),
                   "notification turns, the resume turn among them, are submitted with userInitiated=false");
    }

    /**
     * With the budget spent nothing is shown: the only trace is a log line under the JSON-debug setting, the codebase's
     * convention for diagnostics.
     */
    @Test
    void aSpentBudgetIsALogLineGatedByTheDebugSettingAndNothingElse() throws IOException {
        String body = bodyOf(readSource(), "private String consumePolicyRefusalNotice() {", "static String joinAgentNotices(");

        int gate = body.indexOf("if (!policyRefusalBudget.tryAcquire()) {");
        assertTrue(gate >= 0, body);
        String branch = body.substring(gate, body.indexOf("return null;", gate));
        assertTrue(branch.contains("PluginSettings.isDebugJson()"), "gated: " + branch);
        assertTrue(branch.contains("LOG.log(Level.INFO"), "at INFO, the level the other diagnostics use: " + branch);
    }

    private static String withoutComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
