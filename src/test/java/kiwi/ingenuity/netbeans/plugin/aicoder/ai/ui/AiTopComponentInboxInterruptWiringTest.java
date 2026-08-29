package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The mail-interrupt explanation: that an aborted tool call was interrupted rather than refused, and that it may have
 * run anyway.
 *
 * <p>
 * Both failures this pins were observed, not theorised. A session reported to the user that they had rejected a command
 * they never saw; and separately, a SendAiMessage reported as rejected had in fact been delivered, so the session told
 * the user it was never sent and a round trip was spent undoing that.</p>
 *
 * <p>
 * Source-level, because {@code AiTopComponent} eagerly builds a real backend and cannot be instantiated in a unit test.
 * These assertions are deliberately built from occurrence COUNTS and relative ORDER rather than from extracted method
 * bodies — no marker declaration is used as a boundary, so a member reordering cannot break them, which is the failure
 * that took out eight tests in this package earlier today.</p>
 */
class AiTopComponentInboxInterruptWiringTest {

    private static final String SOURCE_PATH
            = "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java";

    private String readSource() throws IOException {
        return Files.readString(Path.of(SOURCE_PATH));
    }

    private static int countOf(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /**
     * The explanation's text with Java's concatenation seams closed up.
     *
     * <p>
     * The constant is written as several adjacent string literals, so a sentence that reads as one phrase at runtime is
     * split by {@code " + "} in the source. Asserting on the raw source would therefore fail for wording that is
     * actually present, and would break again every time the literal is re-wrapped. Joining the chunks tests what the
     * assistant will read.</p>
     */
    private String explanationText() throws IOException {
        String source = readSource();
        int start = source.indexOf("INBOX_INTERRUPT_EXPLANATION");
        int end = source.indexOf("private String consumeInboxInterruptExplanation()", start);
        assertTrue(end > start, "could not locate the explanation constant");
        return source.substring(start, end).replaceAll("\"\\s*\\+\\s*\"", "");
    }

    /**
     * The explanation must exist once and be reached from both delivery paths. Two literals would drift, and the whole
     * point is that the flush path says the same thing as the empty-queue path.
     */
    @Test
    void oneSharedExplanationIsUsedByBothPaths() throws IOException {
        String source = readSource();

        assertTrue(source.contains("INBOX_INTERRUPT_EXPLANATION"),
                "the explanation must be a named shared constant");
        assertEquals(3, countOf(source, "consumeInboxInterruptExplanation()"),
                "exactly one declaration and two call sites — the flush path and the empty-queue path");
    }

    /**
     * THE GAP THIS CLOSES. When mail IS delivered, the flush used to say nothing about the interrupt and leave it to
     * the arriving message to imply. It does not imply it: the message explains why new mail exists, not why the tool
     * call died. The explanation must be prepended into that same turn, before it is submitted.
     */
    @Test
    void theFlushPathCarriesTheExplanationIntoTheTurnThatDeliversMail() throws IOException {
        String source = readSource();

        int consume = source.indexOf("String interrupt = consumeInboxInterruptExplanation()");
        int submit = source.indexOf("submitNotificationTurn(NotificationTypeEnum.NEW_INBOX_MESSAGE");
        assertTrue(consume >= 0, "the flush must take the interrupt explanation");
        assertTrue(submit > consume, "and pass it into the turn it submits");
        assertTrue(source.substring(submit, Math.min(submit + 200, source.length())).contains("interrupt"),
                "the explanation must be handed to the submitted turn as its agent-only text");
    }

    /**
     * THE EXPLANATION IS FOR THE MODEL, NOT THE USER. It reached the assistant correctly but was also rendered in the
     * conversation as a user message — two long paragraphs of protocol instructions sitting in the transcript. It is
     * hidden, never shortened: the wording is a correctness mechanism that cost two false "the user rejected this"
     * reports to earn.
     */
    @Test
    void theExplanationIsAgentOnlyAndNeverDisplayed() throws IOException {
        String source = readSource();

        int handleSubmit = source.indexOf("private void handleSubmit(String text, boolean userInitiated, String agentOnlyText)");
        assertTrue(handleSubmit >= 0, "handleSubmit must accept agent-only text");

        // The agent's prompt is built from it — placement is pinned by theAgentOnlyBlockGoesAtTheEndBehindTheMarker.
        assertTrue(source.contains("agentOnlyText"),
                "agent-only text must reach what the agent receives");
        // ...and the display call is skipped when there is nothing visible. Guarded on the CURRENT text — see
        // theVisibleTextIsAlwaysSentAndTestedAfterDeferredMailIsAppended for why the entry-time flag was wrong.
        assertTrue(source.contains("if (!text.isBlank()) {"),
                "addUserMessage must be guarded, so a hidden-only submit renders nothing");
        assertEquals(1, countOf(source, "conversationPanel.addUserMessage(text, userInitiated)"),
                "exactly one display call, and it takes the VISIBLE text only — never the agent text");
    }

    /**
     * The empty-queue path submits a turn whose only content is the explanation. With that hidden there is nothing left
     * to show, and it must render nothing rather than an empty bubble.
     */
    @Test
    void theEmptyQueuePathDisplaysNothingAtAll() throws IOException {
        String source = readSource();

        assertTrue(source.contains("submitNotificationTurn(NotificationTypeEnum.INBOX_INTERRUPT_NOTICE, null, explanation)"),
                "the empty-queue path must submit no visible text and the explanation as agent-only");
    }

    /**
     * THE CUT IS BY PROVENANCE, NOT BY CONTENT — the constraint that decides whether this feature is safe.
     *
     * <p>
     * A content scan would truncate any message containing the marker. Both of these demonstrably occur: a user can
     * type or paste "[SYSTEM]", and an assistant discussing this very feature writes it — this session has done so
     * repeatedly today. Either would be silently cut mid-message, which is worse than the bug being fixed.</p>
     *
     * <p>
     * So the split is structural: agent-only text arrives as its own parameter and the visible text is displayed from
     * its own variable. Nothing is searched, so nothing can be falsely matched.</p>
     */
    @Test
    void theBlockIsByProvenanceNotByScanningForTheTags() throws IOException {
        String source = readSource();

        assertTrue(source.contains("static final String SYSTEM_BLOCK_OPEN"), "the tags must be named constants");
        assertTrue(source.contains("static final String SYSTEM_BLOCK_CLOSE"), "including the closing tag");

        // The tags are only ever CONCATENATED into agent-facing text. If they were searched for, split on, or stripped,
        // a user's or the assistant's own message containing them could be mangled.
        for (String tag : List.of("SYSTEM_BLOCK_OPEN", "SYSTEM_BLOCK_CLOSE")) {
            assertEquals(0, countOf(source, "indexOf(" + tag),
                    "the UI must never search for " + tag + " — that would mangle a user or assistant message");
            assertEquals(0, countOf(source, "split(" + tag), "nor split on " + tag);
            assertEquals(0, countOf(source, "contains(" + tag), "nor test for " + tag);
            assertEquals(0, countOf(source, "replace(" + tag), "nor strip " + tag);
        }
    }

    /**
     * THE DROPPED-MAIL BUG. hasVisible was computed on entry, BEFORE deferred inbox notifications were appended to the
     * text. A notice-only turn (blank visible text, agent-only explanation) submitted while mail was queued then took
     * the no-visible branch: the composed prompt never included expandedText(), so THE MAIL NEVER REACHED THE MODEL,
     * and the display guard was still false, so IT WAS NEVER SHOWN EITHER. Lost from both sides, silently.
     *
     * <p>
     * Both sites now read the CURRENT text. The delimited block is what makes that safe to keep: with the agent-only
     * text wrapped rather than positioned, appending to the visible text cannot break it no matter where the appending
     * happens.</p>
     */
    @Test
    void theVisibleTextIsAlwaysSentAndTestedAfterDeferredMailIsAppended() throws IOException {
        String source = readSource();

        // The agent's copy always includes the visible text — there is no branch that omits it.
        assertTrue(source.contains("String visibleForAgent = tmpExpansion.expandedText();"),
                "the visible text must always be part of what the agent receives");
        assertEquals(0, countOf(source, "hasVisible ? tmpExpansion.expandedText()"),
                "the agent composition must not branch on the stale visible flag");

        // The display decision is taken from the current text, not the entry-time flag.
        assertTrue(source.contains("if (!text.isBlank()) {"),
                "display must be decided from the CURRENT text, after deferred mail may have been appended");
        assertEquals(0, countOf(source, "if (hasVisible) {"),
                "the stale flag must no longer gate display");
    }

    /**
     * The agent-only text is WRAPPED, not positioned. That is what makes the dropped-mail class of bug unrepeatable:
     * appending to the visible text — another notification type, a context note, anything — cannot move text across a
     * boundary that is defined by delimiters rather than by an index.
     */
    @Test
    void theAgentOnlyTextIsWrappedInBothTags() throws IOException {
        String source = readSource();

        assertTrue(source.contains("SYSTEM_BLOCK_OPEN + \"\\n\" + agentOnlyText + \"\\n\" + SYSTEM_BLOCK_CLOSE"),
                "the agent-only text must be enclosed by BOTH tags, so its position stops mattering");
    }

    /**
     * A MALFORMED BLOCK RENDERS AS-IS — unclosed, nested, tags out of order, anything. No strip, no repair, no
     * best-effort fallback.
     *
     * <p>
     * We compose those tags ourselves, so a malformed block means WE have a bug. Rendering it puts that bug in front of
     * the user immediately; swallowing text while guessing at what was meant hides it. A visible "&lt;SYSTEM&gt;" in
     * the transcript gets reported and fixed — text that silently vanishes does not.</p>
     *
     * <p>
     * This holds by construction rather than by a rule: the transcript is BUILT from the visible text and never DERIVED
     * by removing anything, so there is no parse to be malformed. These assertions exist to stop someone later adding a
     * "helpful" strip — the one change that would turn a cosmetic defect back into a vanishing one.</p>
     */
    @Test
    void aMalformedBlockRendersAsIsWithNoRecoveryLogic() throws IOException {
        String source = readSource();

        // The displayed string is the visible text VERBATIM — no transformation of any kind on the way to the panel.
        assertTrue(source.contains("conversationPanel.addUserMessage(text, userInitiated)"),
                "the transcript must render the visible text exactly as composed");

        // No stripping, trimming or rewriting of the tags anywhere.
        for (String needle : List.of("replaceAll(\"<SYSTEM", "replace(\"<SYSTEM", "replaceAll(\"</SYSTEM",
                "replace(\"</SYSTEM", "stripSystemBlock", "removeSystemBlock")) {
            assertEquals(0, countOf(source, needle),
                    "no recovery or strip logic may exist — a malformed block must render, not vanish: " + needle);
        }
    }

    /**
     * Deferred inbox mail must reach the user. It is appended to the VISIBLE text, which is displayed and is also
     * always part of the agent's copy — so it can be neither hidden nor dropped, wherever in the method it is added.
     */
    @Test
    void deferredInboxMessagesStayVisible() throws IOException {
        String source = readSource();

        int deferredAppend = source.indexOf("[Pending inbox messages]");
        assertTrue(deferredAppend >= 0, "the deferred-notification append must still exist");
        assertTrue(source.contains("text = text.isBlank() ? \"[Pending inbox messages]\\n\" + deferred"),
                "deferred mail must be appended to the VISIBLE text, so it is both shown and sent");
    }

    /**
     * Hiding it in the panel alone would leave it reappearing on reload. addUserMessage is the single call that feeds
     * BOTH the transcript and saved history, so skipping it covers both — this pins that there is no second, separate
     * history write that could still record it.
     */
    @Test
    void nothingHiddenCanReappearFromHistory() throws IOException {
        String source = readSource();

        assertEquals(0, countOf(source, "addUserMessage(agentText"), "the agent text must never be recorded");
        assertEquals(0, countOf(source, "addUserMessage(agentOnlyText"), "nor the hidden text");
    }

    /**
     * One turn, not two. handleSubmit runs a single turn at a time and submitting in a loop drops all but the first, so
     * the explanation has to ride the same submission as the mail rather than be sent on its own.
     */
    @Test
    void theExplanationRidesTheSameTurnAsTheMail() throws IOException {
        String source = readSource();

        assertEquals(1, countOf(source, "submitNotificationTurn(NotificationTypeEnum.NEW_INBOX_MESSAGE"),
                "the flush must submit exactly one turn, carrying both the explanation and the mail");
    }

    /**
     * BOTH turn-completion paths must offer the empty-queue explanation, not just one.
     *
     * <p>
     * They drifted: the suppressed-turn path had the fallback and ordinary turn completion did not, so the notice was
     * missing from the path most turns take — and that is exactly the mechanism's own scenario, where the session read
     * the mail itself during the interrupted turn and nothing else is left to tell it the abort was not a user
     * rejection.</p>
     */
    @Test
    void bothTurnCompletionPathsOfferTheEmptyQueueExplanation() throws IOException {
        String source = readSource();

        assertEquals(2, countOf(source, "!flushPendingNotifications() && !explainInboxInterruptIfNeeded()"),
                "both the suppressed-turn path and ordinary turn completion must offer the fallback");
        assertEquals(3, countOf(source, "explainInboxInterruptIfNeeded()"),
                "one declaration and exactly two call sites — the fallback must not be invoked anywhere else");
    }

    /**
     * The notice submits a turn, so it must suppress the idle branch exactly as the flush does — otherwise the tab goes
     * green and the input goes live for the moment between the two.
     */
    @Test
    void noTurnCompletionPathGoesIdleWithoutOfferingTheFallback() throws IOException {
        String source = readSource();

        // "if (!flushPendingNotifications())" can only appear where the guard ENDS at the flush — a site paired with
        // the fallback reads "... () && !explain...". Whitespace-independent, so reformatting cannot fake a pass.
        assertEquals(0, countOf(source, "if (!flushPendingNotifications())"),
                "a turn-completion path that guards only on the flush would report idle without ever offering the "
                + "empty-queue explanation — that is the drift this pins");
    }

    /**
     * The double-notice guard. The flag is consumed — read and cleared together — so whichever path asks first gets the
     * text and the other gets nothing. A session told twice that it was interrupted starts narrating the interruption
     * to the user, which is its own noise.
     */
    @Test
    void theInterruptFlagIsConsumedInExactlyOnePlace() throws IOException {
        String source = readSource();

        int declaration = source.indexOf("private String consumeInboxInterruptExplanation()");
        assertTrue(declaration >= 0, "the consuming accessor must exist");

        int clearInConsume = source.indexOf("mailArrivedDuringTurn = false", declaration);
        assertTrue(clearInConsume > declaration && clearInConsume - declaration < 400,
                "the flag must be cleared inside the consuming accessor, not by its callers");
        assertEquals(2, countOf(source, "mailArrivedDuringTurn = false"),
                "cleared in the consuming accessor and on a user-initiated submit — nowhere else");
    }

    /**
     * THE REGRESSION THAT WOULD MAKE THE NOTICE LIE. Codex steers, Copilot injects, Grok and Ollama drop the mail —
     * none of them abort anything, so telling those sessions their turn was interrupted would be a plain falsehood
     * about their own history.
     *
     * <p>
     * The flag is cleared BEFORE that gate on purpose: an interrupt those backends never had must not be reported on
     * some later turn either.</p>
     */
    @Test
    void nonAbortingBackendsAreStillExcluded() throws IOException {
        String source = readSource();

        int declaration = source.indexOf("private String consumeInboxInterruptExplanation()");
        int gate = source.indexOf("MailDeliveryTimingEnum.ABORTS_TURN", declaration);
        int clear = source.indexOf("mailArrivedDuringTurn = false", declaration);

        assertTrue(gate > declaration && gate - declaration < 600,
                "the ABORTS_TURN gate must still be applied");
        assertTrue(clear < gate,
                "the flag must be cleared before the gate, so a non-aborting backend cannot report it later");
    }

    /**
     * The second, sharper half. "Rejected" reads as "it did not happen", so the notice has to say outright that it may
     * have happened anyway and direct the session to check the actual result before resuming.
     */
    @Test
    void theWordingWarnsThatAnAbortedCallMayHaveAlreadyRun() throws IOException {
        String wording = explanationText();

        assertTrue(wording.contains("MAY HAVE ALREADY RUN"),
                "the notice must say an aborted call may have taken effect: " + wording);
        assertTrue(wording.contains("Check its result."),
                "it must direct the session to check the interrupted call's result: " + wording);
        assertTrue(wording.contains("Read your inbox and resume your work."),
                "it must direct the session to process mail before resuming: " + wording);
    }

    /**
     * The original guarantee must survive the rewrite: the abort is not a user decision, and the session must not tell
     * the user it was.
     */
    @Test
    void theWordingStillDeniesAUserRejection() throws IOException {
        String wording = explanationText();

        assertTrue(wording.contains("NOT a rejection, cancellation, or refusal by the user"), wording);
        assertTrue(wording.contains("Do not tell the user they declined or rejected anything"), wording);
    }
}
