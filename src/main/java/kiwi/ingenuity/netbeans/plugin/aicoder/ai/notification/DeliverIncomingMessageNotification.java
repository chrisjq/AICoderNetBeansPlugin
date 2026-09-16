package kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.NotificationUtil;

public class DeliverIncomingMessageNotification extends AbstractNotification {

    private final AiInboxMessage message;

    public DeliverIncomingMessageNotification(AiInboxMessage message) {
        this.message = message;
    }

    /**
     * What the USER sees: one short line naming who wrote and what about. The identifying block the assistant needs is
     * {@link #agentOnlyText()} instead — it is an instruction addressed to the model ("read it with ReadAiMessage"),
     * and echoing it into the transcript as though the user had typed it is what this split removes.
     */
    @Override
    public String text() {
        return NotificationUtil.formatInboxMessage(senderName(), message.subject());
    }

    /**
     * What the ASSISTANT receives, unchanged and in full: the id, sender, reply-expected flag, subject and the
     * instruction to call ReadAiMessage. It is still sent on every delivery — only its place moved, from the visible
     * prompt into the agent-only SYSTEM block. The id in particular is load-bearing: without it the recipient cannot
     * fetch the body at all.
     */
    @Override
    public String agentOnlyText() {
        return NotificationUtil.formatInboxNotification(message, senderName());
    }

    /**
     * The sender's display name, falling back to its session id when that session has since closed — an id is poor
     * reading but it is still enough to identify who wrote.
     */
    private String senderName() {
        var abs = SessionRegistry.get(message.fromSessionId());
        return abs != null ? abs.getAiSession().name() : message.fromSessionId();
    }

    @Override
    public boolean shouldDeliver() {
        return AiSessionInboxBroker.getInstance().isMessageUnread(message.id());
    }
}
