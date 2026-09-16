package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSessionCallback;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.SpooledLogCopier;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionBuildNotifier}'s delivery decisions, driven against a stub session registered in {@link
 * SessionRegistry} the same way {@code AiSessionInboxBrokerNotifierTest.stubSession} does it. The listener itself is
 * exercised directly rather than through a real {@link BuildQueue} run, since only its branching on job type, cancel
 * reason and session lookup is under test here.
 */
class SessionBuildNotifierTest {

    private final SessionBuildNotifier notifier = new SessionBuildNotifier();
    private final List<String> registeredSessionIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        registeredSessionIds.forEach(SessionRegistry::unregister);
        registeredSessionIds.clear();
    }

    private AiSession stubSession(String id, boolean running, boolean allowImportant,
                                  AtomicInteger deliveries, AtomicInteger interrupts) {
        return stubSession(id, running, allowImportant, deliveries, interrupts, null);
    }

    private AiSession stubSession(String id, boolean running, boolean allowImportant,
                                  AtomicInteger deliveries, AtomicInteger interrupts,
                                  AtomicReference<AbstractNotification> deliveredNotification) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, allowImportant, null, null, null);
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
            public void deliverIncomingMessage(String from, AbstractNotification notification) {
                deliveries.incrementAndGet();
                if (deliveredNotification != null) {
                    deliveredNotification.set(notification);
                }
            }

            @Override
            public void applyDescriptionUpdate(String description) {
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
        registeredSessionIds.add(id);
        return session;
    }

    private BuildJob job(String sessionId, BuildTypeEnum type) {
        BuildRequest request = new BuildRequest("BuildMavenProject", "/proj", "/proj", sessionId, "Caller", type,
                                                60_000L, true, control -> null);
        BuildJob job = new BuildJob("job-1", request, Instant.now());
        job.markFinished(BuildStatusEnum.SUCCESS, "OK", null, Instant.now());
        return job;
    }

    @Test
    void asyncJobDeliveredAndInterruptedWhenRunningAndAllowingImportant() {
        AtomicInteger deliveries = new AtomicInteger();
        AtomicInteger interrupts = new AtomicInteger();
        stubSession("async-both", true, true, deliveries, interrupts);

        notifier.onFinished(job("async-both", BuildTypeEnum.ASYNC));

        assertEquals(1, deliveries.get(), "async result must reach the session's inbox path");
        assertEquals(1, interrupts.get(), "a running session that allows important messages must be interrupted");
    }

    @Test
    void asyncJobDeliveredButNotInterruptedWhenNotRunning() {
        AtomicInteger deliveries = new AtomicInteger();
        AtomicInteger interrupts = new AtomicInteger();
        stubSession("async-idle", false, true, deliveries, interrupts);

        notifier.onFinished(job("async-idle", BuildTypeEnum.ASYNC));

        assertEquals(1, deliveries.get(), "an idle session still gets the result delivered");
        assertEquals(0, interrupts.get(), "nothing to interrupt when the session isn't running a turn");
    }

    @Test
    void asyncJobDeliveredButNotInterruptedWhenImportantMessagesDisallowed() {
        AtomicInteger deliveries = new AtomicInteger();
        AtomicInteger interrupts = new AtomicInteger();
        stubSession("async-quiet", true, false, deliveries, interrupts);

        notifier.onFinished(job("async-quiet", BuildTypeEnum.ASYNC));

        assertEquals(1, deliveries.get());
        assertEquals(0, interrupts.get(), "must not interrupt a session that opted out of important messages");
    }

    @Test
    void inlineJobIsNeverDelivered() {
        AtomicInteger deliveries = new AtomicInteger();
        AtomicInteger interrupts = new AtomicInteger();
        stubSession("inline-caller", true, true, deliveries, interrupts);

        notifier.onFinished(job("inline-caller", BuildTypeEnum.INLINE));

        assertEquals(0, deliveries.get(), "an inline caller already got its result from the tool call itself");
        assertEquals(0, interrupts.get());
    }

    @Test
    void sessionClosedCancellationIsSkipped() {
        AtomicInteger deliveries = new AtomicInteger();
        AtomicInteger interrupts = new AtomicInteger();
        stubSession("closed-session", true, true, deliveries, interrupts);

        BuildJob job = job("closed-session", BuildTypeEnum.ASYNC);
        job.requestCancel(BuildCancelReasonEnum.SESSION_CLOSED);

        notifier.onFinished(job);

        assertEquals(0, deliveries.get(), "the session that queued it is already gone");
        assertEquals(0, interrupts.get());
    }

    @Test
    void everyListenerIsDeliveredTheResultNotJustTheRequester() {
        AtomicInteger requesterDeliveries = new AtomicInteger();
        AtomicInteger requesterInterrupts = new AtomicInteger();
        AtomicInteger listenerDeliveries = new AtomicInteger();
        AtomicInteger listenerInterrupts = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<AbstractNotification> listenerNotification
                = new AtomicReference<>();
        stubSession("requester", true, true, requesterDeliveries, requesterInterrupts);
        stubSession("listener", true, true, listenerDeliveries, listenerInterrupts, listenerNotification);
        BuildJob job = job("requester", BuildTypeEnum.ASYNC);
        job.addListener("listener");

        notifier.onFinished(job);

        assertEquals(1, requesterDeliveries.get(), "the requester still gets its own result");
        assertEquals(1, listenerDeliveries.get(), "an AI that joined this build must get the result too");
        assertNotNull(listenerNotification.get(), "the listener's delivered notification must be captured");
        // This fixture has no log-path line, so copying deliberately leaves its result unchanged. The discriminating
        // regression guard for forwarded listener text is BuildCompletionNotificationTest's explicit-text case.
        assertEquals(SpooledLogCopier.copyForSession("listener", BuildReportFormatter.aiResult(job)),
                     listenerNotification.get().agentOnlyText(),
                     "the listener's delivered notification must contain the text produced for it");
        assertEquals(1, listenerInterrupts.get(), "a listener is interrupted like any important message");
    }

    @Test
    void anInlineBuildDeliversToItsListenersButNotToItsOwnCaller() {
        AtomicInteger callerDeliveries = new AtomicInteger();
        AtomicInteger joinerDeliveries = new AtomicInteger();
        AtomicInteger interrupts = new AtomicInteger();
        stubSession("inline-caller", true, true, callerDeliveries, interrupts);
        stubSession("joiner", true, true, joinerDeliveries, interrupts);
        BuildJob job = job("inline-caller", BuildTypeEnum.INLINE);
        job.addListener("joiner");

        notifier.onFinished(job);

        assertEquals(0, callerDeliveries.get(), "an inline caller already got its result from the tool call itself");
        assertEquals(1, joinerDeliveries.get(), "the joiner was given a 'joined' reply, so this message is its only result");
    }

    @Test
    void missingSessionIsSilentlyIgnored() {
        assertDoesNotThrow(() -> notifier.onFinished(job("no-such-session", BuildTypeEnum.ASYNC)));
    }
}
