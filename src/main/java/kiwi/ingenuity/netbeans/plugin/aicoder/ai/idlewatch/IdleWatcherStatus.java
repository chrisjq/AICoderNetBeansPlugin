package kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch;

import java.time.Instant;

/**
 * The live state of a {@link IdleWatcher}: the watcher itself plus where its clock currently stands. A snapshot, taken
 * atomically under the registry's lock, so a caller never sees a half-updated pair.
 *
 * @param idleSince when the target most recently became idle, or null while the target is busy (the clock is stopped
 * while a turn is in progress)
 * @param dueAt when the watcher will fire if the target stays idle that long, or null while the target is busy
 * @param firedThisIdlePeriod whether this recurring watcher has already fired during the target's current idle period;
 * a recurring watcher fires at most once per idle period, and re-arms only after the target starts and finishes another
 * turn
 */
public record IdleWatcherStatus(IdleWatcher watcher, Instant idleSince, Instant dueAt, boolean firedThisIdlePeriod) {

}
