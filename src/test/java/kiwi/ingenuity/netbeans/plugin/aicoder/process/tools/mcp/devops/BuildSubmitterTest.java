package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildJob;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildOutcome;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildQueue;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildStatusEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOutputFormatter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.PreparedBuild;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ProjectActionResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real plugin-wide {@link BuildQueue} with trivial builds; every test uses its own temporary project
 * directory, so the one-build-per-project rule never links tests together.
 */
class BuildSubmitterTest {

    private static final BuildQueue QUEUE = BuildQueue.getInstance();

    @Test
    void aPreparedErrorIsReturnedAndNothingIsQueued(@TempDir Path project) {
        String result = BuildSubmitter.submit(QUEUE, PreparedBuild.error("Error: goals must not be empty"), "BuildMavenProject {}",
                                              project.toString(), session(), false,
                                              control -> BuildOutcome.completed(true, "never runs"));

        assertEquals("Error: goals must not be empty", result);
        assertTrue(QUEUE.snapshot().current().stream()
                .noneMatch(job -> job.request().projectPath().equals(project.toString())));
    }

    @Test
    void anAsyncBuildReturnsItsIdAndTheQueueToolsAtOnce(@TempDir Path project) throws Exception {
        CountDownLatch release = new CountDownLatch(1);

        String reply = BuildSubmitter.submit(QUEUE, prepared(project), "BuildMavenProject {\"goals\":[\"install\"]}",
                                             project.toString(), session(), true, control -> {
                                         await(release);
                                         return BuildOutcome.completed(true, "BUILD SUCCESS");
                                     });

        assertTrue(reply.startsWith("Queued async build build-"), reply);
        assertTrue(reply.contains("Project: " + project), reply);
        // Asserted against the constant, not a literal: the limit is configurable and the wording is derived from it,
        // so hardcoding a number here would just rot the next time it changes.
        assertTrue(reply.contains("Time limit: " + BuildSubmitter.ASYNC_LIMIT_TEXT + " once it starts running"), reply);
        assertTrue(reply.contains("delivered to you as a message"), reply);
        assertTrue(reply.contains("Use ListBuilds to see the queue and StopAsyncBuild with buildId build-"), reply);
        BuildJob job = activeJobFor(project);
        assertNotNull(job);
        assertTrue(reply.contains(job.id()), reply);

        release.countDown();
        assertEquals(BuildStatusEnum.SUCCESS, QUEUE.awaitFinish(job).status());
    }

    @Test
    void anInlineBuildReturnsItsResultTimingsAndTheOptionsFooter(@TempDir Path project) {
        String result = BuildSubmitter.submit(QUEUE, prepared(project), "BuildMavenProject {}", project.toString(), session(),
                                              false, control -> BuildOutcome.completed(true, "BUILD SUCCESS"));

        assertTrue(result.startsWith("Build build-"), result);
        assertTrue(result.contains("Status: SUCCESS"), result);
        assertTrue(result.contains("Duration: "), result);
        assertTrue(result.contains("BUILD SUCCESS"), result);
        assertTrue(result.endsWith(BuildSubmitter.OPTIONS_FOOTER), result);
    }

    @Test
    void anInlineTimeoutLeadsWithTheAsyncHint(@TempDir Path project) {
        String result = BuildSubmitter.submit(QUEUE, prepared(project), "RunMavenTests {}", project.toString(), session(),
                                              false, control -> BuildOutcome.timedOut("Timed out after 600s"));

        assertTrue(result.startsWith(BuildSubmitter.TIMED_OUT_HINT), result);
        assertTrue(result.contains("Status: TIMED_OUT"), result);
        assertTrue(result.endsWith(BuildSubmitter.OPTIONS_FOOTER), result);
    }

    @Test
    void aSecondBuildForTheSameProjectIsRefused(@TempDir Path project) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BuildSubmitter.submit(QUEUE, prepared(project), "BuildMavenProject {}", project.toString(), session(), true,
                              control -> {
                                  await(release);
                                  return BuildOutcome.completed(true, "BUILD SUCCESS");
                              });

        // Captured while it is still blocked: once released it can finish and leave the current list before a lookup.
        BuildJob first = activeJobFor(project);
        assertNotNull(first);

        String refused = BuildSubmitter.submit(QUEUE, prepared(project), "RunMavenTests {}", project.toString(), session(),
                                               false, control -> BuildOutcome.completed(true, "never runs"));

        assertTrue(refused.startsWith("A build for " + project + " is already"), refused);
        release.countDown();
        QUEUE.awaitFinish(first);
    }

    @Test
    void aSecondAiAskingForTheSameBuildIsToldItAlreadyExistsInsteadOfQueueingItTwice(@TempDir Path project) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String sameCall = "BuildMavenProject {\"projectPath\":\"" + project + "\"}";
        BuildSubmitter.submit(QUEUE, prepared(project), sameCall, project.toString(), session(), true, control -> {
                          await(release);
                          return BuildOutcome.completed(true, "BUILD SUCCESS");
                      });
        // Captured while it is still blocked, so the lookup cannot race its completion.
        BuildJob first = activeJobFor(project);
        assertNotNull(first);

        String joined = BuildSubmitter.submit(QUEUE, prepared(project), sameCall, project.toString(), session(), true,
                                              control -> BuildOutcome.completed(true, "never runs"));

        assertTrue(joined.startsWith("Already "), joined);
        assertTrue(joined.contains(first.id()), joined);
        assertTrue(joined.contains("added as a listener"), joined);
        assertEquals(1, QUEUE.snapshot().current().stream()
                     .filter(job -> job.request().projectPath().equals(project.toString())).count(),
                     "the identical build must not be queued a second time");

        release.countDown();
        QUEUE.awaitFinish(first);
    }

    @Test
    void theRequesterRepeatingItsOwnAsyncCallGetsAFirstPersonReplyWithAStopHint(@TempDir Path project) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String sameCall = "BuildMavenProject {\"projectPath\":\"" + project + "\"}";
        FakeSession requester = session();
        PreparedBuild pb = prepared(project);
        // Built and submitted directly rather than through BuildSubmitter.submit: an ASYNC call there does not block,
        // but going through QUEUE.submit lets awaitStart pin the job to RUNNING before the repeat call, so the
        // assertion below is not racing the worker thread for "queued" vs "running" wording.
        BuildJob first = QUEUE.submit(new BuildRequest(sameCall, pb.root().getAbsolutePath(), project.toString(),
                                                       requester.getId(), requester.getSessionName(),
                                                       BuildTypeEnum.ASYNC, 60_000, true, control -> {
                                                           await(release);
                                                           return BuildOutcome.completed(true, "BUILD SUCCESS");
                                                       }));
        assertTrue(QUEUE.awaitStart(first, TimeUnit.SECONDS.toMillis(10)));

        String repeat = BuildSubmitter.submit(QUEUE, pb, sameCall, project.toString(), requester, true,
                                              control -> BuildOutcome.completed(true, "never runs"));

        assertEquals("You already have this build running as " + first.id() + " — it was not queued again. Its"
                + " result will be delivered to you once, when it finishes. Use StopAsyncBuild with buildId "
                + first.id() + " to cancel it.", repeat);
        assertFalse(repeat.contains("added as a listener"), "the requester is not told about itself in the third person");
        assertFalse(repeat.contains("Requested by"), repeat);

        release.countDown();
        QUEUE.awaitFinish(first);
    }

    @Test
    void theRequesterRepeatingItsOwnInlineCallGetsNoStopHintBecauseInlineBuildsCannotBeStopped(
            @TempDir Path project) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String sameCall = "BuildMavenProject {\"projectPath\":\"" + project + "\"}";
        FakeSession requester = session();
        PreparedBuild pb = prepared(project);
        // Built and submitted directly via QUEUE.submit — an INLINE call through BuildSubmitter.submit blocks its
        // caller until the build finishes, which this test's own CountDownLatch would then deadlock against.
        BuildJob first = QUEUE.submit(new BuildRequest(sameCall, pb.root().getAbsolutePath(), project.toString(),
                                                       requester.getId(), requester.getSessionName(),
                                                       BuildTypeEnum.INLINE, 60_000, true, control -> {
                                                           await(release);
                                                           return BuildOutcome.completed(true, "BUILD SUCCESS");
                                                       }));
        assertTrue(QUEUE.awaitStart(first, TimeUnit.SECONDS.toMillis(10)));

        String repeat = BuildSubmitter.submit(QUEUE, pb, sameCall, project.toString(), requester, true,
                                              control -> BuildOutcome.completed(true, "never runs"));

        assertEquals("You already have this build running as " + first.id() + " — it was not queued again. Its"
                + " result will be delivered to you once, when it finishes.", repeat,
                     "an inline build cannot be stopped, so no StopAsyncBuild hint");

        release.countDown();
        QUEUE.awaitFinish(first);
    }

    @Test
    void theToolCallShownInTheQueueLeavesOutCredentialsAndAsync() {
        JsonObject raw = new JsonObject();
        raw.addProperty(McpToolPropertyEnum.PROJECT_PATH.key(), "/p/app");
        raw.addProperty(McpToolPropertyEnum.SESSION_ID.key(), "session-1");
        raw.addProperty(McpToolPropertyEnum.SECRET_KEY.key(), "secret");
        raw.addProperty(McpToolPropertyEnum.ASYNC.key(), true);

        String toolCall = BuildSubmitter.toolCall("BuildMavenProject", new ToolRequestArguments(raw));

        assertEquals("BuildMavenProject {\"projectPath\":\"/p/app\"}", toolCall);
        assertFalse(toolCall.contains("secret"));
    }

    @Test
    void ideOutcomeMapsEveryActionKindToItsReportedStatus() {
        assertIdeOutcome(ProjectActionResult.Kind.COMPLETED, BuildStatusEnum.COMPLETED);
        assertIdeOutcome(ProjectActionResult.Kind.FAILED, BuildStatusEnum.FAILED);
        assertIdeOutcome(ProjectActionResult.Kind.ERROR, BuildStatusEnum.FAILED);
        assertIdeOutcome(ProjectActionResult.Kind.NOT_TRACKED, BuildStatusEnum.UNKNOWN);
        assertIdeOutcome(ProjectActionResult.Kind.NOT_AWAITED, BuildStatusEnum.UNKNOWN);
        assertIdeOutcome(ProjectActionResult.Kind.STILL_RUNNING, BuildStatusEnum.TIMED_OUT);
    }

    private static void assertIdeOutcome(ProjectActionResult.Kind kind, BuildStatusEnum expected) {
        String message = "message for " + kind;
        BuildOutcome outcome = BuildSubmitter.ideOutcome(new ProjectActionResult(message, kind));
        assertEquals(expected, outcome.status(), kind + " must map to " + expected);
        assertEquals(message, outcome.result(), "the action's message must pass through unchanged");
    }

    private static PreparedBuild prepared(Path project) {
        try {
            Files.createDirectories(project);
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return new PreparedBuild(null, "submitter-test", project.toFile(), List.of("mvn", "package"),
                                 BuildOutputFormatter.Backend.MAVEN);
    }

    private static BuildJob activeJobFor(Path project) {
        return QUEUE.snapshot().current().stream()
                .filter(job -> job.request().projectPath().equals(project.toString()))
                .findFirst().orElse(null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static FakeSession session() {
        return new FakeSession(AiSession.create(null, AiTypeEnum.CLAUDE));
    }

    private static final class FakeSession extends AbstractAiSession {

        FakeSession(AiSession session) {
            super(session);
        }

        @Override
        public String getId() {
            return getAiSession().id();
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
