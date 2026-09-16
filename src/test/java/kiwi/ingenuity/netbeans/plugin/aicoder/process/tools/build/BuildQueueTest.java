package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BuildQueueTest {

    private static final long WAIT_SECONDS = 5;

    private MutableClock clock;
    private BuildQueue queue;
    private List<BuildJob> finishedJobs;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-16T01:00:00Z"));
        queue = new BuildQueue(clock);
        finishedJobs = new CopyOnWriteArrayList<>();
        queue.setCompletionListener(finishedJobs::add);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void inlineLimitIsTheFloorUntilTheProjectHasSucceededOnce() {
        assertEquals(TimeoutEnum.BUILD_LOCK_LIFETIME_MILLIS.millis(), queue.inlineTimeoutMillisFor("/p/unknown"),
                     "a project with no successful build yet gets the plain floor");
        assertNull(queue.longestSuccessFor("/p/unknown"));
    }

    @Test
    void aSuccessfulRunRaisesTheInlineLimitForThatProjectWithAMargin() throws Exception {
        BuildJob job = queue.submit(request("/p/slow", "session-1", BuildTypeEnum.INLINE, control -> {
                                        clock.advance(Duration.ofMinutes(6));
                                        return BuildOutcome.completed(true, "BUILD SUCCESS");
                                    }));
        queue.awaitFinish(job);

        assertEquals(Duration.ofMinutes(6), queue.longestSuccessFor("/p/slow"));
        assertEquals(Duration.ofMinutes(6).toMillis() * 12 / 10, queue.inlineTimeoutMillisFor("/p/slow"),
                     "six minutes observed plus the 20% margin");
    }

    @Test
    void onlySuccessfulRunsCountSoAFailureOrTimeoutNeverRaisesTheLimit() throws Exception {
        BuildJob failed = queue.submit(request("/p/broken", "session-1", BuildTypeEnum.INLINE, control -> {
                                           clock.advance(Duration.ofMinutes(9));
                                           return BuildOutcome.completed(false, "BUILD FAILED");
                                       }));
        queue.awaitFinish(failed);
        BuildJob timedOut = queue.submit(request("/p/hung", "session-1", BuildTypeEnum.ASYNC, control -> {
                                             clock.advance(Duration.ofMinutes(30));
                                             return BuildOutcome.timedOut("Timed out");
                                         }));
        queue.awaitFinish(timedOut);

        assertNull(queue.longestSuccessFor("/p/broken"), "a build that failed never ran the work to completion");
        assertNull(queue.longestSuccessFor("/p/hung"), "a timeout must not ratchet the limit upwards");
        assertEquals(TimeoutEnum.BUILD_LOCK_LIFETIME_MILLIS.millis(), queue.inlineTimeoutMillisFor("/p/hung"));
    }

    @Test
    void completedIdeActionDoesNotChangeLongestSuccessButSuccessfulBuildDoes() throws Exception {
        BuildJob ide = queue.submit(unstoppableRequest("/p/ide", "session-1", control -> {
                                                   clock.advance(Duration.ofMinutes(7));
                                                   return new BuildOutcome(BuildStatusEnum.COMPLETED,
                                                                           "COMPLETED (result unknown)");
                                               }));
        queue.awaitFinish(ide);
        assertNull(queue.longestSuccessFor("/p/ide"));

        BuildJob normal = queue.submit(request("/p/ide", "session-1", BuildTypeEnum.ASYNC, control -> {
                                           clock.advance(Duration.ofMinutes(3));
                                           return BuildOutcome.completed(true, "BUILD SUCCESS");
                                       }));
        queue.awaitFinish(normal);
        assertEquals(Duration.ofMinutes(3), queue.longestSuccessFor("/p/ide"));
    }

    @Test
    void aSuccessfulDownloadDoesNotChangeLongestSuccessButASuccessfulBuildDoes() throws Exception {
        BuildJob download = queue.submit(nonCountingRequest("/p/download", "session-1", control -> {
                                                        clock.advance(Duration.ofMinutes(5));
                                                        return BuildOutcome.completed(true, "BUILD SUCCESS");
                                                    }));
        queue.awaitFinish(download);
        assertNull(queue.longestSuccessFor("/p/download"));

        BuildJob normal = queue.submit(request("/p/download", "session-1", BuildTypeEnum.ASYNC, control -> {
                                           clock.advance(Duration.ofMinutes(2));
                                           return BuildOutcome.completed(true, "BUILD SUCCESS");
                                       }));
        queue.awaitFinish(normal);
        assertEquals(Duration.ofMinutes(2), queue.longestSuccessFor("/p/download"));
    }

    @Test
    void anAsyncSuccessRaisesTheLimitForALaterInlineBuild() throws Exception {
        // The point of feeding the record from async runs too: a project slower than the floor could never succeed
        // inline, so if only inline runs counted it could never record a time and the limit could never grow.
        BuildJob async = queue.submit(request("/p/big", "session-1", BuildTypeEnum.ASYNC, control -> {
                                          clock.advance(Duration.ofMinutes(20));
                                          return BuildOutcome.completed(true, "BUILD SUCCESS");
                                      }));
        queue.awaitFinish(async);

        assertEquals(Duration.ofMinutes(20).toMillis() * 12 / 10, queue.inlineTimeoutMillisFor("/p/big"),
                     "an inline build of this project now gets the time it has actually proved it needs");
    }

    @Test
    void runsOneBuildAtATimeInTheOrderTheyWereQueued() throws Exception {
        List<String> ran = new CopyOnWriteArrayList<>();
        BlockingWork first = new BlockingWork("first", ran);
        BuildJob a = queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, first));
        assertTrue(first.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        BuildJob b = queue.submit(request("/p/b", "session-2", BuildTypeEnum.INLINE, recording("second", ran)));
        BuildJob c = queue.submit(request("/p/c", "session-1", BuildTypeEnum.ASYNC, recording("third", ran)));

        BuildQueueSnapshot snapshot = queue.snapshot();
        assertEquals(List.of(a, b, c), snapshot.current());
        assertEquals(BuildStatusEnum.RUNNING, a.status());
        assertEquals(BuildStatusEnum.QUEUED, b.status());
        assertEquals(1, queue.positionOf(b));
        assertEquals(2, queue.positionOf(c));

        first.release.countDown();
        queue.awaitFinish(c);

        assertEquals(List.of("first", "second", "third"), ran);
        assertEquals(List.of(c, b, a), queue.snapshot().recent());
        assertTrue(queue.snapshot().current().isEmpty());
    }

    @Test
    void reportsActiveBuildsWhileAnyIsQueuedOrRunning() throws Exception {
        assertFalse(queue.hasActiveBuilds());
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        BuildJob job = queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, running));
        assertTrue(queue.hasActiveBuilds());
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(queue.hasActiveBuilds());

        running.release.countDown();
        queue.awaitFinish(job);

        assertFalse(queue.hasActiveBuilds());
    }

    @Test
    void refusesASecondBuildForAProjectAlreadyQueuedOrRunningWhoeverAsks() throws Exception {
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        queue.submit(request("/p/b", "session-1", BuildTypeEnum.ASYNC, succeeding()));

        // A DIFFERENT build of the same project is what collides. An identical call is shared with the caller instead
        // of being refused — see asecondAiAskingForTheSameBuildListensToItInsteadOfQueueingItTwice.
        BuildQueueException whileRunning = assertThrows(BuildQueueException.class,
                                                        () -> queue.submit(request("/p/a", "session-2", BuildTypeEnum.INLINE,
                                                                                   "RunMavenTests {}", succeeding())));
        assertTrue(whileRunning.getMessage().contains("already running"), whileRunning.getMessage());
        BuildQueueException whileQueued = assertThrows(BuildQueueException.class,
                                                       () -> queue.submit(request("/p/b", "session-2", BuildTypeEnum.ASYNC,
                                                                                  "RunMavenTests {}", succeeding())));
        assertTrue(whileQueued.getMessage().contains("already queued"), whileQueued.getMessage());

        running.release.countDown();
    }

    @Test
    void asecondAiAskingForTheSameBuildListensToItInsteadOfQueueingItTwice() throws Exception {
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        BuildJob first = queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        BuildJob joined = queue.submit(request("/p/a", "session-2", BuildTypeEnum.INLINE, succeeding()));

        assertSame(first, joined, "the identical build must be shared, not queued a second time");
        assertEquals(Set.of("session-2"), joined.listeners());
        assertEquals(1, queue.snapshot().current().size(), "only the original build may be in the queue");

        running.release.countDown();
        queue.awaitFinish(first);
    }

    @Test
    void aQueuedIdeActionCanStillBeStopped() throws Exception {
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        BuildJob queuedIde = queue.submit(unstoppableRequest("/p/b", "session-1", succeeding()));

        String stopped = queue.stop(queuedIde.id(), "session-1");

        assertTrue(stopped.startsWith("Cancelled queued build"), stopped);
        assertEquals(BuildStatusEnum.CANCELLED, queuedIde.status(),
                     "removing a build from the queue needs nothing from the build system");
        assertEquals(BuildCancelReasonEnum.STOPPED_BY_OWNER, queuedIde.cancelReason());

        running.release.countDown();
    }

    @Test
    void aRunningIdeActionRefusesToBeStoppedRatherThanReportACancellationThatDidNotHappen() throws Exception {
        BlockingWork running = new BlockingWork("ide", new CopyOnWriteArrayList<>());
        BuildJob job = queue.submit(unstoppableRequest("/p/a", "session-1", running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        String refused = queue.stop(job.id(), "session-1");

        assertTrue(refused.contains("cannot be stopped from here"), refused);
        assertEquals(BuildStatusEnum.RUNNING, job.status(), "a refusal must not pretend the build stopped");
        assertNull(job.cancelReason());

        running.release.countDown();
        queue.awaitFinish(job);

        assertEquals(BuildStatusEnum.SUCCESS, job.status(), "it runs to completion, as the IDE build itself would");
    }

    @Test
    void theRequesterIsNeverItsOwnListener() throws Exception {
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        BuildJob job = queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        assertSame(job, queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, succeeding())));
        assertTrue(job.listeners().isEmpty(), "asking twice must not make the requester listen to itself");

        running.release.countDown();
        queue.awaitFinish(job);
    }

    @Test
    void aBuildSurvivesItsRequesterClosingAndIsCancelledOnlyWhenTheLastListenerGoes() throws Exception {
        CancellableWork work = new CancellableWork();
        BuildJob job = queue.submit(request("/p/a", "requester", BuildTypeEnum.ASYNC, work));
        assertTrue(work.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertSame(job, queue.submit(request("/p/a", "listener", BuildTypeEnum.ASYNC, succeeding())));

        queue.cancelForSession("requester");

        assertEquals(BuildStatusEnum.RUNNING, job.status(), "another AI is still waiting on this exact build");
        assertNull(job.cancelReason());

        queue.cancelForSession("listener");
        queue.awaitFinish(job);

        assertEquals(BuildStatusEnum.CANCELLED, job.status(), "nobody is left waiting on it now");
        assertEquals(BuildCancelReasonEnum.SESSION_CLOSED, job.cancelReason());
    }

    @Test
    void onlyTheOwnerCanStopAQueuedAsyncBuildAndItIsListedAsCancelled() throws Exception {
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        BuildJob queuedJob = queue.submit(request("/p/b", "session-1", BuildTypeEnum.ASYNC, succeeding()));

        String refused = queue.stop(queuedJob.id(), "session-2");
        assertEquals("Build " + queuedJob.id() + " was requested by AI session-1; only the AI that requested a build"
                + " can stop it.", refused);
        assertEquals(BuildStatusEnum.QUEUED, queuedJob.status());

        String stopped = queue.stop(queuedJob.id(), "session-1");
        assertTrue(stopped.startsWith("Cancelled queued build"), stopped);
        assertEquals(BuildStatusEnum.CANCELLED, queuedJob.status());
        assertEquals(BuildCancelReasonEnum.STOPPED_BY_OWNER, queuedJob.cancelReason());
        assertNull(queuedJob.startedAt());
        assertTrue(queue.snapshot().recent().contains(queuedJob));
        assertTrue(finishedJobs.contains(queuedJob));

        running.release.countDown();
    }

    @Test
    void aListenerStoppingSomeoneElsesBuildIsToldWhoRequestedItAndThatItWillStillGetTheResult() throws Exception {
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        BuildJob job = queue.submit(request("/p/a", "requester", BuildTypeEnum.ASYNC, running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertSame(job, queue.submit(request("/p/a", "listener", BuildTypeEnum.ASYNC, succeeding())));

        String refused = queue.stop(job.id(), "listener");

        assertEquals("Build " + job.id() + " was requested by AI requester; only the AI that requested a build can"
                + " stop it. You are listening to it, so you will still receive its result.", refused);
        assertEquals(BuildStatusEnum.RUNNING, job.status(), "a refusal must not stop the build");
        assertNull(job.cancelReason());

        running.release.countDown();
        queue.awaitFinish(job);
    }

    @Test
    void stoppingARunningBuildCancelsItsControlAndRecordsCancelled() throws Exception {
        CancellableWork work = new CancellableWork();
        BuildJob job = queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, work));
        assertTrue(work.started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        String stopping = queue.stop(job.id(), "session-1");
        assertTrue(stopping.startsWith("Stopping running build"), stopping);
        queue.awaitFinish(job);

        assertEquals(BuildStatusEnum.CANCELLED, job.status());
        assertEquals(BuildCancelReasonEnum.STOPPED_BY_OWNER, job.cancelReason());
        assertTrue(job.result().startsWith("Build cancelled: stopped by its AI."), job.result());
    }

    @Test
    void inlineBuildsCannotBeStopped() throws Exception {
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        BuildJob job = queue.submit(request("/p/a", "session-1", BuildTypeEnum.INLINE, running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        assertTrue(queue.stop(job.id(), "session-1").startsWith("No queued or running async build"));
        assertEquals(BuildStatusEnum.RUNNING, job.status());

        running.release.countDown();
    }

    @Test
    void closingASessionCancelsItsQueuedAndRunningBuildsOnly() throws Exception {
        CancellableWork work = new CancellableWork();
        BuildJob runningJob = queue.submit(request("/p/a", "closing", BuildTypeEnum.INLINE, work));
        assertTrue(work.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        BuildJob queuedJob = queue.submit(request("/p/b", "closing", BuildTypeEnum.ASYNC, succeeding()));
        BuildJob otherSession = queue.submit(request("/p/c", "staying", BuildTypeEnum.ASYNC, succeeding()));

        queue.cancelForSession("closing");
        queue.awaitFinish(otherSession);

        assertEquals(BuildStatusEnum.CANCELLED, queuedJob.status());
        assertEquals(BuildCancelReasonEnum.SESSION_CLOSED, queuedJob.cancelReason());
        assertEquals(BuildStatusEnum.CANCELLED, runningJob.status());
        assertEquals(BuildCancelReasonEnum.SESSION_CLOSED, runningJob.cancelReason());
        assertEquals(BuildStatusEnum.SUCCESS, otherSession.status());
    }

    @Test
    void recentBuildsKeepTheLastFiveNewestFirst() throws Exception {
        BuildJob last = null;
        for (int i = 1; i <= 7; i++) {
            last = queue.submit(request("/p/" + i, "session-1", BuildTypeEnum.ASYNC, succeeding()));
            queue.awaitFinish(last);
        }

        List<BuildJob> recent = queue.snapshot().recent();
        assertEquals(BuildQueue.RECENT_BUILD_LIMIT, recent.size());
        assertEquals(last, recent.get(0));
        assertEquals("/p/3", recent.get(4).request().projectPath());
    }

    @Test
    void anInlineBuildThatDoesNotStartInTimeLeavesTheQueueAsCancelled() throws Exception {
        BlockingWork running = new BlockingWork("running", new CopyOnWriteArrayList<>());
        queue.submit(request("/p/a", "session-1", BuildTypeEnum.ASYNC, running));
        assertTrue(running.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        BuildJob inline = queue.submit(request("/p/b", "session-2", BuildTypeEnum.INLINE, succeeding()));

        assertFalse(queue.awaitStart(inline, 50));

        assertEquals(BuildStatusEnum.CANCELLED, inline.status());
        assertEquals(BuildCancelReasonEnum.START_WAIT_EXPIRED, inline.cancelReason());
        assertEquals(-1, queue.positionOf(inline));
        running.release.countDown();
    }

    @Test
    void resultsTimingsAndFailuresAreRecorded() throws Exception {
        BuildJob ok = queue.submit(request("/p/a", "session-1", BuildTypeEnum.INLINE, control -> {
                                       clock.advance(Duration.ofSeconds(90));
                                       return BuildOutcome.completed(true, "BUILD SUCCESS");
                                   }));
        assertTrue(queue.awaitStart(ok, TimeUnit.SECONDS.toMillis(WAIT_SECONDS)));
        queue.awaitFinish(ok);
        BuildJob timedOut = queue.submit(request("/p/b", "session-1", BuildTypeEnum.ASYNC,
                                                 control -> BuildOutcome.timedOut("Timed out after 3600s")));
        queue.awaitFinish(timedOut);
        BuildJob broken = queue.submit(request("/p/c", "session-1", BuildTypeEnum.ASYNC, control -> {
                                           throw new IllegalStateException("boom");
                                       }));
        queue.awaitFinish(broken);

        assertEquals(BuildStatusEnum.SUCCESS, ok.status());
        assertEquals("BUILD SUCCESS", ok.result());
        assertEquals(Duration.ofSeconds(90), ok.duration());
        assertEquals(ok.startedAt().plusSeconds(90), ok.endedAt());
        assertEquals(BuildStatusEnum.TIMED_OUT, timedOut.status());
        assertEquals(BuildStatusEnum.FAILED, broken.status());
        assertEquals("Build error: boom", broken.result());
        assertEquals(List.of(ok, timedOut, broken), finishedJobs);
    }

    private static BuildRequest request(String project, String sessionId, BuildTypeEnum type, BuildWork work) {
        return request(project, sessionId, type, "BuildMavenProject {\"goals\":[\"package\"]}", work);
    }

    /**
     * A build with an explicit tool call, for tests that need two DIFFERENT builds of one project. The queue shares an
     * identical call between callers rather than refusing it, so only a differing call still collides.
     */
    private static BuildRequest request(String project, String sessionId, BuildTypeEnum type, String toolCall,
                                        BuildWork work) {
        return new BuildRequest(toolCall, project, project, sessionId, "AI " + sessionId, type, 60_000, true, work);
    }

    /**
     * A build that cannot be stopped once running, as an IDE build action cannot: NetBeans gives the caller of
     * {@code ActionProvider.invokeAction} no way to cancel it.
     */
    private static BuildRequest unstoppableRequest(String project, String sessionId, BuildWork work) {
        return new BuildRequest("BuildProject {}", project, project, sessionId, "AI " + sessionId,
                                BuildTypeEnum.ASYNC, 60_000, false, work);
    }

    /**
     * A build that must not feed Longest OK run, as a Maven dependency download does not: it is not a build, so how
     * long it takes says nothing about how long this project's own build needs.
     */
    private static BuildRequest nonCountingRequest(String project, String sessionId, BuildWork work) {
        return new BuildRequest("DownloadMavenSources {}", project, project, sessionId, "AI " + sessionId,
                                BuildTypeEnum.ASYNC, 60_000, true, false, work);
    }

    private static BuildWork succeeding() {
        return control -> BuildOutcome.completed(true, "BUILD SUCCESS");
    }

    private static BuildWork recording(String name, List<String> ran) {
        return control -> {
            ran.add(name);
            return BuildOutcome.completed(true, name);
        };
    }

    private static final class BlockingWork implements BuildWork {

        private final String name;
        private final List<String> ran;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingWork(String name, List<String> ran) {
            this.name = name;
            this.ran = ran;
        }

        @Override
        public BuildOutcome run(BuildControl control) {
            ran.add(name);
            started.countDown();
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return BuildOutcome.completed(true, name);
        }
    }

    private static final class CancellableWork implements BuildWork {

        private final CountDownLatch started = new CountDownLatch(1);

        @Override
        public BuildOutcome run(BuildControl control) {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (!control.isCancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return BuildOutcome.failed("process killed");
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
}
