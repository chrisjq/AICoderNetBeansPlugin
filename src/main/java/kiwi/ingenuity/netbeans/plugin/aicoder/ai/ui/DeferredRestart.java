package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.function.BooleanSupplier;

/**
 * Runs a restart only while the session is idle: at once if it is, otherwise as soon as the work in flight ends. A
 * restart is never run mid-turn, and a request made while busy is remembered until then.
 *
 * <p>
 * It also tracks the restart itself. From {@link #restartStarted()} until {@link #restartFinished} the session counts
 * as busy and {@link #isRestartInFlight()} is true, so the UI can hold input and stash submits while the process is
 * down, and then let the conversation be resumed BEFORE anything is replayed into it. Event-dispatch thread only.
 */
final class DeferredRestart {

    private final BooleanSupplier busy;
    private final Runnable restart;
    private boolean pending;
    private boolean restartInFlight;

    DeferredRestart(BooleanSupplier busy, Runnable restart) {
        this.busy = busy;
        this.restart = restart;
    }

    private boolean isBusy() {
        return restartInFlight || busy.getAsBoolean();
    }

    /**
     * Restarts now if idle; otherwise remembers the request for {@link #onMaybeIdle()}.
     */
    void request() {
        if (isBusy()) {
            pending = true;
            return;
        }
        pending = false;
        restart.run();
    }

    /**
     * Called wherever the session may just have gone idle. Runs a remembered restart if nothing is in flight any more;
     * otherwise leaves it remembered.
     */
    void onMaybeIdle() {
        if (pending && !isBusy()) {
            pending = false;
            restart.run();
        }
    }

    /**
     * Forgets a remembered restart, for a backend that is gone: its next start already picks up the new tools.
     */
    void cancel() {
        pending = false;
    }

    boolean isPending() {
        return pending;
    }

    /**
     * The restart action calls this as it begins to take the process down.
     */
    void restartStarted() {
        restartInFlight = true;
    }

    /**
     * True from {@link #restartStarted()} until {@link #restartFinished}: the process is being replaced and the
     * conversation has not yet been resumed.
     */
    boolean isRestartInFlight() {
        return restartInFlight;
    }

    /**
     * Clears the restart gate without resuming or replaying after a failed replacement start.
     */
    boolean restartFailed() {
        restartInFlight = false;
        return false;
    }

    /**
     * Ends the restart. {@code resume} runs while the restart still counts as in flight, so nothing can be submitted
     * into the new process before the conversation is resumed. A failed resume clears the gate but does not replay a
     * stashed prompt: replaying then would send it into a fresh conversation.
     *
     * @return true only when the conversation resumed and the replay completed
     */
    boolean restartFinished(Runnable resume, Runnable replay) {
        try {
            resume.run();
        }
        catch (RuntimeException | Error e) {
            return false;
        }
        finally {
            restartInFlight = false;
        }
        replay.run();
        return true;
    }
}
