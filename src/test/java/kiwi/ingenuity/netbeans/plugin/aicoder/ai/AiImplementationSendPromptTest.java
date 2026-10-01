package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * A user Send arrives on the EDT and every backend's sendPrompt can wait for a monitor that start() holds, so
 * {@link AiImplementation#sendPrompt} must only queue. The executor is a queue the test drains by hand, so
 * "not yet" and "in this order" are asserted directly rather than inferred from thread timing.
 */
class AiImplementationSendPromptTest {

    private static final class HandDrivenExecutor implements Executor {

        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        boolean rejecting;

        @Override
        public void execute(Runnable task) {
            if (rejecting) {
                throw new RejectedExecutionException("plugin is shutting down");
            }
            queued.add(task);
        }

        void runAll() {
            Runnable next;
            while ((next = queued.poll()) != null) {
                next.run();
            }
        }
    }

    private static final class FakeManager extends AiProcessManager {

        final List<String> sent = new ArrayList<>();
        final List<List<File>> sentDirs = new ArrayList<>();
        final List<InterruptTypeEnum> interrupts = new ArrayList<>();
        int stops;
        RuntimeException sendFailure;
        Runnable duringSend = () -> {
        };
        /**
         * Sets {@code processing} once {@link #duringSend} has run, the way a real manager does after it has
         * its monitor.
         */
        boolean startsTurn;
        /**
         * Grok and Ollama close on every Cancel, whether or not a turn is running.
         */
        boolean closesOnEveryCancel;
        private final AiProcessEventListener events;

        FakeManager(AiProcessEventListener listener) {
            super(listener);
            this.events = listener;
        }

        @Override
        public void start(String executableOrConfig, String modelOrConfig) {
        }

        @Override
        public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
            sent.add(text);
            sentDirs.add(projectDirs);
            duringSend.run();
            if (sendFailure != null) {
                throw sendFailure;
            }
            if (startsTurn) {
                processing = true;
            }
        }

        @Override
        public void interrupt(InterruptTypeEnum type) {
            interrupts.add(type);
            if (closesOnEveryCancel && type == InterruptTypeEnum.Cancel) {
                processing = false;
                events.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, "manager stopped"));
            }
        }

        @Override
        public void stop() {
            stops++;
        }

        @Override
        public void resumeSession(String existingSessionId) {
        }

        @Override
        public boolean isMcpActive() {
            return false;
        }
    }

    private static final class Fixture extends AiImplementation {

        final List<AiProcessEvent> events;
        final HandDrivenExecutor executor = new HandDrivenExecutor();
        final FakeManager manager;

        Fixture() {
            this(new CopyOnWriteArrayList<>());
        }

        private Fixture(List<AiProcessEvent> events) {
            super(AiTypeEnum.CLAUDE, events::add, null);
            this.events = events;
            this.manager = new FakeManager(events::add);
        }

        List<StatusEventTypeEnum> statusTypes() {
            return events.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast)
                    .map(StatusEvent::type).toList();
        }

        @Override
        protected AiProcessManager delegate() {
            return manager;
        }

        @Override
        protected Executor sessionControlExecutor() {
            return executor;
        }

        @Override
        public void setModel(String model) {
        }

        @Override
        public void onStarted(AiSessionHost session) {
        }

        @Override
        protected void afterStart() {
        }
    }

    @Test
    void sendPromptOnlyQueuesAndTheManagerIsReachedWhenTheQueueRuns() {
        Fixture f = new Fixture();
        File dir = new File("work");

        f.sendPrompt("hello", dir, List.of(dir));

        assertEquals(List.of(), f.manager.sent, "the manager's sendPrompt can block on its monitor, so it must not run on the caller");
        assertTrue(f.isBusy(), "a queued send is work the UI's Stop must wait for");
        assertTrue(f.isProcessing());

        f.executor.runAll();

        assertEquals(List.of("hello"), f.manager.sent);
        assertEquals(List.of(List.of(dir)), f.manager.sentDirs);
        assertFalse(f.isBusy(), "once handed over, the manager's own state is the only busy source");
        assertEquals(List.of(), f.statusTypes(), "a user turn sends no BUSY from here");
    }

    @Test
    void sendsRunOnTheSessionControlExecutorInOrderWithOtherWork() {
        Fixture f = new Fixture();
        List<String> order = new ArrayList<>();
        f.manager.duringSend = () -> order.add("send");

        f.sessionControlExecutor().execute(() -> order.add("recycle"));
        f.sendPrompt("hello", null, List.of());
        f.sessionControlExecutor().execute(() -> order.add("compact"));
        f.executor.runAll();

        assertEquals(List.of("recycle", "send", "compact"), order);
    }

    @Test
    void theProjectDirsAreCopiedAtSendTime() {
        Fixture f = new Fixture();
        List<File> dirs = new ArrayList<>(List.of(new File("a")));

        f.sendPrompt("hello", null, dirs);
        dirs.add(new File("b"));
        f.executor.runAll();

        assertEquals(List.of(List.of(new File("a"))), f.manager.sentDirs);
    }

    @Test
    void aManagerFailureClosesTheBusyLockWithExactlyOneFailed() {
        Fixture f = new Fixture();
        f.manager.sendFailure = new IllegalStateException("boom");

        f.sendPrompt("hello", null, List.of());
        f.executor.runAll();

        assertEquals(List.of(StatusEventTypeEnum.FAILED), f.statusTypes());
        StatusEvent failed = (StatusEvent) f.events.get(0);
        assertTrue(failed.text().contains("boom"), failed.text());
        assertFalse(f.isBusy());
    }

    @Test
    void cancelBeforeTheSendReachesTheManagerDropsItAndClosesWithOneStopped() {
        Fixture f = new Fixture();

        f.sendPrompt("hello", null, List.of());
        f.cancel();

        assertEquals(List.of(StatusEventTypeEnum.STOPPED), f.statusTypes());
        assertFalse(f.isBusy(), "the dropped send no longer holds the UI's lock");
        assertEquals(List.of(), f.manager.interrupts, "the manager is idle, so there is nothing for it to abort");

        f.executor.runAll();

        assertEquals(List.of(), f.manager.sent, "a send the user cancelled must never start a turn");
        assertEquals(List.of(StatusEventTypeEnum.STOPPED), f.statusTypes(), "the dropped send adds no second closer");
    }

    @Test
    void interruptCancelDropsAQueuedSendTheSameWayCancelDoes() {
        Fixture f = new Fixture();

        f.sendPrompt("hello", null, List.of());
        f.interrupt(InterruptTypeEnum.Cancel);
        f.executor.runAll();

        assertEquals(List.of(), f.manager.sent);
        assertEquals(List.of(StatusEventTypeEnum.STOPPED), f.statusTypes());
    }

    @Test
    void aMailInterruptLeavesTheQueuedSendAlone() {
        Fixture f = new Fixture();

        f.sendPrompt("hello", null, List.of());
        f.interrupt(InterruptTypeEnum.Mail);
        f.executor.runAll();

        assertEquals(List.of("hello"), f.manager.sent);
        assertEquals(List.of(), f.statusTypes());
        assertEquals(List.of(InterruptTypeEnum.Mail), f.manager.interrupts);
    }

    @Test
    void aSendAfterACancelIsNotDroppedByTheEarlierCancel() {
        Fixture f = new Fixture();

        f.sendPrompt("first", null, List.of());
        f.cancel();
        f.sendPrompt("second", null, List.of());
        f.executor.runAll();

        assertEquals(List.of("second"), f.manager.sent);
    }

    @Test
    void aSendInsideTheManagerStillCountsAsBusyUntilItReturns() {
        Fixture f = new Fixture();
        List<Boolean> busyDuring = new ArrayList<>();
        List<Boolean> processingDuring = new ArrayList<>();
        f.manager.duringSend = () -> {
            busyDuring.add(f.isBusy());
            processingDuring.add(f.isProcessing());
        };

        f.sendPrompt("hello", null, List.of());
        f.executor.runAll();

        assertEquals(List.of(true), busyDuring,
                "the manager has not set its own processing flag yet, so this is the only thing keeping the UI locked");
        assertEquals(List.of(true), processingDuring);
        assertFalse(f.isBusy(), "with the manager idle again nothing holds the lock");
    }

    @Test
    void aStopInTheGapBeforeTheManagerIsBusyReachesTheManagerExactlyOnceAndEndsWithOneStopped() {
        Fixture f = new Fixture();
        f.manager.closesOnEveryCancel = true;
        f.manager.startsTurn = true;
        List<Boolean> backendWasBusy = new ArrayList<>();
        f.manager.duringSend = () -> {
            backendWasBusy.add(f.isBusy());
            f.cancel();
        };

        f.sendPrompt("hello", null, List.of());
        f.executor.runAll();

        assertEquals(List.of(true), backendWasBusy, "the UI's snapshot must see the send, or it unlocks the input itself");
        assertEquals(List.of(InterruptTypeEnum.Cancel), f.manager.interrupts,
                "one Stop is one Cancel at the manager, not one now and one when the send returns");
        assertEquals(List.of(StatusEventTypeEnum.STOPPED), f.statusTypes(),
                "a second STOPPED would close the next turn's busy lock");
        assertFalse(f.manager.isProcessing(), "the turn the send started was aborted");
    }

    @Test
    void aTurnStartedAfterACancelledSendIsNotAbortedByTheEarlierCancel() {
        Fixture f = new Fixture();
        f.manager.closesOnEveryCancel = true;
        f.manager.startsTurn = true;
        f.manager.duringSend = f::cancel;

        f.sendPrompt("first", null, List.of());
        f.executor.runAll();
        f.manager.duringSend = () -> {
        };
        f.sendPrompt("second", null, List.of());
        f.executor.runAll();

        assertEquals(List.of("first", "second"), f.manager.sent);
        assertEquals(List.of(InterruptTypeEnum.Cancel), f.manager.interrupts);
        assertEquals(List.of(StatusEventTypeEnum.STOPPED), f.statusTypes());
        assertTrue(f.manager.isProcessing(), "the second turn is running and nothing has aborted it");
    }

    @Test
    void aStopAfterTheManagerIsBusyIsForwardedAtOnceAndNotRepeatedWhenTheSendReturns() {
        Fixture f = new Fixture();
        f.manager.closesOnEveryCancel = true;
        List<Integer> interruptsWhenCancelReturned = new ArrayList<>();
        f.manager.duringSend = () -> {
            f.manager.processing = true;
            f.cancel();
            interruptsWhenCancelReturned.add(f.manager.interrupts.size());
        };

        f.sendPrompt("hello", null, List.of());
        f.executor.runAll();

        assertEquals(List.of(1), interruptsWhenCancelReturned, "a running turn is aborted without waiting for the send");
        assertEquals(List.of(InterruptTypeEnum.Cancel), f.manager.interrupts);
        assertEquals(List.of(StatusEventTypeEnum.STOPPED), f.statusTypes());
    }

    @Test
    void aStopDuringASendTheManagerRefusesLeavesTheRefusalAsTheOnlyCloser() {
        Fixture f = new Fixture();
        f.manager.closesOnEveryCancel = true;
        f.manager.duringSend = f::cancel;

        f.sendPrompt("hello", null, List.of());
        f.executor.runAll();

        assertEquals(List.of(), f.manager.interrupts, "no turn started, so there is nothing to abort");
        assertEquals(List.of(), f.statusTypes(), "the manager's own refusal closes the lock, not a stray STOPPED");
        assertFalse(f.isBusy());
    }

    @Test
    void aStopHeldDuringASendThatThrowsAfterTheManagerWentBusyIsNotDeliveredBecauseFailedAlreadyClosed() {
        Fixture f = new Fixture();
        f.manager.closesOnEveryCancel = true;
        f.manager.sendFailure = new IllegalStateException("boom");
        f.manager.duringSend = () -> {
            f.cancel();
            f.manager.processing = true;
        };

        f.sendPrompt("hello", null, List.of());
        f.executor.runAll();

        assertEquals(List.of(), f.manager.interrupts, "FAILED already closed the send, so the held Cancel has nothing left to abort");
        assertEquals(List.of(StatusEventTypeEnum.FAILED), f.statusTypes(), "a second closer (STOPPED) would close the next turn's busy lock");
    }

    @Test
    void aStopWhileAnEarlierTurnRunsAndASendIsQueuedIsForwardedOnceAndClosedByTheManager() {
        Fixture f = new Fixture();
        f.manager.closesOnEveryCancel = true;
        f.manager.processing = true;

        f.sendPrompt("hello", null, List.of());
        f.cancel();
        f.executor.runAll();

        assertEquals(List.of(InterruptTypeEnum.Cancel), f.manager.interrupts);
        assertEquals(List.of(StatusEventTypeEnum.STOPPED), f.statusTypes(), "the manager's STOPPED is the closer");
        assertEquals(List.of(), f.manager.sent);
    }

    @Test
    void theHandoverCallbackRunsOnlyAfterTheManagerHasTheSend() {
        Fixture f = new Fixture();
        List<String> order = new ArrayList<>();
        f.manager.duringSend = () -> order.add("manager");

        f.sendPrompt("hello", null, List.of(), () -> order.add("handed over"), () -> order.add("not delivered"));

        assertEquals(List.of(), order, "queued is not delivered");
        f.executor.runAll();
        assertEquals(List.of("manager", "handed over"), order);
    }

    @Test
    void aSendDroppedByACancelIsReportedNotDeliveredAndNeverHandedOver() {
        Fixture f = new Fixture();
        List<String> ran = new ArrayList<>();
        f.sendPrompt("a", null, List.of(), () -> ran.add("handed over"), () -> ran.add("not delivered"));

        f.cancel();
        f.executor.runAll();

        assertEquals(List.of("not delivered"), ran, "the caller records 'delivered' only from the handover callback");
    }

    @Test
    void aSendDroppedByAStopIsReportedNotDelivered() {
        Fixture f = new Fixture();
        List<String> ran = new ArrayList<>();
        f.sendPrompt("a", null, List.of(), () -> ran.add("handed over"), () -> ran.add("not delivered"));

        f.stop();
        f.executor.runAll();

        assertEquals(List.of("not delivered"), ran);
    }

    @Test
    void aSendTheManagerRejectsIsReportedNotDeliveredOnce() {
        Fixture f = new Fixture();
        f.manager.sendFailure = new IllegalStateException("boom");
        List<String> ran = new ArrayList<>();

        f.sendPrompt("b", null, List.of(), () -> ran.add("handed over"), () -> ran.add("not delivered"));
        f.executor.runAll();

        assertEquals(List.of("not delivered"), ran);
    }

    @Test
    void aSendTheExecutorRefusesIsReportedNotDeliveredOnce() {
        Fixture f = new Fixture();
        f.executor.rejecting = true;
        List<String> ran = new ArrayList<>();

        f.sendPrompt("c", null, List.of(), () -> ran.add("handed over"), () -> ran.add("not delivered"));

        assertEquals(List.of("not delivered"), ran);
    }

    @Test
    void aCancelDuringTheSendDoesNotUndoAHandoverThatHappened() {
        Fixture f = new Fixture();
        f.manager.duringSend = f::cancel;
        List<String> ran = new ArrayList<>();

        f.sendPrompt("a", null, List.of(), () -> ran.add("handed over"), () -> ran.add("not delivered"));
        f.executor.runAll();

        assertEquals(List.of("handed over"), ran, "the prompt reached the manager, which is what 'delivered' means");
    }

    @Test
    void theBusyStateListenerFiresAfterTheSendNoLongerCountsAsBusy() {
        Fixture f = new Fixture();
        List<Boolean> busyWhenNotified = new ArrayList<>();
        f.setBusyStateListener(() -> busyWhenNotified.add(f.isBusy()));

        f.sendPrompt("hello", null, List.of());
        assertEquals(List.of(), busyWhenNotified);
        f.executor.runAll();

        assertEquals(List.of(false), busyWhenNotified,
                "a turn's closing event handled mid-send left the UI locked, so it must look again once released");
    }

    @Test
    void anExecutorThatRefusesTheSendClosesWithOneFailedAndLeavesNoStuckLock() {
        Fixture f = new Fixture();
        f.executor.rejecting = true;

        f.sendPrompt("hello", null, List.of());

        assertEquals(List.of(StatusEventTypeEnum.FAILED), f.statusTypes());
        assertTrue(((StatusEvent) f.events.get(0)).text().contains("plugin is shutting down"));
        assertFalse(f.isBusy(), "the refused send must not keep counting as queued");
        assertFalse(f.isProcessing());

        f.executor.rejecting = false;
        f.sendPrompt("again", null, List.of());
        f.executor.runAll();

        assertEquals(List.of("again"), f.manager.sent, "a later send still works");
    }

    @Test
    void stopDropsAQueuedSendWithoutAnyCloser() {
        Fixture f = new Fixture();

        f.sendPrompt("hello", null, List.of());
        f.stop();
        f.executor.runAll();

        assertEquals(1, f.manager.stops);
        assertEquals(List.of(), f.manager.sent);
        assertEquals(List.of(), f.statusTypes(), "stop() is closed by the backend's own EXITED, not from here");
        assertFalse(f.isBusy());
    }
}
