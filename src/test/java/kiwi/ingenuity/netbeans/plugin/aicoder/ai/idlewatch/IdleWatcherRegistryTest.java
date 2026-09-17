package kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link IdleWatcherRegistry} directly against a fake {@link Clock}, a recording {@link IdleWatchDelivery} and a
 * fake {@link IdleSessionProbe}, with {@code useSchedulerThread=false} so every check is driven explicitly by
 * {@link IdleWatcherRegistry#checkNow()} rather than a real background thread.
 */
class IdleWatcherRegistryTest {

    private static final Instant BASE = Instant.parse("2026-09-17T01:00:00Z");
    private static final Duration MIN = IdleWatcherRegistry.MIN_TIMEOUT;

    private MutableClock clock;
    private FakeProbe probe;
    private RecordingDelivery delivery;
    private IdleWatcherRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BASE);
        probe = new FakeProbe();
        delivery = new RecordingDelivery();
        registry = new IdleWatcherRegistry(clock, delivery, probe, false);
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    private static IdleWatcherStatus onlyStatus(IdleWatcherRegistry registry, String watcherSessionId) {
        List<IdleWatcherStatus> statuses = registry.list(watcherSessionId);
        assertEquals(1, statuses.size(), () -> "expected exactly one watcher for " + watcherSessionId + ": " + statuses);
        return statuses.get(0);
    }

    private static IdleWatcherStatus onlyStatusFor(IdleWatcherRegistry registry, String watcherSessionId,
                                                   String watcherId) {
        return registry.list(watcherSessionId).stream()
                .filter(s -> s.watcher().id().equals(watcherId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no watcher " + watcherId + " for " + watcherSessionId));
    }

    @Test
    void createValidatesBlankIdsSelfWatchTimeoutAndClosedTarget() {
        probe.open.add("target");

        assertThrows(IllegalArgumentException.class, () -> registry.create(null, "target", MIN, false, false, null));
        assertThrows(IllegalArgumentException.class, () -> registry.create("", "target", MIN, false, false, null));
        assertThrows(IllegalArgumentException.class, () -> registry.create("   ", "target", MIN, false, false, null));
        assertThrows(IllegalArgumentException.class, () -> registry.create("watcher", null, MIN, false, false, null));
        assertThrows(IllegalArgumentException.class, () -> registry.create("watcher", "", MIN, false, false, null));
        assertThrows(IllegalArgumentException.class, () -> registry.create("same", "same", MIN, false, false, null));
        assertThrows(IllegalArgumentException.class, () -> registry.create("watcher", "target", null, false, false, null));
        assertThrows(IllegalArgumentException.class,
                     () -> registry.create("watcher", "target", MIN.minusMinutes(1), false, false, null));
        assertThrows(IllegalArgumentException.class,
                     () -> registry.create("watcher", "target-not-open", MIN, false, false, null));

        assertTrue(registry.list("watcher").isEmpty(), "no rejected create leaves any partial state behind");
        assertTrue(registry.list("same").isEmpty());
    }

    @Test
    void theInterruptFlagIsCarriedThroughOnTheArmedWatcher() {
        probe.open.add("target-a");
        probe.open.add("target-b");

        IdleWatcher interrupting = registry.create("watcher", "target-a", MIN, false, true, null);
        IdleWatcher quiet = registry.create("watcher", "target-b", MIN, false, false, null);

        assertTrue(interrupting.interrupt());
        assertFalse(quiet.interrupt());
        assertTrue(onlyStatusFor(registry, "watcher", interrupting.id()).watcher().interrupt());
        assertFalse(onlyStatusFor(registry, "watcher", quiet.id()).watcher().interrupt());
    }

    @Test
    void armingAgainstABusyTargetLeavesTheClockStoppedUntilItGoesIdle() {
        probe.open.add("target");
        probe.running.add("target");

        registry.create("watcher", "target", MIN, false, false, null);

        IdleWatcherStatus whileBusy = onlyStatus(registry, "watcher");
        assertNull(whileBusy.idleSince());
        assertNull(whileBusy.dueAt());
        assertNull(registry.nextCheckAt());

        clock.advance(Duration.ofMinutes(3));
        Instant idleAt = clock.instant();
        registry.onSessionIdle("target");

        IdleWatcherStatus afterIdle = onlyStatus(registry, "watcher");
        assertEquals(idleAt, afterIdle.idleSince());
        assertEquals(idleAt.plus(MIN), afterIdle.dueAt());
    }

    @Test
    void armingAgainstAnAlreadyIdleTargetWaitsTheFullTimeoutFromArmingButReportsTheTrueIdleSince() {
        probe.open.add("target");
        registry.onSessionIdle("target");
        Instant idleSince = clock.instant();

        clock.advance(Duration.ofMinutes(10));
        Instant createdAt = clock.instant();
        registry.create("watcher", "target", Duration.ofMinutes(5), false, false, null);

        IdleWatcherStatus status = onlyStatus(registry, "watcher");
        assertEquals(idleSince, status.idleSince(), "the notice must still report when the target truly went idle");
        assertEquals(createdAt.plus(Duration.ofMinutes(5)), status.dueAt(),
                     "due must be a full timeout from ARMING, not from the target's true (much earlier) idle-since");

        // At the OLD rule's due instant (idleSince + timeout — already in the past relative to createdAt) nothing
        // fires: a watcher must always wait its full timeout from when it was armed.
        clock.set(idleSince.plus(Duration.ofMinutes(5)));
        registry.checkNow();
        assertTrue(delivery.calls.isEmpty(), "must not fire before a full timeout has elapsed since arming");

        // At the actual due instant (createdAt + timeout) it fires, still reporting the true, earlier idle-since.
        clock.set(createdAt.plus(Duration.ofMinutes(5)));
        registry.checkNow();
        assertEquals(1, delivery.calls.size());
        assertEquals(idleSince, delivery.calls.get(0).idleSince(), "delivered idleSince must be the true, earlier one");
    }

    @Test
    void repeatedOnSessionIdleDoesNotMoveIdleSinceAndBusyThenIdleResetsIt() {
        probe.open.add("target");
        registry.create("watcher", "target", MIN, false, false, null);
        Instant t0 = onlyStatus(registry, "watcher").idleSince();
        assertNotNull(t0);

        clock.advance(Duration.ofMinutes(1));
        registry.onSessionIdle("target");
        assertEquals(t0, onlyStatus(registry, "watcher").idleSince(), "a repeated idle call must not move idle-since");

        registry.onSessionBusy("target");
        IdleWatcherStatus busy = onlyStatus(registry, "watcher");
        assertNull(busy.idleSince());
        assertNull(busy.dueAt());

        clock.advance(Duration.ofMinutes(2));
        Instant t2 = clock.instant();
        registry.onSessionIdle("target");
        IdleWatcherStatus restarted = onlyStatus(registry, "watcher");
        assertEquals(t2, restarted.idleSince());
        assertEquals(t2.plus(MIN), restarted.dueAt());
    }

    @Test
    void checkNowDeliversNothingBeforeDueAndExactlyOnceAtDueThenRemovesTheOneshot() {
        probe.open.add("target");
        probe.names.put("target", "Target Display Name");
        registry.create("watcher", "target", Duration.ofMinutes(5), false, false, null);
        Instant idleSince = onlyStatus(registry, "watcher").idleSince();

        clock.set(idleSince.plus(Duration.ofMinutes(5)).minusSeconds(1));
        registry.checkNow();
        assertTrue(delivery.calls.isEmpty());
        assertEquals(1, registry.list("watcher").size());

        clock.set(idleSince.plus(Duration.ofMinutes(5)));
        registry.checkNow();
        assertEquals(1, delivery.calls.size());
        assertEquals(IdleWatchEventEnum.IDLE, delivery.calls.get(0).event());
        assertEquals(idleSince, delivery.calls.get(0).idleSince());
        assertEquals("Target Display Name", delivery.calls.get(0).targetName(),
                     "the name captured from probe.displayName() at arming time must reach delivery");
        assertTrue(registry.list("watcher").isEmpty());
    }

    @Test
    void recurringFiresOnceThenRearmsOnlyAfterBusyThenIdleAgain() {
        probe.open.add("target");
        registry.create("watcher", "target", MIN, true, false, null);
        Instant firstIdleSince = onlyStatus(registry, "watcher").idleSince();

        clock.advance(MIN);
        registry.checkNow();
        assertEquals(1, delivery.calls.size());
        IdleWatcherStatus afterFire = onlyStatus(registry, "watcher");
        assertTrue(afterFire.firedThisIdlePeriod());
        assertNull(afterFire.dueAt());

        clock.advance(Duration.ofHours(1));
        registry.checkNow();
        assertEquals(1, delivery.calls.size(), "must not fire again while still in the same idle period");

        registry.onSessionBusy("target");
        registry.onSessionIdle("target");
        Instant secondIdleSince = clock.instant();
        assertFalse(secondIdleSince.equals(firstIdleSince));
        IdleWatcherStatus rearmed = onlyStatus(registry, "watcher");
        assertFalse(rearmed.firedThisIdlePeriod());

        clock.advance(MIN);
        registry.checkNow();
        assertEquals(2, delivery.calls.size());
        assertEquals(secondIdleSince, delivery.calls.get(1).idleSince());
    }

    @Test
    void deliveryReturningFalseRemovesTheWatcherEvenWhenRecurring() {
        probe.open.add("target");
        IdleWatcher watcher = registry.create("watcher", "target", MIN, true, false, null);
        delivery.returnFalseFor.add(watcher.id());

        clock.advance(MIN);
        registry.checkNow();

        assertEquals(1, delivery.calls.size());
        assertTrue(registry.list("watcher").isEmpty());
    }

    @Test
    void deliveryThrowingForOneWatcherDoesNotPreventAnotherDueWatcherFromFiring() {
        probe.open.add("target-a");
        probe.open.add("target-b");
        IdleWatcher failing = registry.create("watcher", "target-a", MIN, false, false, null);
        IdleWatcher ok = registry.create("watcher", "target-b", MIN, false, false, null);
        delivery.throwFor.add(failing.id());

        clock.advance(MIN);
        registry.checkNow();

        assertEquals(2, delivery.calls.size(), "both due watchers must be attempted despite one throwing");
        assertTrue(registry.list("watcher").isEmpty(), "the failing watcher is removed (delivered=false) and the ok"
                   + " one-shot is removed after firing");
        assertTrue(delivery.calls.stream().anyMatch(c -> c.watcherId().equals(ok.id())));
        assertTrue(delivery.calls.stream().anyMatch(c -> c.watcherId().equals(failing.id())));
    }

    /**
     * The specific race the registry must not lose: the delivery callback itself reports the target starting a turn (as
     * a live turn-boundary hook would) WHILE a recurring watcher's IDLE delivery is still in flight. The registry
     * re-checks each due watcher under the lock immediately before applying the outcome, so this must not be left
     * marked fired — that would silently swallow the notice that ought to fire on the target's NEXT idle period.
     */
    @Test
    void targetGoingBusyDuringDeliveryOfARecurringWatcherIsNotLeftMarkedFired() {
        probe.open.add("target");
        IdleWatcher watcher = registry.create("watcher", "target", MIN, true, false, null);
        delivery.midDeliveryWatcherId = watcher.id();
        delivery.midDeliveryCallback = () -> registry.onSessionBusy("target");

        clock.advance(MIN);
        registry.checkNow();

        assertEquals(1, delivery.calls.size());
        IdleWatcherStatus after = onlyStatus(registry, "watcher");
        assertFalse(after.firedThisIdlePeriod(),
                    "the mid-delivery busy transition must win over the stale fired-flag write");
        assertNull(after.dueAt());
        assertNull(after.idleSince());

        registry.onSessionIdle("target");
        Instant secondIdleSince = clock.instant();
        clock.advance(MIN);
        registry.checkNow();

        assertEquals(2, delivery.calls.size(), "the watcher must be able to fire again after the next idle period");
        assertEquals(secondIdleSince, delivery.calls.get(1).idleSince());
    }

    @Test
    void cancelOnlyRemovesTheCallersOwnWatcherAndIsIdempotent() {
        probe.open.add("target");
        IdleWatcher watcher = registry.create("watcher", "target", MIN, false, false, null);

        assertFalse(registry.cancel("someone-else", watcher.id()), "another session's id must be refused");
        assertEquals(1, registry.list("watcher").size());

        assertFalse(registry.cancel("watcher", "idle-watch-does-not-exist"));

        assertTrue(registry.cancel("watcher", watcher.id()));
        assertTrue(registry.list("watcher").isEmpty());

        assertFalse(registry.cancel("watcher", watcher.id()), "cancelling twice must not report success twice");
    }

    @Test
    void listReturnsOnlyTheCallersOwnWatchersInCreationOrder() {
        probe.open.add("target");
        IdleWatcher first = registry.create("watcher-A", "target", MIN, false, false, null);
        registry.create("watcher-B", "target", MIN, false, false, null);
        IdleWatcher third = registry.create("watcher-A", "target", MIN.plusMinutes(1), false, false, null);

        List<String> idsForA = registry.list("watcher-A").stream().map(s -> s.watcher().id()).toList();
        assertEquals(List.of(first.id(), third.id()), idsForA);
        assertEquals(1, registry.list("watcher-B").size());
    }

    @Test
    void onSessionClosedForTheWatcherRemovesItsWatchersSilently() {
        probe.open.add("target");
        registry.create("watcher", "target", MIN, false, false, null);

        registry.onSessionClosed("watcher");

        assertTrue(registry.list("watcher").isEmpty());
        assertTrue(delivery.calls.isEmpty(), "the watcher's own session closing delivers nothing — there is no one"
                   + " left to tell");
    }

    @Test
    void onSessionClosedForTheTargetDeliversTargetClosedOncePerWatcherAndRemovesThem() {
        probe.open.add("target");
        IdleWatcher oneshot = registry.create("watcher-A", "target", MIN, false, false, null);
        IdleWatcher recurring = registry.create("watcher-B", "target", MIN, true, false, null);

        registry.onSessionClosed("target");

        assertEquals(2, delivery.calls.size());
        assertTrue(delivery.calls.stream().allMatch(c -> c.event() == IdleWatchEventEnum.TARGET_CLOSED));
        assertTrue(delivery.calls.stream().anyMatch(c -> c.watcherId().equals(oneshot.id())));
        assertTrue(delivery.calls.stream().anyMatch(c -> c.watcherId().equals(recurring.id())));
        assertTrue(registry.list("watcher-A").isEmpty());
        assertTrue(registry.list("watcher-B").isEmpty());
    }

    @Test
    void nextCheckAtIsNullWithNoRunningClocksThenTheEarliestDueBeforeAnyCheck() {
        assertNull(registry.nextCheckAt());

        probe.open.add("target");
        registry.create("watcher", "target", MIN, false, false, null);
        Instant idleSince = onlyStatus(registry, "watcher").idleSince();

        assertEquals(idleSince.plus(MIN), registry.nextCheckAt(), "before any checkNow(), the earliest due wins"
                     + " outright");
    }

    @Test
    void nextCheckAtFloorsToTenSecondsAfterACheckWhenDueIsSooner() {
        probe.open.add("target");
        registry.create("watcher", "target", MIN, false, false, null);
        Instant due = onlyStatus(registry, "watcher").dueAt();

        Instant checkAt = due.minusSeconds(3);
        clock.set(checkAt);
        registry.checkNow();

        assertEquals(checkAt.plus(IdleWatcherRegistry.MIN_CHECK_INTERVAL), registry.nextCheckAt(),
                     "due in 3s is sooner than the 10s floor, so the floor wins");
    }

    @Test
    void nextCheckAtUsesTheActualDueTimeWhenItIsFartherThanTheFloor() {
        probe.open.add("target");
        registry.create("watcher", "target", MIN, false, false, null);
        Instant due = onlyStatus(registry, "watcher").dueAt();

        Instant checkAt = due.minus(Duration.ofMinutes(2));
        clock.set(checkAt);
        registry.checkNow();

        assertEquals(due, registry.nextCheckAt(), "due 2 minutes out is farther than the 10s floor, so due wins");
    }

    @Test
    void shutdownClearsWatchersAndStopsTheScheduler() {
        probe.open.add("target");
        IdleWatcherRegistry live = new IdleWatcherRegistry(clock, delivery, probe, true);
        try {
            live.create("watcher", "target", MIN, false, false, null);
            assertEquals(1, live.list("watcher").size());
            assertTrue(live.hasScheduler(), "arming an idle-target watcher must start the scheduler");

            live.shutdown();

            assertTrue(live.list("watcher").isEmpty(), "shutdown must clear every watcher");
            assertFalse(live.hasScheduler(), "shutdown must tear the scheduler down");
            assertNull(live.nextCheckAt());
            assertTrue(delivery.calls.isEmpty(), "shutdown itself must deliver nothing");
        }
        finally {
            live.shutdown();
        }
    }

    @Test
    void onSessionIdleAfterShutdownDoesNotRecreateTheScheduler() {
        probe.open.add("target");
        IdleWatcherRegistry live = new IdleWatcherRegistry(clock, delivery, probe, true);
        try {
            live.create("watcher", "target", MIN, false, false, null);
            assertTrue(live.hasScheduler());

            live.shutdown();
            assertFalse(live.hasScheduler());

            live.onSessionIdle("target");

            assertFalse(live.hasScheduler(), "a late idle notice must never recreate the scheduler");
            assertTrue(live.list("watcher").isEmpty(), "a late idle notice must not resurrect state");
            assertTrue(delivery.calls.isEmpty());
        }
        finally {
            live.shutdown();
        }
    }

    @Test
    void shutdownIsIdempotent() {
        probe.open.add("target");
        registry.create("watcher", "target", MIN, false, false, null);

        registry.shutdown();
        registry.shutdown();

        assertTrue(registry.list("watcher").isEmpty());
        assertNull(registry.nextCheckAt());
        assertTrue(delivery.calls.isEmpty());
    }

    @Test
    void createAfterShutdownThrows() {
        probe.open.add("target");

        registry.shutdown();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                                                () -> registry.create("watcher", "target", MIN, false, false, null));
        assertTrue(ex.getMessage().toLowerCase().contains("shut down"));
        assertTrue(registry.list("watcher").isEmpty(), "the refused create must leave no watcher behind");
    }

    @Test
    void shutdownDeliversNoNoticesEvenForDueWatchers() {
        probe.open.add("target");
        registry.create("watcher", "target", MIN, false, false, null);
        clock.advance(MIN);

        registry.shutdown();

        assertTrue(delivery.calls.isEmpty(), "shutdown must drop a due watcher without delivering");
        registry.checkNow();
        assertTrue(delivery.calls.isEmpty(), "a post-shutdown check has nothing left to deliver");
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeProbe implements IdleSessionProbe {

        final Set<String> open = new HashSet<>();
        final Set<String> running = new HashSet<>();
        final Map<String, String> names = new HashMap<>();

        @Override
        public boolean isOpen(String sessionId) {
            return open.contains(sessionId);
        }

        @Override
        public boolean isRunning(String sessionId) {
            return running.contains(sessionId);
        }

        @Override
        public String displayName(String sessionId) {
            return names.getOrDefault(sessionId, sessionId);
        }
    }

    private static final class RecordingDelivery implements IdleWatchDelivery {

        final List<Delivery> calls = new ArrayList<>();
        final Set<String> returnFalseFor = new HashSet<>();
        final Set<String> throwFor = new HashSet<>();
        String midDeliveryWatcherId;
        Runnable midDeliveryCallback;

        @Override
        public boolean deliver(IdleWatcher watcher, IdleWatchEventEnum event, Instant idleSince, String targetName) {
            calls.add(new Delivery(watcher.id(), event, idleSince, targetName));
            if (watcher.id().equals(midDeliveryWatcherId) && midDeliveryCallback != null) {
                midDeliveryCallback.run();
            }
            if (throwFor.contains(watcher.id())) {
                throw new RuntimeException("simulated delivery failure for " + watcher.id());
            }
            return !returnFalseFor.contains(watcher.id());
        }

        private record Delivery(String watcherId, IdleWatchEventEnum event, Instant idleSince, String targetName) {

        }
    }
}
