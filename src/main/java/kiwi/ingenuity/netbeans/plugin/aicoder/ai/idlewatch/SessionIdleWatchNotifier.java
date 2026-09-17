package kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch;

import java.time.Instant;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.IdleWatcherNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

/**
 * Delivers an idle-watch notice to the watcher session the way an inbox message arrives: a {@link
 * IdleWatcherNotification} reaches the session's inbox-delivery path. By default no interrupt is requested — the
 * watcher is normally idle and waiting for this; if it happens to be busy, the notice reaches it at the end of its turn
 * like deferred mail. A watcher armed with the opt-in {@code interrupt} flag is instead woken immediately: the notice
 * is delivered and, if the watcher session is running and allows important messages, a graceful {@link
 * InterruptTypeEnum#Mail} interrupt is requested so it reads the notice now.
 */
public final class SessionIdleWatchNotifier implements IdleWatchDelivery {

    @Override
    public boolean deliver(IdleWatcher watcher, IdleWatchEventEnum event, Instant idleSince, String targetName) {
        AbstractAiSession abstractSession = SessionRegistry.get(watcher.watcherSessionId());
        if (abstractSession == null) {
            return false;
        }
        AiSession session = abstractSession.getAiSession();
        if (!session.allowsIdleWatcherTimer()) {
            return false;
        }
        session.deliverIncomingMessage(watcher.targetSessionId(),
                                       new IdleWatcherNotification(watcher, event, idleSince, targetName));
        if (watcher.interrupt() && session.isRunning() && session.allowsImportantMessages()) {
            // Mail, not a dedicated idle-watch interrupt type: a backend that holds Mail until its own in-flight tool
            // call finishes (Claude — see ClaudeAiProcessManager#trackToolCallLifecycle) applies that same hold here,
            // rather than needing its own copy of that logic for a second interrupt reason.
            session.requestGracefulInterrupt(InterruptTypeEnum.Mail);
        }
        return true;
    }
}
