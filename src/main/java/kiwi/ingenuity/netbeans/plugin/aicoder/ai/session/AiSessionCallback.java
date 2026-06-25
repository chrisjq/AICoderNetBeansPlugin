package kiwi.ingenuity.netbeans.plugin.aicoder.ai.session;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;

// ---- Behavioral callbacks (implemented by AiTopComponent) ----
public interface AiSessionCallback {

    boolean isRunning();

    void requestGracefulInterrupt(InterruptTypeEnum type);

    void deliverIncomingMessage(String fromSessionId, AbstractNotification notification);

    void applyDescriptionUpdate(String description);

}
