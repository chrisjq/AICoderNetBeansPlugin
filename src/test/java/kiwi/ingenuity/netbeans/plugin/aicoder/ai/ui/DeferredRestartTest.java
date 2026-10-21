package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DeferredRestartTest {

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicInteger restarts = new AtomicInteger();
    private final DeferredRestart deferred = new DeferredRestart(busy::get, restarts::incrementAndGet);

    @Test
    void anIdleSessionRestartsAtOnce() {
        deferred.request();

        assertEquals(1, restarts.get());
        assertFalse(deferred.isPending());
    }

    @Test
    void aRequestMidTurnDoesNotRestartButIsRemembered() {
        busy.set(true);

        deferred.request();

        assertEquals(0, restarts.get(), "never restart mid-turn");
        assertTrue(deferred.isPending());
    }

    @Test
    void theRememberedRestartRunsOnceWhenTheTurnEnds() {
        busy.set(true);
        deferred.request();

        busy.set(false);
        deferred.onMaybeIdle();
        deferred.onMaybeIdle();

        assertEquals(1, restarts.get(), "run exactly once, not on every later idle signal");
        assertFalse(deferred.isPending());
    }

    @Test
    void aStillBusySessionKeepsTheRestartRemembered() {
        busy.set(true);
        deferred.request();

        deferred.onMaybeIdle();

        assertEquals(0, restarts.get(), "e.g. a queued turn took over straight after the last one");
        assertTrue(deferred.isPending());

        busy.set(false);
        deferred.onMaybeIdle();
        assertEquals(1, restarts.get());
    }

    @Test
    void repeatedRequestsWhileBusyCollapseIntoOneRestart() {
        busy.set(true);
        deferred.request();
        deferred.request();
        deferred.request();

        busy.set(false);
        deferred.onMaybeIdle();

        assertEquals(1, restarts.get());
    }

    @Test
    void idleSignalsWithNothingRememberedDoNothing() {
        deferred.onMaybeIdle();

        assertEquals(0, restarts.get());
    }

    @Test
    void cancellingForgetsTheRememberedRestart() {
        busy.set(true);
        deferred.request();

        deferred.cancel();
        busy.set(false);
        deferred.onMaybeIdle();

        assertEquals(0, restarts.get(), "a dead backend's next start already has the new tools");
        assertFalse(deferred.isPending());
    }

    @Test
    void aRestartInFlightCountsAsBusySoASecondRequestWaitsInsteadOfDoubleStarting() {
        deferred.request();
        deferred.restartStarted();
        assertTrue(deferred.isRestartInFlight());

        deferred.request();

        assertEquals(1, restarts.get(), "no second restart while the first is still replacing the process");
        assertTrue(deferred.isPending());

        deferred.restartFinished(() -> {
        }, () -> {
                         });
        deferred.onMaybeIdle();
        assertEquals(2, restarts.get(), "the remembered request runs once the restart has finished");
    }

    @Test
    void theConversationIsResumedBeforeAnythingStashedIsReplayed() {
        List<String> order = new ArrayList<>();
        deferred.restartStarted();

        deferred.restartFinished(
                () -> order.add("resume inFlight=" + deferred.isRestartInFlight()),
                () -> order.add("replay inFlight=" + deferred.isRestartInFlight()));

        assertEquals(List.of("resume inFlight=true", "replay inFlight=false"), order,
                     "resume while still gated; the replay then sees an ordinary running session");
        assertFalse(deferred.isRestartInFlight());
    }

    @Test
    void aFailedResumeClearsTheGateAndKeepsTheStashedText() {
        List<String> order = new ArrayList<>();
        deferred.restartStarted();

        assertFalse(deferred.restartFinished(
                () -> {
                    throw new IllegalStateException("resume failed");
                },
                () -> order.add("replay")));

        assertTrue(order.isEmpty(), "a stashed prompt must not be replayed into an unresumed conversation");
        assertFalse(deferred.isRestartInFlight());
    }

    @Test
    void anIdleRequestWhileOneIsAlreadyPendingRunsOnceAndClearsThePending() {
        busy.set(true);
        deferred.request();
        assertTrue(deferred.isPending());

        busy.set(false);
        deferred.request();

        assertEquals(1, restarts.get());
        assertFalse(deferred.isPending(), "the pending request was satisfied by the restart that just ran");
        deferred.onMaybeIdle();
        assertEquals(1, restarts.get(), "and is not run a second time");
    }

    @Test
    void anIdleSignalDuringARestartInFlightLeavesThePendingRequestAlone() {
        busy.set(true);
        deferred.request();
        busy.set(false);
        deferred.restartStarted();

        deferred.onMaybeIdle();

        assertEquals(0, restarts.get(), "the process is still being replaced");
        assertTrue(deferred.isPending());
    }

    @Test
    void cancellingDuringARestartInFlightDropsThePendingRequestForGood() {
        deferred.restartStarted();
        deferred.request();
        assertTrue(deferred.isPending());

        deferred.cancel();
        deferred.restartFinished(() -> {
        }, () -> {
                         });
        deferred.onMaybeIdle();

        assertEquals(0, restarts.get());
        assertFalse(deferred.isPending());
        assertFalse(deferred.isRestartInFlight());
    }

    @Test
    void aFailingReplayStillClearsTheGateAfterAnOrdinaryResume() {
        List<String> order = new ArrayList<>();
        deferred.restartStarted();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> deferred.restartFinished(
                                                    () -> order.add("resume"),
                                                    () -> {
                                                        throw new IllegalStateException("replay failed");
                                                    }));

        assertEquals("replay failed", thrown.getMessage());
        assertEquals(List.of("resume"), order);
        assertFalse(deferred.isRestartInFlight(), "a failing replay must not leave input gated");
    }

    @Test
    void anAlreadyFailedReplacementClearsTheGateWithoutResumeOrReplay() {
        List<String> order = new ArrayList<>();
        deferred.restartStarted();

        assertFalse(deferred.restartFailed());
        assertFalse(deferred.isRestartInFlight());

        deferred.onMaybeIdle();
        assertTrue(order.isEmpty());
    }

    @Test
    void aRequestForABackendThatIsNotRunningIsDeclinedByTheRestartAndNothingStaysPending() {
        AtomicBoolean backendRunning = new AtomicBoolean(false);
        AtomicInteger started = new AtomicInteger();
        // Mirrors restartBackendForToolChange: a backend that is not running needs nothing, its next start has the tools.
        DeferredRestart notRunning = new DeferredRestart(busy::get, () -> {
                                                     restarts.incrementAndGet();
                                                     if (!backendRunning.get()) {
                                                         return;
                                                     }
                                                     started.incrementAndGet();
                                                 });

        notRunning.request();

        assertEquals(1, restarts.get(), "the restart action was asked");
        assertEquals(0, started.get(), "and declined: no process was replaced");
        assertFalse(notRunning.isPending());
        assertFalse(notRunning.isRestartInFlight(), "a declined restart never gates input");
        notRunning.onMaybeIdle();
        assertEquals(1, restarts.get(), "and it is not asked again on the next idle signal");
    }

    @Test
    void aFailingRestartIsNotRetriedOnTheNextIdleSignal() {
        DeferredRestart failing = new DeferredRestart(busy::get, () -> {
                                                  restarts.incrementAndGet();
                                                  throw new IllegalStateException("boom");
                                              });
        busy.set(true);
        failing.request();
        busy.set(false);

        assertThrows(IllegalStateException.class, failing::onMaybeIdle);
        failing.onMaybeIdle();

        assertEquals(1, restarts.get());
    }
}
