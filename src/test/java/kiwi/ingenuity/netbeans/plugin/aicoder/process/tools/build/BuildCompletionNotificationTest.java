package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.BuildCompletionNotification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Lives in the build package because its fixture needs BuildJob's package-private construction API.
 */
class BuildCompletionNotificationTest {

    @Test
    void agentOnlyTextReturnsExplicitPerRecipientText() {
        BuildCompletionNotification notification = new BuildCompletionNotification(finishedJob(), "listener log text");

        assertEquals("listener log text", notification.agentOnlyText());
    }

    @Test
    void agentOnlyTextFallsBackToBuildResultWhenTextIsNull() {
        BuildJob job = finishedJob();
        BuildCompletionNotification notification = new BuildCompletionNotification(job, null);

        assertEquals(BuildReportFormatter.aiResult(job), notification.agentOnlyText());
    }

    @Test
    void agentOnlyTextFallsBackToBuildResultWhenTextIsBlank() {
        BuildJob job = finishedJob();
        BuildCompletionNotification notification = new BuildCompletionNotification(job, "   ");

        assertEquals(BuildReportFormatter.aiResult(job), notification.agentOnlyText());
    }

    private static BuildJob finishedJob() {
        Instant queued = Instant.parse("2026-09-16T01:00:00Z");
        Instant started = Instant.parse("2026-09-16T01:00:05Z");
        Instant ended = Instant.parse("2026-09-16T01:02:08Z");
        BuildRequest request = new BuildRequest("BuildMavenProject", "/p/app", "/p/app", "requester",
                                                "Requester", BuildTypeEnum.ASYNC, 60_000, true, control -> null);
        BuildJob job = new BuildJob("build-1", request, queued);
        job.markRunning(Clock.fixed(started, ZoneOffset.UTC));
        job.markFinished(BuildStatusEnum.SUCCESS, "BUILD SUCCESS", null, ended);
        return job;
    }
}
