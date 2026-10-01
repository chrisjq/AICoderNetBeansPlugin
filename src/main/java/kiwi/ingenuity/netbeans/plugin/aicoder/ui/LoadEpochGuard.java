package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

/**
 * Guards against a stale async load overwriting a newer one when two results arrive out of order —
 * {@code SessionPickerDialog}'s loadAll calls run on a pool of more than one thread, so completion order is
 * not guaranteed to match request order. Call {@link #next()} on the EDT when starting a new load and check
 * {@link #isCurrent(long)} in its completion callback (also on the EDT) before applying the result; both
 * methods are meant to be called only from the EDT, so neither needs its own synchronization.
 */
final class LoadEpochGuard {

    private long current;

    /**
     * Starts a new epoch and returns it — the caller must apply its eventual result only if
     * {@link #isCurrent(long)} still holds for the returned value.
     */
    long next() {
        return ++current;
    }

    /**
     * Whether {@code epoch} is still the most recently started one — false once a later {@link #next()} call
     * has superseded it.
     */
    boolean isCurrent(long epoch) {
        return epoch == current;
    }
}
