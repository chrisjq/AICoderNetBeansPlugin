package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.NotificationTypeEnum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * {@link AiTopComponent#groupedVisibleText} and {@link AiTopComponent#combinedAgentOnlyText}: the two pure static
 * helpers {@code flushPendingNotifications} composes its turn from. Package-private, so this test lives alongside
 * {@code AiTopComponent} rather than instantiating it — see {@code AiTopComponentInboxInterruptWiringTest} for why that
 * class itself can't be built in a unit test.
 */
class AiTopComponentNotificationGroupingTest {

    private static AbstractNotification notification(NotificationTypeEnum type, String text, String agentOnlyText) {
        return new AbstractNotification() {
            @Override
            public String text() {
                return text;
            }

            @Override
            public boolean shouldDeliver() {
                return true;
            }

            @Override
            public String agentOnlyText() {
                return agentOnlyText;
            }

            @Override
            public NotificationTypeEnum type() {
                return type;
            }
        };
    }

    /**
     * MAIL IS DRAWN BY THE ARRIVAL HANDLER, NOT HERE. {@code handleGlobalProperty} posts "New message from [X]:
     * Subject" the moment the message lands, and did so long before notifications started rendering as system messages.
     * Composing the same line here as well gave every message two identical entries.
     * <p>
     * The duplicate never appeared on default settings — with {@code autoNotifyInbox} off, mail is parked in the
     * deferred queue and never reaches this batch at all — but the session templates enable it, so exactly the sessions
     * created from them would have shown it on every arrival.
     */
    @Test
    void anInboxOnlyBatchDrawsNothingBecauseArrivalAlreadyDrewIt() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "New message from [A]: first", null),
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "New message from [B]: second", null));

        assertEquals("", AiTopComponent.groupedVisibleText(batch),
                     "an arriving message is not something the user typed, so it must not ride in the prompt");
        assertEquals("", AiTopComponent.systemMessageText(batch),
                     "the arrival handler already drew both lines; drawing them again is the duplicate");
    }

    /**
     * THE OTHER HALF OF THE SAME PROPERTY, and the one that makes suppression safe: the assistant is still told. Only
     * the duplicate DRAWING stops — mail's agent-only block still rides out with the turn, which is the only channel
     * that reaches the model at all now that system-rendered types contribute nothing to the prompt.
     */
    @Test
    void suppressingTheDuplicateLineStillSendsTheMailToTheAssistant() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "New message from [A]: first", "id=1 from=A"));

        assertEquals("", AiTopComponent.systemMessageText(batch), "nothing drawn here");
        assertEquals("id=1 from=A", AiTopComponent.combinedAgentOnlyText(batch, null),
                     "but the assistant must still receive the message, or suppression would lose it entirely");
    }

    @Test
    void aBuildOnlyBatchLeavesThePromptEmptyAndGoesToTheSystemMessageInstead() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.BUILD_COMPLETE, "BUILD: BuildMavenProject SUCCESS in 2 secs (/p)",
                             null));

        assertEquals("", AiTopComponent.groupedVisibleText(batch),
                     "a build result is not something the user typed, so it must not ride in the prompt");
        assertEquals("BUILD: BuildMavenProject SUCCESS in 2 secs (/p)", AiTopComponent.systemMessageText(batch),
                     "the build summary is already one complete line; no [prefix] glued in front");
    }

    /**
     * A build result has NO arrival publisher — this flush is the only thing that ever draws its summary — so it must
     * keep being composed here while mail beside it is skipped. Suppressing both would lose the build line entirely
     * rather than de-duplicate it, which is why the skip is per-type rather than blanket.
     */
    @Test
    void aMixedBatchDrawsTheBuildLinesAndSkipsTheMail() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "New message from [A]: one", null),
                notification(NotificationTypeEnum.BUILD_COMPLETE, "BUILD: X SUCCESS", null),
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "New message from [B]: two", null));

        assertEquals("", AiTopComponent.groupedVisibleText(batch),
                     "neither mail nor a build result is something the user typed");
        assertEquals("BUILD: X SUCCESS", AiTopComponent.systemMessageText(batch),
                     "the build line stands alone: mail was already drawn on arrival");
    }

    @Test
    void blankAndNullTextsAreSkipped() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, null, null),
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "  ", null),
                notification(NotificationTypeEnum.BUILD_COMPLETE, "BUILD: X SUCCESS", null));

        assertEquals("", AiTopComponent.groupedVisibleText(batch), "nothing reaches the prompt from a notification");
        assertEquals("BUILD: X SUCCESS", AiTopComponent.systemMessageText(batch),
                     "the blank entries are skipped and the one real line stands alone");
    }

    @Test
    void severalBuildResultsInOneBatchAreJoinedIntoOneSystemMessage() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.BUILD_COMPLETE, "BUILD: A SUCCESS", null),
                notification(NotificationTypeEnum.BUILD_COMPLETE, "BUILD: B FAILED", null));

        assertEquals("BUILD: A SUCCESS\n\nBUILD: B FAILED", AiTopComponent.systemMessageText(batch),
                     "two builds finishing while the session was busy read as two lines, in arrival order");
    }

    /**
     * Every type that actually carries visible text keeps it out of the PROMPT, so nothing a notification produces can
     * appear in the transcript as though the user had typed it. Where the line is then drawn differs by type, which is
     * asserted separately below.
     * <p>
     * {@link NotificationTypeEnum#INBOX_INTERRUPT_NOTICE} is deliberately excluded rather than swept in: it is only
     * ever submitted with NULL visible text and its explanation as agent-only, so it has nothing to render as a system
     * message and nothing that could reach the prompt. Asserting the property of that type too would pin behaviour it
     * does not have.
     */
    @Test
    void noTypeThatCarriesVisibleTextEverReachesThePrompt() {
        for (NotificationTypeEnum type : List.of(NotificationTypeEnum.NEW_INBOX_MESSAGE,
                                                 NotificationTypeEnum.BUILD_COMPLETE)) {
            List<AbstractNotification> batch = List.of(notification(type, "a line", null));

            assertTrue(type.rendersAsSystemMessage(), type + " must render as a system message");
            assertEquals("", AiTopComponent.groupedVisibleText(batch),
                         type + " must not put text into the prompt the user appears to have typed");
        }
    }

    /**
     * EXACTLY ONE PUBLISHER PER LINE. A type drawn on arrival must be skipped by the flush, and a type with no arrival
     * publisher must be drawn by it — pinned together, because the failure runs in both directions: clearing the flag
     * on mail restores the duplicate, and setting it on a build result silently loses the only copy of that line.
     */
    @Test
    void aTypeIsDrawnByTheFlushExactlyWhenNothingElseAlreadyDrewIt() {
        assertTrue(NotificationTypeEnum.NEW_INBOX_MESSAGE.announcedOnArrival(),
                   "handleGlobalProperty draws mail the moment it lands");
        assertFalse(NotificationTypeEnum.BUILD_COMPLETE.announcedOnArrival(),
                    "a finishing build has no arrival publisher — the flush is its only chance to be drawn");

        for (NotificationTypeEnum type : List.of(NotificationTypeEnum.NEW_INBOX_MESSAGE,
                                                 NotificationTypeEnum.BUILD_COMPLETE)) {
            List<AbstractNotification> batch = List.of(notification(type, "a line", null));

            assertEquals(type.announcedOnArrival() ? "" : "a line", AiTopComponent.systemMessageText(batch),
                         type + " must be drawn here exactly when it was not drawn on arrival");
        }
    }

    @Test
    void entirelyBlankBatchProducesEmptyString() {
        List<AbstractNotification> batch = List.of(notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, null, null));

        assertEquals("", AiTopComponent.groupedVisibleText(batch));
    }

    @Test
    void combinedAgentOnlyTextLeadsWithInterruptExplanationThenEachNotificationInOrder() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "mail", "mail agent-only"),
                notification(NotificationTypeEnum.BUILD_COMPLETE, "BUILD: X SUCCESS", "full build report"));

        String agentOnly = AiTopComponent.combinedAgentOnlyText(batch, "turn was interrupted");

        assertEquals("turn was interrupted\n\nmail agent-only\n\nfull build report", agentOnly);
    }

    /**
     * A notification with no agent-only text of its own falls back to its visible text, so the assistant is told
     * something even when the producer supplied only one half.
     * <p>
     * This inverts what the test asserted before. It was correct when visible text rode the prompt: agent-only text was
     * genuinely EXTRA, and a notification without it had already been seen by the assistant. Now that a system-rendered
     * type contributes nothing to the prompt, a notification without agent-only text would reach the user and nobody
     * else — which is exactly how the delivery-failure notices stopped telling a sender that its message had never
     * arrived.
     */
    @Test
    void aNotificationWithoutAgentOnlyTextFallsBackToItsVisibleText() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "mail", null),
                notification(NotificationTypeEnum.BUILD_COMPLETE, "BUILD: X SUCCESS", "  "));

        String agentOnly = AiTopComponent.combinedAgentOnlyText(batch, null);

        assertEquals("mail\n\nBUILD: X SUCCESS", agentOnly,
                     "both fall back: one has null agent-only text, the other only blanks");
    }

    @Test
    void trulyEmptyEntriesStillContributeNothing() {
        List<AbstractNotification> batch = List.of(
                notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, null, null),
                notification(NotificationTypeEnum.BUILD_COMPLETE, "   ", "  "));

        assertNull(AiTopComponent.combinedAgentOnlyText(batch, null),
                   "with neither half carrying text there is genuinely nothing to tell the agent");
    }

    @Test
    void combinedAgentOnlyTextLeadsWithTheInterruptExplanation() {
        List<AbstractNotification> batch = List.of(notification(NotificationTypeEnum.NEW_INBOX_MESSAGE, "mail", null));

        String agentOnly = AiTopComponent.combinedAgentOnlyText(batch, "turn was interrupted");

        assertEquals("turn was interrupted\n\nmail", agentOnly,
                     "the explanation comes first, then the notification's own text via the fallback");
    }
}
