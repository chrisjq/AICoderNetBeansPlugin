package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link BlockingActionProgress} and {@link IdeActionWaiter} directly, standing in for an {@code
 * ActionProvider} that calls started()/finished(boolean) on the progress object it was handed. No real NetBeans project
 * is involved: only the wait-then-classify logic behind {@link ProjectActionProvider}'s blocking IDE actions is under
 * test here.
 */
class IdeActionWaiterTest {

    private static final long GENEROUS_GRACE_MILLIS = 2_000L;
    private static final long GENEROUS_RUN_LIMIT_MILLIS = 5_000L;

    @Test
    void startedThenFinishedTrueReturnsCompletedSuccess() throws Exception {
        BlockingActionProgress progress = new BlockingActionProgress();
        Thread provider = new Thread(() -> {
            progress.started();
            progress.finished(true);
        });
        provider.start();
        try {
            ProjectActionResult result
                    = IdeActionWaiter.await(progress, "Build", GENEROUS_GRACE_MILLIS, GENEROUS_RUN_LIMIT_MILLIS);

            assertEquals(ProjectActionResult.Kind.COMPLETED, result.kind());
            assertTrue(result.message().contains("build result is not confirmed"), result.message());
            assertFalse(result.message().contains("succeeded"), result.message());

        }
        finally {
            provider.join();
        }
    }

    @Test
    void startedThenFinishedFalseReturnsCompletedFailure() throws Exception {
        BlockingActionProgress progress = new BlockingActionProgress();
        Thread provider = new Thread(() -> {
            progress.started();
            progress.finished(false);
        });
        provider.start();
        try {
            ProjectActionResult result
                    = IdeActionWaiter.await(progress, "Clean and build", GENEROUS_GRACE_MILLIS,
                                            GENEROUS_RUN_LIMIT_MILLIS);

            assertEquals(ProjectActionResult.Kind.FAILED, result.kind());
            assertEquals("Clean and build reported failure", result.message());

        }
        finally {
            provider.join();
        }
    }

    @Test
    void neverStartedFallsBackToUntrackedMessage() {
        BlockingActionProgress progress = new BlockingActionProgress();

        ProjectActionResult result = IdeActionWaiter.await(progress, "Clean", 100L, GENEROUS_RUN_LIMIT_MILLIS);

        assertEquals(ProjectActionResult.Kind.NOT_TRACKED, result.kind());

        assertEquals(IdeActionWaiter.notTrackedMessage("Clean"), result.message());
        assertTrue(result.message().startsWith("Clean triggered — check the Output window for results"),
                   "today's text must remain a literal prefix, unchanged: " + result.message());
        assertTrue(result.message().contains("could not confirm completion"));
    }

    @Test
    void notTrackedMessageBlamesTheProviderForNotReportingProgress() {
        String message = IdeActionWaiter.notTrackedMessage("Clean");

        assertTrue(message.startsWith("Clean triggered — check the Output window for results"),
                   "today's text must remain a literal prefix, unchanged: " + message);
        assertTrue(message.contains("does not report progress"), message);
        assertFalse(message.contains("UI thread"), "must not claim an EDT reason it wasn't given: " + message);
    }

    @Test
    void notWaitedMessageBlamesTheEdtNotTheProvider() {
        String message = IdeActionWaiter.notWaitedMessage("Clean");

        assertTrue(message.startsWith("Clean triggered — check the Output window for results"),
                   "today's text must remain a literal prefix, unchanged: " + message);
        assertTrue(message.contains("UI thread"), message);
        assertFalse(message.contains("does not report progress"),
                    "must not claim the provider can't report progress when we simply never asked: " + message);
    }

    @Test
    void startedButNeverFinishedReportsStillRunning() throws Exception {
        BlockingActionProgress progress = new BlockingActionProgress();
        Thread provider = new Thread(progress::started);
        provider.start();
        try {
            ProjectActionResult result = IdeActionWaiter.await(progress, "Build", GENEROUS_GRACE_MILLIS, 100L);

            assertEquals(ProjectActionResult.Kind.STILL_RUNNING, result.kind(),
                         "still running is not the same as done, regardless of eventual success");

            assertTrue(result.message().contains("still running"), () -> result.message());
        }
        finally {
            provider.join();
        }
    }
}
