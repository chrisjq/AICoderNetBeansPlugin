package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * A newer load must always win over a stale one that happens to complete later — this is what stands between
 * SessionPickerDialog and a quick delete-then-reload showing outdated data if the two loads finish out of
 * request order.
 */
class LoadEpochGuardTest {

    @Test
    void theFirstEpochIsCurrentUntilASecondOneStarts() {
        LoadEpochGuard guard = new LoadEpochGuard();

        long first = guard.next();

        assertTrue(guard.isCurrent(first), "with no later call, the only epoch issued must still be current");
    }

    @Test
    void startingASecondLoadMakesTheFirstOneStale() {
        LoadEpochGuard guard = new LoadEpochGuard();

        long first = guard.next();
        long second = guard.next();

        assertFalse(guard.isCurrent(first), "an older epoch must no longer be current once a newer one started");
        assertTrue(guard.isCurrent(second), "the most recently started epoch must be current");
    }

    @Test
    void aStaleResultArrivingAfterANewerOneMustNotBeAppliedEvenThoughItFinishedLast() {
        LoadEpochGuard guard = new LoadEpochGuard();
        long stale = guard.next();
        long fresh = guard.next();

        // The fresh load's result is applied first (it finished first in this scenario)...
        assertTrue(guard.isCurrent(fresh));
        // ...and the stale one, even though it is the one arriving now, must be rejected.
        assertFalse(guard.isCurrent(stale),
                "a stale load's result must be rejected even if it is the one completing right now");
    }
}
