package kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch;

import java.time.Instant;

/**
 * Delivers an idle-watch event to the watcher session. Called by the registry with no lock held, so the implementation
 * is free to reach for whatever session plumbing it needs.
 *
 * @param targetName the display name of the target session, captured by the registry when the watcher was armed, so the
 * notice reads correctly even if the target leaves the registry (e.g. a session closing) before delivery
 *
 * @return false when the watcher session is gone or no longer allows the feature — the registry then removes that
 * watcher
 */
@FunctionalInterface
public interface IdleWatchDelivery {

    boolean deliver(IdleWatcher watcher, IdleWatchEventEnum event, Instant idleSince, String targetName);
}
