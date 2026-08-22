package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.time.Instant;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Covers the text a session is given when a peer message arrives.
 *
 * <p>
 * This string is load-bearing for peer behaviour, which is why it is pinned. The notification is the ONLY thing a
 * recipient sees on delivery — the body exists only after {@code ReadAiMessage} — so anything the sender wrote is
 * invisible until that call is made. Three separate sessions received a notification that merely announced a message,
 * answered "I am ready to execute it" in their own chat, and ended the turn without ever fetching the body. The task
 * was lost, and the sender's instructions were never read.
 *
 * <p>
 * Naming the tools in the notification fixed it: given nothing but {@code Subject=Follow-up}, a session that had
 * previously stalled twice called {@code ReadAiMessage}, carried out the task in the body, and replied. These tests
 * exist so that behaviour is not quietly removed by a later tidy-up of the wording.
 */
class NotificationUtilInboxTest {

    private static AiInboxMessage message(String subject, boolean expectsReply, String replyToId) {
        return new AiInboxMessage("msg-1", "from-session", "to-session",
                subject, "the body, which the recipient cannot see until ReadAiMessage",
                replyToId, false, expectsReply, false,
                Instant.now(), null, null);
    }

    // ---- the instruction that makes a recipient fetch the body ----
    @Test
    void namesReadAiMessageSoTheRecipientKnowsHowToGetTheBody() {
        String text = NotificationUtil.formatInboxNotification(message("Follow-up", false, null), "Planner");
        assertTrue(text.contains("ReadAiMessage"),
                "the body is unreachable without this tool, so the notification must name it: " + text);
    }

    @Test
    void namesReadAiMessageEvenWhenTheSubjectIsMissing() {
        // A blank subject is the worst case: the recipient has nothing at all to act
        // on unless it is told how to fetch the body.
        String text = NotificationUtil.formatInboxNotification(message(null, false, null), "Planner");
        assertTrue(text.contains("ReadAiMessage"), text);
    }

    // ---- reply obligation ----
    /**
     * The notification states whether a reply is expected but must NOT tell the recipient to send one, even when it is.
     * <p>
     * Naming the tool here invited a reply to the notification itself: sessions answered "I am reviewing it" without
     * ever fetching the body, which is an acknowledgement rather than the answer that was asked for. The obligation
     * belongs with the content, so it is appended on first read instead - see
     * {@link NotificationUtil#formatReplyExpectedInstruction()}, asserted below.
     */
    @Test
    void statesWhetherAReplyIsExpectedWithoutInvitingOneBeforeTheBodyIsRead() {
        String expecting = NotificationUtil.formatInboxNotification(message("Q", true, null), "Planner");
        assertTrue(expecting.contains("replyExpected=Yes"), expecting);
        assertFalse(expecting.contains("SendAiMessage"),
                "the reply instruction belongs on first read, not on the notification: " + expecting);

        String notExpecting = NotificationUtil.formatInboxNotification(message("FYI", false, null), "Planner");
        assertFalse(notExpecting.contains("SendAiMessage"), notExpecting);
        assertTrue(notExpecting.contains("replyExpected=No"), notExpecting);
    }

    /**
     * The instruction still has to exist and still has to name the tool - it moved, it was not dropped.
     */
    @Test
    void theReplyInstructionNamesSendAiMessageForTheReadPath() {
        assertTrue(NotificationUtil.formatReplyExpectedInstruction().contains("SendAiMessage"),
                NotificationUtil.formatReplyExpectedInstruction());
    }

    // ---- the identifying fields the recipient needs ----
    @Test
    void carriesTheMessageIdSenderAndSubject() {
        String text = NotificationUtil.formatInboxNotification(message("Deploy the thing", false, null), "Planner");
        assertTrue(text.contains("msg-1"), "the id is what ReadAiMessage needs: " + text);
        assertTrue(text.contains("Planner"), text);
        assertTrue(text.contains("from-session"), text);
        assertTrue(text.contains("Deploy the thing"), text);
    }

    @Test
    void includesReplyToIdOnlyWhenTheMessageIsAReply() {
        assertTrue(NotificationUtil.formatInboxNotification(message("Re: x", false, "original-42"), "Planner")
                .contains("original-42"));
        assertFalse(NotificationUtil.formatInboxNotification(message("x", false, null), "Planner")
                .contains("replyToId"));
        assertFalse(NotificationUtil.formatInboxNotification(message("x", false, "   "), "Planner")
                .contains("replyToId"));
    }

    // ---- the chat-facing message is separate and must stay clean ----
    @Test
    void theChatSystemMessageDoesNotCarryToolInstructions() {
        // formatInboxMessage is what the human sees in the transcript; the tool
        // instructions belong only in the text sent to the model.
        String chat = NotificationUtil.formatInboxMessage("Planner", "Follow-up");
        assertFalse(chat.contains("ReadAiMessage"), "user-facing text must not read like an AI instruction: " + chat);
        assertFalse(chat.contains("SendAiMessage"), chat);
        assertTrue(chat.contains("Planner") && chat.contains("Follow-up"), chat);
    }
}
