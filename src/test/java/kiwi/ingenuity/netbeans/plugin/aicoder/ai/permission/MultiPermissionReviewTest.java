package kiwi.ingenuity.netbeans.plugin.aicoder.ai.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.MultiPermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.MultiPermissionItem;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import org.junit.jupiter.api.Test;

/**
 * The batch review is the part of the multi-file feature that must be provably correct: it decides what the AI is told,
 * and a mistake either blocks the AI process forever or silently loses a decision. These tests drive it headlessly —
 * there is no Swing here, and if any is ever needed the class has gone out of scope.
 */
class MultiPermissionReviewTest {

    private static final UnaryOperator<String> RAW = UnaryOperator.identity();

    private static MultiPermissionEvent event(String... paths) {
        List<MultiPermissionItem> items = java.util.Arrays.stream(paths)
                .map(p -> new MultiPermissionItem(p, "content of " + p))
                .toList();
        return new MultiPermissionEvent(items, new CompletableFuture<>());
    }

    private static MultiPermissionReview review(String... paths) {
        return new MultiPermissionReview(event(paths), RAW);
    }

    private static PermissionDecision decisionOf(MultiPermissionEvent e) throws Exception {
        return e.response().get(2, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- exit path 1: every file accepted

    @Test
    void acceptingEveryFileAllowsTheWholeSet() throws Exception {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);

        assertFalse(e.response().isDone(), "the reply is held while the user reviews");
        r.accept();
        assertFalse(e.response().isDone(), "one file accepted is not the whole set");
        r.accept();

        assertTrue(r.isFinished());
        assertEquals(MultiPermissionReview.Outcome.ACCEPTED, r.outcome());
        assertTrue(decisionOf(e).allow());
    }

    // ---------------------------------------------------------------- exit path 2: a rejected file

    @Test
    void rejectingOneFileDeclinesTheWholeSetAndStopsEarly() throws Exception {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java", "/p/c.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);

        r.accept();
        r.reject();

        assertTrue(r.isFinished());
        assertNull(r.currentItem(), "there is nothing left to review once the set is declined");
        PermissionDecision decision = decisionOf(e);
        assertFalse(decision.allow());
        assertTrue(decision.message().contains("/p/b.java"),
                "the deny message names the file the user rejected: " + decision.message());
        assertEquals(MultiPermissionReview.ItemState.NOT_SEEN, r.stateOf(3),
                "file 3 was never shown, and that is not the same as accepted");
    }

    /**
     * A click already in flight when the batch resolved must not change the outcome or resolve the future a second
     * time. Ignoring it is deliberate: throwing here would surface an exception on the UI thread for a race the user
     * cannot avoid.
     */
    @Test
    void decisionsAfterARejectionAreHarmless() throws Exception {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);

        r.reject();
        String settledLog = r.log();

        r.accept();
        r.accept();
        r.reject();
        r.rejectAll();
        r.autoAcceptAll();
        r.timedOut();
        r.renderFailed("/p/b.java");
        r.cancelled(new IllegalStateException("late"));

        assertEquals(MultiPermissionReview.Outcome.REJECTED, r.outcome());
        assertEquals(settledLog, r.log(), "a late call must not rewrite the record");
        assertFalse(decisionOf(e).allow());
    }

    // ---------------------------------------------------------------- exit path 3: main-panel reject

    @Test
    void rejectAllDeclinesWithoutReviewingAnything() throws Exception {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);

        r.rejectAll();

        assertEquals(MultiPermissionReview.Outcome.REJECTED_ALL, r.outcome());
        assertEquals(MultiPermissionReview.ItemState.NOT_SEEN, r.stateOf(1));
        assertEquals(MultiPermissionReview.ItemState.NOT_SEEN, r.stateOf(2));
        assertFalse(decisionOf(e).allow());
        assertEquals("""
                     User rejected the change set without reviewing
                     Edit: File 1: /p/a.java - not seen
                     Edit: File 2: /p/b.java - not seen
                     MultiEdit: rejected""", r.log());
    }

    // ---------------------------------------------------------------- exit path 4: auto-accept

    @Test
    void autoAcceptApprovesEveryFileWithoutPrompting() throws Exception {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java", "/p/c.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);

        r.autoAcceptAll();

        assertEquals(MultiPermissionReview.Outcome.AUTO_ACCEPTED, r.outcome());
        assertTrue(decisionOf(e).allow());
        assertEquals(MultiPermissionReview.ItemState.ACCEPTED, r.stateOf(3),
                "auto-accept still records every file individually — losing that record is the defect it replaces");
    }

    // ---------------------------------------------------------------- exit path 5: a diff that will not render

    @Test
    void aDiffThatWillNotRenderDeclinesTheWholeSetAndNamesTheFile() throws Exception {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java", "/p/c.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);

        r.accept();
        r.renderFailed("/p/b.java");

        assertEquals(MultiPermissionReview.Outcome.RENDER_FAILED, r.outcome());
        PermissionDecision decision = decisionOf(e);
        assertFalse(decision.allow());
        assertTrue(decision.message().contains("/p/b.java"),
                "a technical failure must name the file so it is distinguishable from a choice the user made: "
                + decision.message());
        assertEquals("""
                     Could not render the diff for /p/b.java
                     Edit: File 1: /p/a.java - accepted
                     Edit: File 2: /p/b.java - not seen
                     Edit: File 3: /p/c.java - not seen
                     MultiEdit: rejected""", r.log());
    }

    @Test
    void renderFailureWithNoPathNamesTheFileUnderReview() {
        MultiPermissionReview r = review("/p/a.java", "/p/b.java");

        r.accept();
        r.renderFailed("  ");

        assertTrue(r.log().startsWith("Could not render the diff for /p/b.java"), r.log());
    }

    // ---------------------------------------------------------------- exit path 6: the whole-set deadline

    /**
     * Expiry declines everything, including files the user had already accepted — a timeout is another way of not
     * approving everything. The log keeps it distinct from a rejection the user chose: the header says the deadline
     * passed, and file 1 still reads "accepted" because that is what the user did to it.
     */
    @Test
    void timeoutDeclinesTheWholeSetIncludingFilesAlreadyAccepted() throws Exception {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);

        r.accept();
        r.timedOut();

        assertEquals(MultiPermissionReview.Outcome.TIMED_OUT, r.outcome());
        assertFalse(decisionOf(e).allow(), "a timeout is another way of not approving everything");
        assertEquals("""
                     Timed out waiting for review
                     Edit: File 1: /p/a.java - accepted
                     Edit: File 2: /p/b.java - not seen
                     MultiEdit: rejected""", r.log());
    }

    /**
     * The reason the outcome exists rather than reusing rejectAll(): both decline the set and both produce the same
     * aggregate reply, but recording an expiry as "the user rejected the change set without reviewing" would attribute
     * to the user something they never did.
     */
    @Test
    void aTimeoutIsNotLoggedAsAUserRejection() {
        MultiPermissionReview timedOut = review("/p/a.java");
        MultiPermissionReview userRejected = review("/p/a.java");

        timedOut.timedOut();
        userRejected.rejectAll();

        assertTrue(timedOut.log().startsWith("Timed out waiting for review"), timedOut.log());
        assertTrue(userRejected.log().startsWith("User rejected the change set without reviewing"),
                userRejected.log());
        assertFalse(timedOut.log().contains("User rejected"),
                "an expiry must never read as a decision the user made: " + timedOut.log());
    }

    // ---------------------------------------------------------------- exit path 7: cancellation

    /**
     * The backends tell an interruption from a deliberate "no" by exactly this: a denial lets the agent continue its
     * turn, an exceptional completion interrupts it. Collapsing the two would change the AI's behaviour.
     */
    @Test
    void cancellationCompletesTheFutureExceptionally() {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);
        IllegalStateException cause = new IllegalStateException("panel closed");

        r.accept();
        r.cancelled(cause);

        assertEquals(MultiPermissionReview.Outcome.CANCELLED, r.outcome());
        assertTrue(e.response().isCompletedExceptionally());
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> e.response().get(2, TimeUnit.SECONDS));
        assertSame(cause, thrown.getCause());
    }

    /**
     * With no cause supplied the review manufactures a {@link CancellationException}, which
     * {@link CompletableFuture#get()} rethrows unwrapped rather than boxing in an {@code ExecutionException}. Either
     * shape reaches the handlers as a non-null throwable, which is what makes this an interruption rather than a
     * denial.
     */
    @Test
    void cancellationWithNoCauseStillCompletesExceptionally() {
        MultiPermissionEvent e = event("/p/a.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);

        r.cancelled(null);

        assertTrue(e.response().isCompletedExceptionally());
        assertThrows(CancellationException.class, () -> e.response().get(2, TimeUnit.SECONDS));
    }

    // ---------------------------------------------------------------- exactly-once completion

    /**
     * Every exit path must resolve the response — missing one leaves the AI process blocked forever — and none may
     * resolve it twice, which would silently lose a decision. Driven through a completion counter rather than
     * {@code isDone()}, since a second completion on an already-done future is a silent no-op.
     */
    @Test
    void everyExitPathCompletesTheResponseExactlyOnce() {
        record Path(String name, java.util.function.Consumer<MultiPermissionReview> drive) {

        }
        List<Path> paths = List.of(
                new Path("all accepted", r -> {
                    r.accept();
                    r.accept();
                }),
                new Path("one rejected", r -> {
                    r.accept();
                    r.reject();
                }),
                new Path("reject all", MultiPermissionReview::rejectAll),
                new Path("auto-accept", MultiPermissionReview::autoAcceptAll),
                new Path("render failed", r -> r.renderFailed("/p/b.java")),
                new Path("timed out", MultiPermissionReview::timedOut),
                new Path("cancelled", r -> r.cancelled(new IllegalStateException("x"))));
        assertEquals(7, paths.size(), "there are seven exit paths; a new one must be added here too");

        for (Path path : paths) {
            MultiPermissionEvent e = event("/p/a.java", "/p/b.java");
            AtomicInteger completions = new AtomicInteger();
            e.response().whenComplete((d, t) -> completions.incrementAndGet());

            path.drive().accept(new MultiPermissionReview(e, RAW));

            assertTrue(e.response().isDone(), path.name() + " left the AI process blocked");
            assertEquals(1, completions.get(), path.name() + " did not resolve the response exactly once");
        }
    }

    /**
     * isFinished() reports that the OUTCOME IS SETTLED, not that the future is done — the two are ordered, not
     * simultaneous, because the outcome is settled inside the lock and the future completed after it is released. This
     * pins the ordering that matters to the UI: by the time anything can observe the response, the review already
     * refuses to open another diff and the log is already final. The reverse window — isFinished() true while the future
     * is not yet done — is real and documented, but observing it from a test would mean depending on exactly where
     * inside the lock the renderer is invoked, so it is left to the javadoc rather than pinned to an internal detail.
     */
    @Test
    void theOutcomeIsSettledBeforeTheResponseIsObservable() {
        MultiPermissionEvent e = event("/p/a.java", "/p/b.java");
        MultiPermissionReview r = new MultiPermissionReview(e, RAW);
        List<String> seen = new java.util.ArrayList<>();
        e.response().whenComplete((d, t) -> seen.add(r.isFinished() + "/" + r.outcome() + "/" + r.log()));

        r.accept();
        r.reject();

        assertEquals(List.of("true/REJECTED/" + r.log()), seen,
                "the outcome and the log must already be final when the response becomes observable");
    }

    /**
     * The race this class exists to make safe: two exit paths arriving on different threads at once. Both controls are
     * on screen together — the per-file diff's Accept and the main panel's Reject — so a user can genuinely trigger
     * both, and the UI delivers them on whichever thread gets there first. Whoever wins, the response resolves exactly
     * once, the two outcomes cannot both be recorded, and the decision agrees with the outcome that won.
     *
     * <p>This previously raced accept() against the review's own timedOut(). That path was removed when the batch's
     * bound moved to the producer, so the pair changed; the lock it exercises, and the guarantee, did not.</p>
     */
    @Test
    void twoExitPathsRacingResolveOnce() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            MultiPermissionEvent e = event("/p/a.java");
            MultiPermissionReview r = new MultiPermissionReview(e, RAW);
            AtomicInteger completions = new AtomicInteger();
            e.response().whenComplete((d, t) -> completions.incrementAndGet());

            CountDownLatch go = new CountDownLatch(1);
            Thread accepter = new Thread(() -> {
                await(go);
                r.accept();
            });
            Thread rejecter = new Thread(() -> {
                await(go);
                r.rejectAll();
            });
            accepter.start();
            rejecter.start();
            go.countDown();
            accepter.join(2000);
            rejecter.join(2000);

            assertTrue(r.isFinished());
            assertEquals(1, completions.get(), "attempt " + attempt + " resolved the response more than once");
            MultiPermissionReview.Outcome outcome = r.outcome();
            assertTrue(outcome == MultiPermissionReview.Outcome.ACCEPTED
                    || outcome == MultiPermissionReview.Outcome.REJECTED_ALL, "unexpected outcome " + outcome);
            assertEquals(outcome == MultiPermissionReview.Outcome.ACCEPTED, decisionOf(e).allow(),
                    "the decision must agree with the outcome that won the race");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- order

    /**
     * Items are walked in the order the AI supplied. The paths below are deliberately non-alphabetical, so any sorting
     * would show up as a different walk and a different log.
     */
    @Test
    void itemsAreWalkedInTheSuppliedOrder() {
        MultiPermissionReview r = review("/p/c.java", "/p/a.java", "/p/b.java");

        assertEquals("/p/c.java", r.currentItem().filePath());
        assertEquals(1, r.currentItemNumber());
        r.accept();
        assertEquals("/p/a.java", r.currentItem().filePath());
        assertEquals(2, r.currentItemNumber());
        r.accept();
        assertEquals("/p/b.java", r.currentItem().filePath());
        r.accept();

        assertNull(r.currentItem());
        assertEquals(0, r.currentItemNumber());
        assertEquals("""
                     User accepted
                     Edit: File 1: /p/c.java - accepted
                     Edit: File 2: /p/a.java - accepted
                     Edit: File 3: /p/b.java - accepted
                     MultiEdit: accepted""", r.log());
    }

    // ---------------------------------------------------------------- log text

    @Test
    void logForAUserAcceptedSet() {
        MultiPermissionReview r = review("/p/a.java", "/p/b.java", "/p/c.java");

        r.accept();
        r.accept();
        r.accept();

        assertEquals("""
                     User accepted
                     Edit: File 1: /p/a.java - accepted
                     Edit: File 2: /p/b.java - accepted
                     Edit: File 3: /p/c.java - accepted
                     MultiEdit: accepted""", r.log());
    }

    @Test
    void logForARejectedDiffShowsTheUnseenFiles() {
        MultiPermissionReview r = review("/p/a.java", "/p/b.java", "/p/c.java");

        r.accept();
        r.reject();

        assertEquals("""
                     User rejected a diff
                     Edit: File 1: /p/a.java - accepted
                     Edit: File 2: /p/b.java - rejected
                     Edit: File 3: /p/c.java - not seen
                     MultiEdit: rejected""", r.log());
    }

    @Test
    void logForAnAutoAcceptedSetSaysSoInTheSummary() {
        MultiPermissionReview r = review("/p/a.java", "/p/b.java", "/p/c.java");

        r.autoAcceptAll();

        assertEquals("""
                     Auto accepted
                     Edit: File 1: /p/a.java - accepted
                     Edit: File 2: /p/b.java - accepted
                     Edit: File 3: /p/c.java - accepted
                     MultiEdit: auto-accepted""", r.log());
    }

    @Test
    void logForACancelledReview() {
        MultiPermissionReview r = review("/p/a.java", "/p/b.java");

        r.accept();
        r.cancelled(new IllegalStateException("panel closed"));

        assertEquals("""
                     Review cancelled
                     Edit: File 1: /p/a.java - accepted
                     Edit: File 2: /p/b.java - not seen
                     MultiEdit: rejected""", r.log());
    }

    @Test
    void theLogIsOnlyAvailableOnceTheReviewHasFinished() {
        MultiPermissionReview r = review("/p/a.java", "/p/b.java");

        r.accept();

        assertThrows(IllegalStateException.class, r::log);
    }

    // ---------------------------------------------------------------- the injected path renderer

    /**
     * The renderer must actually be applied, not accepted and ignored — a log showing raw paths where the rest of the
     * message panel shows short ones is exactly the bug this shape invites.
     */
    @Test
    void logPathsGoThroughTheInjectedRenderer() {
        MultiPermissionEvent e = event("/home/me/proj/src/A.java", "/home/me/proj/src/B.java");
        MultiPermissionReview r = new MultiPermissionReview(e, p -> "proj/" + p.substring(p.lastIndexOf('/') + 1));

        r.accept();
        r.accept();

        assertEquals("""
                     User accepted
                     Edit: File 1: proj/A.java - accepted
                     Edit: File 2: proj/B.java - accepted
                     MultiEdit: accepted""", r.log());
    }

    @Test
    void theRenderFailureHeaderAlsoGoesThroughTheRenderer() {
        MultiPermissionEvent e = event("/home/me/proj/src/A.java");
        MultiPermissionReview r = new MultiPermissionReview(e, p -> "proj/" + p.substring(p.lastIndexOf('/') + 1));

        r.renderFailed("/home/me/proj/src/A.java");

        assertTrue(r.log().startsWith("Could not render the diff for proj/A.java"), r.log());
    }

    /**
     * The deny message goes back to the AI process, which supplied those paths and identifies its own files by them, so
     * it keeps the raw path even when the log is shortened.
     */
    @Test
    void theDenyMessageKeepsTheRawPath() throws Exception {
        MultiPermissionEvent e = event("/home/me/proj/src/A.java");
        MultiPermissionReview r = new MultiPermissionReview(e, p -> "proj/A.java");

        r.reject();

        assertTrue(decisionOf(e).message().contains("/home/me/proj/src/A.java"), decisionOf(e).message());
    }

    @Test
    void aRendererReturningNullFallsBackToTheRawPath() {
        MultiPermissionEvent e = event("/p/a.java");
        MultiPermissionReview r = new MultiPermissionReview(e, p -> null);

        r.accept();

        assertTrue(r.log().contains("/p/a.java"), r.log());
    }

    // ---------------------------------------------------------------- construction

    @Test
    void constructionRejectsAMissingEventOrRenderer() {
        assertThrows(IllegalArgumentException.class, () -> new MultiPermissionReview(null, RAW));
        assertThrows(IllegalArgumentException.class, () -> new MultiPermissionReview(event("/p/a.java"), null));
    }

    @Test
    void stateOfRejectsAFileNumberOutsideTheSet() {
        MultiPermissionReview r = review("/p/a.java");

        assertNotNull(r.stateOf(1));
        assertThrows(IndexOutOfBoundsException.class, () -> r.stateOf(0));
        assertThrows(IndexOutOfBoundsException.class, () -> r.stateOf(2));
    }
}
