package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildJob;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildOutcome;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildQueue;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildQueueException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildQueueSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildReportFormatter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildStatusEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildWork;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOutputFormatter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildProcessRunner;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.PreparedBuild;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ProjectActionResult;

/**
 * Puts a validated Maven, Gradle or Ant build or test call through the {@link BuildQueue}: an async call returns its
 * queued build at once, an inline call waits for its turn and returns the result. All the queue wording the calling AI
 * sees lives here.
 */
public final class BuildSubmitter {

    /**
     * How long an async build may run, in words, for every message and tool description that mentions it. Derived from
     * the limit itself so raising {@link LockTypeEnum#ASYNC_BUILD_LOCK} cannot leave the tools telling the calling AI a
     * number the queue no longer enforces — this text used to be written out by hand in seven places.
     */
    public static final String ASYNC_LIMIT_TEXT
            = TimeUnit.MILLISECONDS.toHours(LockTypeEnum.ASYNC_BUILD_LOCK.getLifetimeMillis()) + " hours";

    static final String OPTIONS_FOOTER = "\n\nOptions: pass async: true to queue a build without waiting (up to "
            + ASYNC_LIMIT_TEXT
            + ", result delivered as a message); ListBuilds shows the build queue; StopAsyncBuild cancels one of your async"
            + " builds.";
    static final String TIMED_OUT_HINT = "Timed out: re-run with async: true to queue it with a " + ASYNC_LIMIT_TEXT
            + " limit.\n\n";
    /**
     * Appended to each queued build tool's GetInstructions line, so the instruction list itself explains the queue.
     */
    public static final String QUEUE_INSTRUCTION = "; async: true queues it and returns a build id (result delivered as a"
            + " message, up to " + ASYNC_LIMIT_TEXT + "); ListBuilds shows the queue, StopAsyncBuild cancels your async build";

    /**
     * Submits a tool's prepared build to the plugin's build queue, reading {@code async} from the tool's arguments.
     */
    public static String submit(String toolName, ToolRequestArguments args, String projectPath, PreparedBuild prepared,
                                AbstractAiSession session) {
        // ALWAYS run the shape check, even when preparation already failed: a wrong-type option can itself be WHY
        // preparation failed — a string goals value reads as no array at all, so the Maven provider sees an empty
        // goals list and refuses with "goals must not be empty", a misleading message that would otherwise outrun the
        // real, correct shape error. The shape error takes precedence, as it did before item 8. Only the backend is
        // unavailable when there is no prepared build or it is already an error; validate(null, ...) still runs the
        // common checks plus every backend's option checks together.
        BuildOutputFormatter.Backend backend = prepared != null && !prepared.isError() ? prepared.backend() : null;
        String shapeError = BuildOptionShapeValidator.validate(backend, args);
        PreparedBuild validated = shapeError == null ? prepared : PreparedBuild.error("Error: " + shapeError);
        boolean async = args.bool(McpToolPropertyEnum.ASYNC.key());
        return submit(BuildQueue.getInstance(), validated, toolCall(toolName, args), projectPath, session, async,
                      control -> BuildProcessRunner.run(validated, control));
    }

    /**
     * Submits a build to {@code queue}. A prepared error is returned unchanged and nothing is queued.
     */
    /**
     * Puts an IDE build action (BuildProject, CleanProject, CleanAndBuildProject) through the same queue as every other
     * build. It is marked not stoppable once running: NetBeans gives the caller of {@code ActionProvider.invokeAction}
     * no way to cancel, so only a queued one can be stopped.
     *
     * @param action runs the IDE action and blocks until it finishes, or reports that completion could not be confirmed
     */
    public static String submitIdeAction(String toolName, ToolRequestArguments args, String projectPath,
                                         PreparedBuild prepared, AbstractAiSession session,
                                         Supplier<ProjectActionResult> action) {
        boolean async = args.bool(McpToolPropertyEnum.ASYNC.key());
        return submit(BuildQueue.getInstance(), prepared, toolCall(toolName, args), projectPath, session, async, false,
                      control -> ideOutcome(action.get()));
    }

    /**
     * What an IDE build action's outcome becomes in the queue. NetBeans' ActionProgress.finished(true) only means the
     * action ran normally — its meaning varies by project type — so it is reported as COMPLETED, never SUCCESS. A
     * failure the action reported, or an error resolving it, is FAILED; an action whose completion could not be
     * observed is UNKNOWN; one still running at the run limit is TIMED_OUT.
     */
    static BuildOutcome ideOutcome(ProjectActionResult result) {
        return switch (result.kind()) {
            case COMPLETED ->
                new BuildOutcome(BuildStatusEnum.COMPLETED, result.message());
            case FAILED, ERROR ->
                BuildOutcome.failed(result.message());
            case NOT_TRACKED, NOT_AWAITED ->
                new BuildOutcome(BuildStatusEnum.UNKNOWN, result.message());
            case STILL_RUNNING ->
                BuildOutcome.timedOut(result.message());
        };
    }

    static String submit(BuildQueue queue, PreparedBuild prepared, String toolCall, String projectPath,
                         AbstractAiSession session, boolean async, BuildWork work) {
        return submit(queue, prepared, toolCall, projectPath, session, async, true, work);
    }

    /**
     * @param stoppableWhileRunning false for an IDE build action, which cannot be stopped once it has started — see
     * {@link BuildRequest#stoppableWhileRunning()}
     */
    static String submit(BuildQueue queue, PreparedBuild prepared, String toolCall, String projectPath,
                         AbstractAiSession session, boolean async, boolean stoppableWhileRunning, BuildWork work) {
        if (prepared == null || prepared.isError()) {
            return prepared == null ? "Error: no prepared build" : prepared.error();
        }
        BuildTypeEnum type = async ? BuildTypeEnum.ASYNC : BuildTypeEnum.INLINE;
        String projectKey = prepared.root().getAbsolutePath();
        // Async keeps its flat limit; an inline build gets one sized to what this project has actually needed before.
        long timeoutMillis = async ? LockTypeEnum.ASYNC_BUILD_LOCK.getLifetimeMillis()
                             : queue.inlineTimeoutMillisFor(projectKey);
        BuildRequest request = new BuildRequest(toolCall, projectKey, projectPath, session.getId(),
                                                session.getSessionName(), type, timeoutMillis, stoppableWhileRunning,
                                                prepared.countsTowardLongestSuccess(), work);
        try {
            BuildJob job = queue.submit(request);
            if (job.request() != request) {
                // Someone already asked for exactly this build; we listen to theirs rather than running it twice.
                // Returned even for an inline call, so it is not left blocking behind an async build's much longer limit.
                return joinedReply(job, session.getId());
            }
            if (async) {
                return queuedReply(queue, job);
            }
            long startWaitMillis = LockTypeEnum.BUILD_LOCK.getWaitTimeoutMillis();
            if (!queue.awaitStart(job, startWaitMillis)) {
                return "Build did not start within " + TimeUnit.MILLISECONDS.toSeconds(startWaitMillis)
                        + "s because other builds were queued ahead. Retry with async: true to queue it, or check"
                        + " ListBuilds." + OPTIONS_FOOTER;
            }
            job = queue.awaitFinish(job);
            String result = BuildReportFormatter.aiResult(job);
            return (job.status() == BuildStatusEnum.TIMED_OUT ? TIMED_OUT_HINT : "") + result + OPTIONS_FOOTER;
        }
        catch (BuildQueueException e) {
            return e.getMessage();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the build queue." + OPTIONS_FOOTER;
        }
    }

    /**
     * The tool call as ListBuilds shows it: the tool name and its arguments, without credentials. {@code async} is left
     * out because the Type column already shows it.
     */
    static String toolCall(String toolName, ToolRequestArguments args) {
        return toolName + " " + canonical(args.withoutKeys(McpToolPropertyEnum.SESSION_ID.key(),
                                                           McpToolPropertyEnum.SECRET_KEY.key(),
                                                           McpToolPropertyEnum.ASYNC.key()));
    }

    /**
     * The same options written the same way whoever passed them: object keys are sorted, so two AIs asking for the
     * identical build in a different key order produce identical text and the queue can recognise the second call as
     * the build it is already running. Array order is left alone — {@code goals} and {@code targets} are sequences, not
     * sets, and reordering them would change the build.
     */
    private static JsonElement canonical(JsonElement element) {
        if (element.isJsonObject()) {
            Map<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            JsonObject out = new JsonObject();
            sorted.forEach((key, value) -> out.add(key, canonical(value)));
            return out;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            element.getAsJsonArray().forEach(item -> out.add(canonical(item)));
            return out;
        }
        return element;
    }

    /**
     * Someone had already asked for exactly this build, so the caller was added as a listener instead of the build
     * being run twice. The build's own requester gets a short confirmation instead of being told about themselves in
     * the third person (item 7, 2026-09-17); every other session gets the same completion message the requester gets,
     * but only the requester can stop it.
     */
    private static String joinedReply(BuildJob job, String callerSessionId) {
        if (job.request().sessionId().equals(callerSessionId)) {
            return requesterRepeatReply(job);
        }
        return "Already " + job.status().name().toLowerCase() + " as " + job.id() + ": " + job.request().toolCall()
                + "\nProject: " + job.request().projectPath()
                + "\nRequested by: " + job.request().callerName()
                + "\nIt was NOT queued a second time — you have been added as a listener, and the same result will be"
                + " delivered to you as a message when it finishes."
                + "\nUse ListBuilds to see the queue. Only " + job.request().callerName() + " can stop this build.";
    }

    /**
     * The build's own requester asking again for the identical call. {@code StopAsyncBuild} is offered only when this
     * build is actually stoppable by its requester right now — the same condition {@link BuildQueue#stop} itself
     * applies: a queued async build can always be removed, a running one only when
     * {@link BuildRequest#stoppableWhileRunning()}.
     */
    private static String requesterRepeatReply(BuildJob job) {
        boolean stoppable = job.request().type() == BuildTypeEnum.ASYNC
                && (job.status() == BuildStatusEnum.QUEUED || job.request().stoppableWhileRunning());
        String reply = "You already have this build " + job.status().name().toLowerCase() + " as " + job.id()
                + " — it was not queued again. Its result will be delivered to you once, when it finishes.";
        return stoppable ? reply + " Use StopAsyncBuild with buildId " + job.id() + " to cancel it." : reply;
    }

    private static String queuedReply(BuildQueue queue, BuildJob job) {
        int ahead = Math.max(0, queue.positionOf(job));
        BuildQueueSnapshot snapshot = queue.snapshot();
        StringBuilder sb = new StringBuilder("Queued async build ").append(job.id()).append(": ")
                .append(job.request().toolCall())
                .append("\nProject: ").append(job.request().projectPath())
                .append("\nPosition: ").append(ahead).append(" build(s) ahead of it");
        if (!snapshot.current().isEmpty() && snapshot.current().get(0) != job
                && snapshot.current().get(0).status() == BuildStatusEnum.RUNNING) {
            BuildJob running = snapshot.current().get(0);
            sb.append(" (running now: ").append(running.request().toolCall()).append(" for ")
                    .append(running.request().projectPath()).append(')');
        }
        return sb.append("\nTime limit: ").append(ASYNC_LIMIT_TEXT).append(" once it starts running")
                .append("\nThe result will be delivered to you as a message when it finishes.")
                .append("\nUse ListBuilds to see the queue and StopAsyncBuild with buildId ").append(job.id())
                .append(" to cancel it.")
                .toString();
    }

    private BuildSubmitter() {
    }
}
