package kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSessionCallback;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link SessionIdleWatchNotifier}'s interrupt decision against a stubbed {@link AiSession} registered in
 * {@link SessionRegistry} — the same seam {@code AiSessionInboxBrokerNotifierTest} and {@code
 * SessionBuildNotifierTest} use, so no live NetBeans UI or backend is needed.
 */
class SessionIdleWatchNotifierTest {

    private final SessionIdleWatchNotifier notifier = new SessionIdleWatchNotifier();
    private String registeredSessionId;

    @AfterEach
    void tearDown() {
        if (registeredSessionId != null) {
            SessionRegistry.unregister(registeredSessionId);
            registeredSessionId = null;
        }
    }

    private AiSession session(String id, boolean running, boolean allowImportant, AtomicInteger interrupts) {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowIdleWatcherTimer(true);
        settings.setAllowImportantMessages(allowImportant);
        AiSession session = new AiSession(id, "Name-" + id, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(),
                                          Instant.now());
        session.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
                if (type == InterruptTypeEnum.Mail) {
                    interrupts.incrementAndGet();
                }
            }

            @Override
            public void deliverIncomingMessage(String from, AbstractNotification msg) {
            }

            @Override
            public void applyDescriptionUpdate(String desc) {
            }
        });
        AbstractAiSession wrapper = new AbstractAiSession(session) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getSessionName() {
                return "Name-" + id;
            }

            @Override
            public Map getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        };
        SessionRegistry.register(wrapper);
        registeredSessionId = id;
        return session;
    }

    private static IdleWatcher watcher(String watcherSessionId, boolean interrupt) {
        return new IdleWatcher("idle-watch-1", watcherSessionId, "target-session", Duration.ofMinutes(5), false,
                               interrupt, null, Instant.now());
    }

    @Test
    void interruptTrueRunningAndAllowingImportantRequestsAMailInterruptOnIdle() {
        AtomicInteger interrupts = new AtomicInteger();
        AiSession session = session("notifier-yes", true, true, interrupts);

        boolean delivered = notifier.deliver(watcher(session.id(), true), IdleWatchEventEnum.IDLE, Instant.now(),
                                             "Target");

        assertTrue(delivered);
        assertEquals(1, interrupts.get());
    }

    @Test
    void interruptTrueAlsoRequestsAnInterruptOnTargetClosed() {
        AtomicInteger interrupts = new AtomicInteger();
        AiSession session = session("notifier-closed", true, true, interrupts);

        boolean delivered = notifier.deliver(watcher(session.id(), true), IdleWatchEventEnum.TARGET_CLOSED,
                                             Instant.now(), "Target");

        assertTrue(delivered);
        assertEquals(1, interrupts.get(), "TARGET_CLOSED must request an interrupt exactly like IDLE when opted in");
    }

    @Test
    void interruptFalseNeverRequestsAnInterruptEvenWhenRunningAndAllowingImportant() {
        AtomicInteger interrupts = new AtomicInteger();
        AiSession session = session("notifier-no", true, true, interrupts);

        boolean delivered = notifier.deliver(watcher(session.id(), false), IdleWatchEventEnum.IDLE, Instant.now(),
                                             "Target");

        assertTrue(delivered);
        assertEquals(0, interrupts.get(), "default is opt-in only — no flag means no interrupt");
    }

    @Test
    void interruptTrueButNotRunningRequestsNoInterrupt() {
        AtomicInteger interrupts = new AtomicInteger();
        AiSession session = session("notifier-idle-session", false, true, interrupts);

        boolean delivered = notifier.deliver(watcher(session.id(), true), IdleWatchEventEnum.IDLE, Instant.now(),
                                             "Target");

        assertTrue(delivered);
        assertEquals(0, interrupts.get(), "nothing to interrupt when the watcher session isn't running a turn");
    }

    @Test
    void interruptTrueButImportantMessagesDisallowedRequestsNoInterrupt() {
        AtomicInteger interrupts = new AtomicInteger();
        AiSession session = session("notifier-quiet", true, false, interrupts);

        boolean delivered = notifier.deliver(watcher(session.id(), true), IdleWatchEventEnum.IDLE, Instant.now(),
                                             "Target");

        assertTrue(delivered);
        assertEquals(0, interrupts.get(), "must respect the session's own allow-important-messages setting");
    }
}
