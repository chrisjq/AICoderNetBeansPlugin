package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

/**
 * A running session's live view of what detection and the session have learned. Listeners are told after every change
 * (new facts from the poller, the end of a turn, a compaction) and may be called from any thread; they read
 * {@link #snapshot()} themselves.
 */
public interface DetectedSnapshotSource {

    DetectedSnapshot snapshot();

    void addListener(Runnable listener);

    void removeListener(Runnable listener);
}
