package kiwi.ingenuity.netbeans.plugin.aicoder.ai.permission;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.UnaryOperator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.MultiPermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.MultiPermissionItem;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;

/**
 * Owns one {@link MultiPermissionEvent} for the whole life of that batch: it walks the change set in the order the AI
 * supplied, accumulates the per-file decisions, produces the single aggregate reply, and renders the record written to
 * the message panel.
 *
 * <p>
 * Deliberately headless. There is no Swing here and none may be added: the state machine, the aggregate decision and
 * the log text are the parts that must be provably correct, and they are only provably correct if they can be unit
 * tested without a UI. The UI is thin glue that calls into this class.</p>
 *
 * <p>
 * Backend-neutral by design. It consumes neutral change items and produces a {@link PermissionDecision}; mapping that
 * onto a particular backend's reply vocabulary is the handler's job. No Codex types, no unified-diff assumptions, no
 * accept/decline/cancel strings appear here.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * The event's response future is completed <b>exactly once</b>, on each of <b>seven</b> exit paths — all accepted, any
 * rejected, {@link #rejectAll()}, {@link #autoAcceptAll()}, {@link #renderFailed(String)}, {@link #timedOut()} and
 * {@link #cancelled(Throwable)}. Missing one would leave the AI process blocked forever; completing twice would
 * silently lose a decision. The first call to reach an exit path wins and every later call is ignored, so two of them
 * arriving concurrently is safe — that race is real, not theoretical.</p>
 *
 * <p>
 * The timeout is <b>one deadline for the whole set</b>, not one per file. The UI arms it when the review starts, using
 * the same static bound a single-file diff gets, and calls {@link #timedOut()} if it expires — so a batch has the same
 * total human-attention budget as one diff, and each panel after the first inherits whatever is left of it. This class
 * owns no clock and no duration; it is told that the deadline passed. Expiry declines the WHOLE set, including files
 * the user had already accepted: a timeout is another way of not approving everything, and nothing is applied either
 * way because the write only happens after the single reply.</p>
 *
 * <p>
 * {@link #timedOut()} is deliberately distinct from {@link #rejectAll()} even though both decline the set. The
 * aggregate reply is the same; the log is not. Recording an expiry as "the user rejected the change set without
 * reviewing" would attribute to the user something they never did — the same reason "not seen" is kept separate from
 * "accepted".</p>
 *
 * <p>
 * {@link #cancelled(Throwable)} completes the future <b>exceptionally</b>, and that difference is load-bearing. The
 * backends distinguish "the user said no" from "this was interrupted" by exactly that: a normal denial lets the agent
 * continue its turn, while an exceptional completion interrupts the turn. The two must never be collapsed.</p>
 *
 * <p>
 * Rejection stops the review early. After a rejection there is no current item, and further {@link #accept()} or
 * {@link #reject()} calls are <b>ignored</b> rather than throwing — the UI may deliver a click that was already in
 * flight when the set was resolved, and that must be harmless rather than an exception on the event thread.</p>
 */
public final class MultiPermissionReview {

    private final MultiPermissionEvent event;
    private final List<MultiPermissionItem> items;
    private final ItemState[] states;

    /**
     * Renders a file path for display in the log. Injected rather than called directly, because the production renderer
     * resolves the open projects from the running IDE and this class must stay headless and testable.
     */
    private final UnaryOperator<String> pathRenderer;

    /**
     * Guards every transition so two exit paths arriving at once cannot both resolve the batch.
     */
    private final Object lock = new Object();

    /**
     * Index of the item awaiting a decision; equal to the item count once every file has been accepted.
     */
    private int cursor;

    private Outcome outcome;
    private String detailPath;
    private String log;

    /**
     * @param event the batch to review; its items are walked in the supplied order and never sorted
     * @param pathRenderer renders each file path for the log. The UI passes the same renderer the single-file edit
     * notification uses, so one file reads identically whichever panel showed it; tests pass identity or a stub. It is
     * a required parameter rather than an optional one so the UI cannot quietly fall back to raw paths and produce a
     * log that disagrees with the rest of the message panel.
     * @throws IllegalArgumentException if the event or renderer is null. The set is not checked for emptiness here:
     * {@link MultiPermissionEvent} refuses an empty item list in its own constructor, so a batch that reaches this
     * class always has at least one file. Guarding the contract rather than this consumer protects a second backend
     * that writes its own consumer instead of using this class.
     */
    public MultiPermissionReview(MultiPermissionEvent event, UnaryOperator<String> pathRenderer) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        if (pathRenderer == null) {
            throw new IllegalArgumentException("pathRenderer is required");
        }
        this.event = event;
        this.pathRenderer = pathRenderer;
        this.items = event.items();
        this.states = new ItemState[items.size()];
        Arrays.fill(this.states, ItemState.NOT_SEEN);
    }

    /**
     * The item awaiting a decision, or null once the review has finished by any route.
     */
    public MultiPermissionItem currentItem() {
        synchronized (lock) {
            return outcome != null ? null : items.get(cursor);
        }
    }

    /**
     * The 1-based number of the item awaiting a decision, matching the "File N" numbering in the log, or 0 once the
     * review has finished.
     */
    public int currentItemNumber() {
        synchronized (lock) {
            return outcome != null ? 0 : cursor + 1;
        }
    }

    /**
     * The user accepted the current file's diff. Advances to the next file, or resolves the whole set as accepted when
     * that was the last one. Ignored once the review has finished.
     */
    public void accept() {
        Outcome finished = null;
        synchronized (lock) {
            if (outcome != null) {
                return;
            }
            states[cursor] = ItemState.ACCEPTED;
            cursor++;
            if (cursor == items.size()) {
                finished = markFinished(Outcome.ACCEPTED, null);
            }
        }
        if (finished != null) {
            resolve(finished, null);
        }
    }

    /**
     * The user rejected the current file's diff. Stops early and declines the whole change set — there is no point
     * reviewing files whose fate is already settled. Ignored once the review has finished.
     */
    public void reject() {
        Outcome finished;
        synchronized (lock) {
            if (outcome != null) {
                return;
            }
            states[cursor] = ItemState.REJECTED;
            finished = markFinished(Outcome.REJECTED, items.get(cursor).filePath());
        }
        resolve(finished, null);
    }

    /**
     * The main panel's Reject button: decline the whole change set without opening or reading any diff. Files already
     * accepted keep that state in the log; everything else stays "not seen". Ignored once the review has finished.
     */
    public void rejectAll() {
        Outcome finished;
        synchronized (lock) {
            if (outcome != null) {
                return;
            }
            finished = markFinished(Outcome.REJECTED_ALL, null);
        }
        resolve(finished, null);
    }

    /**
     * Auto-accept: approve every file in the set without prompting. The batch is still owned and still logged file by
     * file — skipping this class for auto-accept, and so losing the per-file record, is the defect this replaces.
     * Ignored once the review has finished.
     */
    public void autoAcceptAll() {
        Outcome finished;
        synchronized (lock) {
            if (outcome != null) {
                return;
            }
            Arrays.fill(states, ItemState.ACCEPTED);
            cursor = items.size();
            finished = markFinished(Outcome.AUTO_ACCEPTED, null);
        }
        resolve(finished, null);
    }

    /**
     * A file's diff could not be rendered — stale content, line endings, a patch that will not apply. A file the user
     * cannot review is a file that cannot be approved, so the whole change set is declined. The failing path is named
     * in the log and in the deny message so a technical failure is distinguishable from a rejection the user chose.
     *
     * @param filePath the file whose diff could not be rendered; if blank, the file currently under review is named
     */
    public void renderFailed(String filePath) {
        Outcome finished;
        synchronized (lock) {
            if (outcome != null) {
                return;
            }
            String named = filePath != null && !filePath.isBlank()
                    ? filePath.trim()
                    : items.get(cursor).filePath();
            finished = markFinished(Outcome.RENDER_FAILED, named);
        }
        resolve(finished, null);
    }

    /**
     * The whole-set review deadline expired. Declines the entire change set, including files the user had already
     * accepted — a timeout is another way of not approving everything, and nothing is applied either way because the
     * write only happens after the single reply. Ignored once the review has finished.
     *
     * <p>
     * The caller owns the clock; this class only records that the deadline passed. Kept distinct from
     * {@link #rejectAll()} because the aggregate reply is the same but the log is not: an expiry must not be recorded
     * as a rejection the user chose.</p>
     */
    public void timedOut() {
        Outcome finished;
        synchronized (lock) {
            if (outcome != null) {
                return;
            }
            finished = markFinished(Outcome.TIMED_OUT, null);
        }
        resolve(finished, null);
    }

    /**
     * The turn was cancelled or the panel closed. Completes the response <b>exceptionally</b>, which is how the
     * backends tell an interruption from a deliberate "no" — a denial lets the agent continue its turn, an exceptional
     * completion interrupts it. Ignored once the review has finished.
     *
     * @param cause the reason; a {@link CancellationException} is supplied when null
     */
    public void cancelled(Throwable cause) {
        Outcome finished;
        synchronized (lock) {
            if (outcome != null) {
                return;
            }
            finished = markFinished(Outcome.CANCELLED, null);
        }
        resolve(finished, cause);
    }

    /**
     * True once the outcome is <b>settled</b> by any route: no further decision will be recorded, the log is final, and
     * no more diffs should be opened.
     *
     * <p>
     * The response future is completed immediately afterwards, outside the lock, so a caller that observes this as true
     * may briefly see {@code event.response().isDone()} still false. That window is deliberate and this method
     * deliberately does not close it by reporting the future instead:</p>
     *
     * <ul>
     * <li>Settled-outcome is the question the UI actually asks — "may I open the next diff?" — and the answer must be
     * no the instant the outcome is settled, not one moment later when the future happens to complete.</li>
     * <li>Reporting the future would create a second source of truth about a state the lock already owns. The internal
     * gate on every transition would still be {@code outcome != null}, so the two could disagree in exactly this
     * window.</li>
     * </ul>
     */
    public boolean isFinished() {
        synchronized (lock) {
            return outcome != null;
        }
    }

    /**
     * How the set ended, or null while the review is still running.
     */
    public Outcome outcome() {
        synchronized (lock) {
            return outcome;
        }
    }

    /**
     * The state recorded for one file, by 1-based file number as it appears in the log.
     */
    public ItemState stateOf(int fileNumber) {
        synchronized (lock) {
            if (fileNumber < 1 || fileNumber > states.length) {
                throw new IndexOutOfBoundsException("no file " + fileNumber + " in a set of " + states.length);
            }
            return states[fileNumber - 1];
        }
    }

    /**
     * The record written to the message panel: a header stating how the set ended, one line per file in the supplied
     * order, then a summary line for the whole request.
     *
     * @throws IllegalStateException if the review has not finished — the text is only meaningful once the outcome is
     * settled, and returning a provisional record would invite logging a decision that has not been made
     */
    public String log() {
        synchronized (lock) {
            if (outcome == null) {
                throw new IllegalStateException("the log is only valid once the review has finished");
            }
            return log;
        }
    }

    /**
     * Settles the outcome and builds the log while holding the lock, so exactly one caller can ever reach
     * {@link #resolve}. The future itself is completed outside the lock: completing it runs dependent callbacks on this
     * thread, and those must not be able to re-enter a locked review.
     */
    private Outcome markFinished(Outcome settled, String path) {
        outcome = settled;
        detailPath = path;
        log = buildLog();
        return settled;
    }

    private void resolve(Outcome settled, Throwable cause) {
        switch (settled) {
            case ACCEPTED, AUTO_ACCEPTED ->
                event.response().complete(PermissionDecision.allowed());
            case CANCELLED ->
                event.response().completeExceptionally(
                        cause != null ? cause : new CancellationException("multi-file review cancelled"));
            default ->
                event.response().complete(PermissionDecision.denied(denyMessage(settled)));
        }
    }

    private String denyMessage(Outcome settled) {
        return switch (settled) {
            case REJECTED ->
                "User rejected the change to " + detailPath + " — the whole change set was declined";
            case REJECTED_ALL ->
                "User rejected the change set without reviewing the diffs";
            case RENDER_FAILED ->
                "The diff for " + detailPath + " could not be rendered, so the whole change set was declined";
            case TIMED_OUT ->
                "Timed out waiting for the user to review the change set — the whole change set was declined";
            default ->
                "The change set was declined";
        };
    }

    /**
     * The log is what the user reads, so paths there go through the injected renderer. The deny message is not — it is
     * handed back to the AI process, which supplied those paths and identifies its own files by them.
     */
    private String render(String filePath) {
        String rendered = pathRenderer.apply(filePath);
        return rendered != null ? rendered : filePath;
    }

    private String buildLog() {
        StringBuilder sb = new StringBuilder(header()).append('\n');
        for (int i = 0; i < items.size(); i++) {
            sb.append("Edit: File ").append(i + 1).append(": ").append(render(items.get(i).filePath()))
                    .append(" - ").append(states[i].label()).append('\n');
        }
        return sb.append("MultiEdit: ").append(summary()).toString();
    }

    private String header() {
        return switch (outcome) {
            case ACCEPTED ->
                "User accepted";
            case AUTO_ACCEPTED ->
                "Auto accepted";
            case REJECTED ->
                "User rejected a diff";
            case REJECTED_ALL ->
                "User rejected the change set without reviewing";
            case RENDER_FAILED ->
                "Could not render the diff for " + render(detailPath);
            case TIMED_OUT ->
                "Timed out waiting for review";
            case CANCELLED ->
                "Review cancelled";
        };
    }

    private String summary() {
        return switch (outcome) {
            case ACCEPTED ->
                "accepted";
            case AUTO_ACCEPTED ->
                "auto-accepted";
            default ->
                "rejected";
        };
    }

    /**
     * How the whole change set ended. Null-valued (see {@link #outcome()}) while the review is still running.
     */
    public enum Outcome {
        /**
         * Every file was shown to the user and every one accepted.
         */
        ACCEPTED,
        /**
         * Auto-accept approved the whole set without prompting.
         */
        AUTO_ACCEPTED,
        /**
         * The user rejected one file's diff, which declines the whole set.
         */
        REJECTED,
        /**
         * The user rejected the set from the main panel, without opening any diff.
         */
        REJECTED_ALL,
        /**
         * A file's diff could not be rendered, which declines the whole set.
         */
        RENDER_FAILED,
        /**
         * The whole-set review deadline expired. Distinct from REJECTED_ALL so the log says what actually happened.
         */
        TIMED_OUT,
        /**
         * The turn was cancelled or the panel closed. Completes the future exceptionally.
         */
        CANCELLED
    }

    /**
     * The three per-file states. "Not seen" must never be conflated with "accepted" — recording that a file was never
     * shown, rather than omitting it or implying approval, is the point of the log.
     */
    public enum ItemState {
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        NOT_SEEN("not seen");

        private final String label;

        ItemState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
