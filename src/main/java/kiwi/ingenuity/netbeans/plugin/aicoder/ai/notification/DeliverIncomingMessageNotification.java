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

    @Override
    public String text() {
        var abs = SessionRegistry.get(message.fromSessionId());
        String callerName = abs != null ? abs.getAiSession().name() : message.fromSessionId();
        return NotificationUtil.formatInboxNotification(message, callerName);
    }

    @Override
    public boolean shouldDeliver() {
        return AiSessionInboxBroker.getInstance().isMessageUnread(message.id());
    }
}
