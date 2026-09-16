package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.DateUtil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BuildReportFormatterTest {

    private static final Instant QUEUED = Instant.parse("2026-09-16T01:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-09-16T01:00:05Z");
    private static final Instant ENDED = Instant.parse("2026-09-16T01:02:08Z");

    @Test
    void aiResultCarriesStatusTimesDurationAndTheBuildsOwnResult() {
        BuildJob job = finishedJob("build-7", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.SUCCESS, null);

        String text = BuildReportFormatter.aiResult(job);

        assertEquals("Build build-7: BuildMavenProject {\"goals\":[\"install\"]}\n"
                + "Project: /p/app\n"
                + "Status: SUCCESS\n"
                + "Queued: " + DateUtil.format(QUEUED) + "\n"
                + "Started: " + DateUtil.format(STARTED) + "\n"
                + "Ended: " + DateUtil.format(ENDED) + "\n"
                + "Duration: 2 mins, 3 secs\n\n"
                + "BUILD SUCCESS", text);
    }

    @Test
    void aiResultShowsCompletedAndUnknownStatusText() {
        BuildJob completed = finishedJob("build-9", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.COMPLETED, null);
        BuildJob unknown = finishedJob("build-10", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.UNKNOWN, null);

        assertTrue(BuildReportFormatter.aiResult(completed).contains("Status: COMPLETED (result unknown)"),
                   BuildReportFormatter.aiResult(completed));
        assertTrue(BuildReportFormatter.aiResult(unknown).contains("Status: UNKNOWN (completion not confirmed)"),
                   BuildReportFormatter.aiResult(unknown));
    }

    @Test
    void chatSummaryIsOneLineWithToolStatusDurationAndProject() {
        BuildJob ok = finishedJob("build-7", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.SUCCESS, null);
        BuildJob stopped = finishedJob("build-8", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.CANCELLED,
                                       BuildCancelReasonEnum.STOPPED_BY_OWNER);

        assertEquals("BUILD: BuildMavenProject SUCCESS in 2 mins, 3 secs (/p/app)", BuildReportFormatter.chatSummary(ok));
        assertEquals("BUILD: BuildMavenProject CANCELLED (stopped by its AI) in 2 mins, 3 secs (/p/app)",
                     BuildReportFormatter.chatSummary(stopped));
    }

    @Test
    void chatSummaryShowsCompletedAndUnknownStatusText() {
        BuildJob completed = finishedJob("build-9", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.COMPLETED, null);
        BuildJob unknown = finishedJob("build-10", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.UNKNOWN, null);

        assertEquals("BUILD: BuildMavenProject COMPLETED (result unknown) in 2 mins, 3 secs (/p/app)",
                     BuildReportFormatter.chatSummary(completed));
        assertEquals("BUILD: BuildMavenProject UNKNOWN (completion not confirmed) in 2 mins, 3 secs (/p/app)",
                     BuildReportFormatter.chatSummary(unknown));
    }

    @Test
    void listBuildsShowsIdsOnlyForTheViewersOwnAsyncBuilds() {
        BuildJob mineAsync = queuedJob("build-1", "me", BuildTypeEnum.ASYNC, "/p/a");
        BuildJob mineInline = queuedJob("build-2", "me", BuildTypeEnum.INLINE, "/p/b");
        BuildJob othersAsync = queuedJob("build-3", "someone-else", BuildTypeEnum.ASYNC, "/p/c");
        BuildJob recentMine = finishedJob("build-0", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.CANCELLED,
                                          BuildCancelReasonEnum.SESSION_CLOSED);
        // Only /p/a has ever completed a build successfully, so it shows a time and the rest show "none yet".
        Map<String, Duration> longestSuccess = Map.of("/p/a", Duration.ofMinutes(4));
        BuildQueueSnapshot snapshot = new BuildQueueSnapshot(ENDED, List.of(mineAsync, mineInline, othersAsync),
                                                             List.of(recentMine), longestSuccess);

        String report = BuildReportFormatter.listBuilds(snapshot, "me");

        assertTrue(report.startsWith("Build queue at " + DateUtil.format(ENDED)), report);
        assertTrue(report.contains("Current builds, in order of execution (3):"), report);
        assertTrue(report.contains("| /p/a | AI me | ASYNC | QUEUED |  | "
                + DateUtil.formatDuration(Duration.ofMinutes(4)) + " | build-1 |"), report);
        assertTrue(report.contains("| /p/b | AI me | INLINE | QUEUED |  | none yet |  |"), report);
        assertTrue(report.contains("| /p/c | AI someone-else | ASYNC | QUEUED |  | none yet |  |"), report);
        assertFalse(report.contains("build-2"), report);
        assertFalse(report.contains("build-3"), report);
        assertTrue(report.contains("CANCELLED (its AI session closed)"), report);
        assertTrue(report.contains("| 2 mins, 3 secs | none yet | build-0 |"), report);
    }

    @Test
    void listBuildsShowsCompletedAndUnknownStatusInTheStatusColumn() {
        BuildJob completed = finishedJob("build-9", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.COMPLETED, null);
        BuildJob unknown = finishedJob("build-10", "me", BuildTypeEnum.ASYNC, BuildStatusEnum.UNKNOWN, null);
        BuildQueueSnapshot snapshot = new BuildQueueSnapshot(ENDED, List.of(), List.of(completed, unknown), Map.of());

        String report = BuildReportFormatter.listBuilds(snapshot, "me");

        assertTrue(report.contains("COMPLETED (result unknown)"), report);
        assertTrue(report.contains("UNKNOWN (completion not confirmed)"), report);
    }

    @Test
    void emptyQueueSaysNone() {
        String report = BuildReportFormatter.listBuilds(
                new BuildQueueSnapshot(ENDED, List.of(), List.of(), Map.of()), "me");

        assertTrue(report.contains("Current builds, in order of execution (0):\n(none)"), report);
        assertTrue(report.endsWith("(last 5):\n(none)"), report);
    }

    private static BuildJob queuedJob(String id, String sessionId, BuildTypeEnum type, String project) {
        return new BuildJob(id, new BuildRequest("BuildMavenProject {\"goals\":[\"install\"]}", project, project, sessionId,
                                                 "AI " + sessionId, type, 60_000, true, control -> null), QUEUED);
    }

    private static BuildJob finishedJob(String id, String sessionId, BuildTypeEnum type, BuildStatusEnum status,
                                        BuildCancelReasonEnum reason) {
        BuildJob job = queuedJob(id, sessionId, type, "/p/app");
        job.markRunning(new FixedClock(STARTED));
        job.markFinished(status, "BUILD SUCCESS", reason, ENDED);
        return job;
    }

    /**
     * A clock that reads {@link #STARTED} when the timer starts and {@link #ENDED} afterwards, giving 2 mins 3 secs.
     */
    private static final class FixedClock extends java.time.Clock {

        private final Instant start;
        private boolean first = true;

        FixedClock(Instant start) {
            this.start = start;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (first) {
                first = false;
                return start;
            }
            return start.plus(Duration.between(STARTED, ENDED));
        }
    }
}
