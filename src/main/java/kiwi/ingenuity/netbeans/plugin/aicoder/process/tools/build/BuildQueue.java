package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * The single queue every Maven, Gradle and Ant build and test tool call goes through, async or inline.
 * <p>
 * Rules:
 * <ul>
 * <li>First come, first served; one build runs at a time across all projects.</li>
 * <li>At most one build per project may be queued or running, whichever AI asked.</li>
 * <li>Only the AI that queued an async build can stop it; inline builds cannot be stopped.</li>
 * <li>Closing an AI session cancels its queued and running builds — but only once no other AI is still listening to
 * them; a running IDE action is left to finish rather than marked cancelled.</li>
 * <li>IDE build actions ({@code BuildProject}, {@code CleanProject}, {@code CleanAndBuildProject}) go through this same
 * queue; a queued one can be stopped like any other, but a running one cannot, and they report
 * {@code COMPLETED}/{@code UNKNOWN} rather than {@code SUCCESS}, since NetBeans reports only that the action ran, not
 * that the build actually succeeded.</li>
 * <li>The last {@link #RECENT_BUILD_LIMIT} finished builds are kept, cancelled ones included, newest first.</li>
 * </ul>
 */
public final class BuildQueue {

    public static final int RECENT_BUILD_LIMIT = 5;
    /**
     * How many builds one project may have queued or running at once. One today; raise it if a project ever needs to
     * run more than one build concurrently.
     */
    public static final int MAX_ACTIVE_BUILDS_PER_PROJECT = 1;

    private static final Logger LOG = Logger.getLogger(BuildQueue.class.getName());
    private static final BuildQueue INSTANCE = new BuildQueue(Clock.systemUTC());

    /**
     * Headroom added to a project's longest observed successful build when sizing its inline limit, so a build that has
     * crept a little slower than last time is not cut off at exactly its previous duration.
     */
    private static final double OBSERVED_TIME_MARGIN = 1.2;

    private final Clock clock;
    private final Object lock = new Object();
    /**
     * Longest SUCCESSFUL run per project, in memory only — it rebuilds itself from ordinary use after a restart, and a
     * remembered time is never worth persisting across one.
     */
    private final Map<String, Duration> longestSuccessByProject = new HashMap<>();
    private final Deque<BuildJob> queued = new ArrayDeque<>();
    private final Deque<BuildJob> recent = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile BuildCompletionListener completionListener = job -> {
    };
    private BuildJob running;
    private Thread worker;

    public static BuildQueue getInstance() {
        return INSTANCE;
    }

    BuildQueue(Clock clock) {
        this.clock = clock;
    }

    /**
     * Sets who is told when a build finishes. The plugin registers the notifier that delivers async results to the
     * calling AI session.
     */
    public void setCompletionListener(BuildCompletionListener listener) {
        completionListener = listener != null ? listener : job -> {
        };
    }

    /**
     * Queues a validated build, or joins the caller to the identical build that is already queued or running.
     *
     * @return the caller's own new build, or the existing job it joined — {@code job.request() != request} identifies a
     * join, and the joining session is added to {@link BuildJob#listeners()}
     *
     * @throws BuildQueueException when a DIFFERENT build for the same project is already queued or running
     */
    public BuildJob submit(BuildRequest request) throws BuildQueueException {
        synchronized (lock) {
            BuildJob sameBuild = activeJobWithSameCall(request);
            if (sameBuild != null) {
                sameBuild.addListener(request.sessionId());
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Build queue: {0} joined {1} ({2}) instead of queueing a duplicate",
                            new Object[]{request.callerName(), sameBuild.id(), request.toolCall()});
                }
                return sameBuild;
            }
            BuildJob existing = activeJobForProject(request.projectKey());
            if (existing != null) {
                throw new BuildQueueException("A build for " + request.projectPath() + " is already "
                        + existing.status().name().toLowerCase() + " (" + existing.request().toolCall() + ", queued by "
                        + existing.request().callerName() + "). Only one build per project can be queued or running;"
                        + " use ListBuilds to see the queue.");
            }
            BuildJob job = new BuildJob("build-" + nextId.getAndIncrement(), request, clock.instant());
            queued.addLast(job);
            ensureWorker();
            lock.notifyAll();
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Build queue: queued {0} ({1}, {2}) for {3} by {4}; {5} ahead",
                        new Object[]{job.id(), request.type(), request.toolCall(), request.projectPath(),
                            request.callerName(), queued.size() - 1 + (running != null ? 1 : 0)});
            }
            return job;
        }
    }

    /**
     * Stops an async build queued by {@code sessionId}. A queued build is removed at once, whatever kind it is. A
     * running one has its process killed and finishes as {@link BuildStatusEnum#CANCELLED} — unless it is not
     * {@link BuildRequest#stoppableWhileRunning()}, in which case stopping is refused rather than reported as a
     * cancellation that did not happen.
     * <p>
     * Only the build's own requester may stop it. A caller naming an id that belongs to someone else's queued or
     * running async build is told who requested it and that only that AI can stop it — and, if the caller is one of its
     * listeners, that it will still receive the result — rather than the generic "belongs to you" refusal, which is
     * kept verbatim for an id that matches nothing or a non-async build.
     *
     * @return what happened, for the calling AI
     */
    public String stop(String buildId, String sessionId) {
        BuildJob removed = null;
        synchronized (lock) {
            BuildJob job = findActive(buildId);
            if (job == null || job.request().type() != BuildTypeEnum.ASYNC) {
                return "No queued or running async build with id " + buildId + " belongs to you. Use ListBuilds to see"
                        + " the ids of your async builds.";
            }
            if (!job.request().sessionId().equals(sessionId)) {
                return "Build " + buildId + " was requested by " + job.request().callerName() + "; only the AI that"
                        + " requested a build can stop it."
                        + (job.listeners().contains(sessionId)
                           ? " You are listening to it, so you will still receive its result." : "");
            }
            if (queued.remove(job)) {
                removed = job;
            }
            else if (!job.request().stoppableWhileRunning()) {
                return "Build " + buildId + " has already started and cannot be stopped from here: it is one of the"
                        + " user's IDE build actions, and NetBeans gives us no way to cancel one once it is running."
                        + " Stop it in the IDE if you need to; either way its result will reach you when it finishes.";
            }
            else {
                job.requestCancel(BuildCancelReasonEnum.STOPPED_BY_OWNER);
                return "Stopping running build " + buildId + "; it will be reported as cancelled.";
            }
        }
        finish(removed, BuildStatusEnum.CANCELLED, cancelledText(BuildCancelReasonEnum.STOPPED_BY_OWNER),
               BuildCancelReasonEnum.STOPPED_BY_OWNER);
        return "Cancelled queued build " + buildId + " before it started.";
    }

    /**
     * Handles a closing AI session. A build it only listened to simply loses that listener; a build it requested is
     * remembered as having lost its requester. Either way the build is cancelled only once nobody is left waiting on it
     * at all — the other AIs asked for exactly this build, so finishing it still serves them.
     * <p>
     * A running build that cannot be stopped (an IDE action) is left to finish instead: flagging it cancelled would
     * report a cancellation while the build carried on regardless.
     */
    public void cancelForSession(String sessionId) {
        List<BuildJob> removed = new ArrayList<>();
        synchronized (lock) {
            queued.removeIf(job -> {
                if (abandonedBy(job, sessionId)) {
                    removed.add(job);
                    return true;
                }
                return false;
            });
            // abandonedBy is evaluated for its bookkeeping either way; only the cancellation is conditional.
            if (running != null && abandonedBy(running, sessionId) && running.request().stoppableWhileRunning()) {
                running.requestCancel(BuildCancelReasonEnum.SESSION_CLOSED);
            }
        }
        for (BuildJob job : removed) {
            finish(job, BuildStatusEnum.CANCELLED, cancelledText(BuildCancelReasonEnum.SESSION_CLOSED),
                   BuildCancelReasonEnum.SESSION_CLOSED);
        }
    }

    /**
     * Applies one session's departure to {@code job} and reports whether nobody is left waiting on it — its requester
     * has closed AND every listener has too. Evaluated on every close rather than only the requester's, so a build
     * whose requester left first is still cancelled when its last listener goes.
     */
    private static boolean abandonedBy(BuildJob job, String sessionId) {
        job.removeListener(sessionId);
        if (job.request().sessionId().equals(sessionId)) {
            job.markRequesterGone();
        }
        return job.isRequesterGone() && job.listeners().isEmpty();
    }

    /**
     * Waits for an inline build to start. When it has not started in time it is removed from the queue as cancelled and
     * false is returned; if it started meanwhile, true.
     */
    public boolean awaitStart(BuildJob job, long waitMillis) throws InterruptedException {
        if (job.started().await(waitMillis, TimeUnit.MILLISECONDS)) {
            return true;
        }
        synchronized (lock) {
            if (!queued.remove(job)) {
                return true;
            }
        }
        finish(job, BuildStatusEnum.CANCELLED, cancelledText(BuildCancelReasonEnum.START_WAIT_EXPIRED),
               BuildCancelReasonEnum.START_WAIT_EXPIRED);
        return false;
    }

    /**
     * Waits for a build to finish and returns it.
     */
    public BuildJob awaitFinish(BuildJob job) throws InterruptedException {
        try {
            return job.finished().get();
        }
        catch (ExecutionException e) {
            throw new IllegalStateException("Build completion failed", e.getCause());
        }
    }

    /**
     * The current builds (the running one first, then the queue in order) and the recent finished builds, newest first.
     */
    public BuildQueueSnapshot snapshot() {
        synchronized (lock) {
            List<BuildJob> current = new ArrayList<>();
            if (running != null) {
                current.add(running);
            }
            current.addAll(queued);
            return new BuildQueueSnapshot(clock.instant(), List.copyOf(current), List.copyOf(recent),
                                          Map.copyOf(longestSuccessByProject));
        }
    }

    /**
     * Whether any build is queued or running. The IDE build actions refuse to run while this is true, so they cannot
     * disturb a build in progress.
     */
    public boolean hasActiveBuilds() {
        synchronized (lock) {
            return running != null || !queued.isEmpty();
        }
    }

    /**
     * How many builds are ahead of {@code job}, counting the running one; -1 when it is no longer queued.
     */
    public int positionOf(BuildJob job) {
        synchronized (lock) {
            int index = 0;
            for (BuildJob candidate : queued) {
                if (candidate == job) {
                    return index + (running != null ? 1 : 0);
                }
                index++;
            }
            return -1;
        }
    }

    /**
     * Test seam: stops the worker thread.
     */
    void shutdown() {
        synchronized (lock) {
            if (worker != null) {
                worker.interrupt();
                worker = null;
            }
        }
    }

    /**
     * Remembers how long a build took when — and only when — it ran to completion successfully AND is one its requester
     * marked as counting toward this record ({@link BuildRequest#countsTowardLongestSuccess}). A failed, timed-out or
     * cancelled run never finished the work, and a dependency download is not a build at all, so neither's duration
     * says anything about how long the project's own build needs. Caller holds the lock.
     */
    private void recordIfLongestSuccess(BuildJob job, BuildStatusEnum status) {
        if (status != BuildStatusEnum.SUCCESS || job.duration() == null || !job.request().countsTowardLongestSuccess()) {
            return;
        }
        String projectKey = job.request().projectKey();
        Duration previous = longestSuccessByProject.get(projectKey);
        if (previous == null || job.duration().compareTo(previous) > 0) {
            longestSuccessByProject.put(projectKey, job.duration());
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Build queue: {0} is now the longest successful build of {1}",
                        new Object[]{job.duration(), projectKey});
            }
        }
    }

    /**
     * The identical build — same project, same canonical tool call — already queued or running, or null. An AI asking
     * for exactly the build someone else already asked for listens to that one instead of running it a second time. The
     * tool call is canonicalised by {@code BuildSubmitter.toolCall}, so the same options in a different key order still
     * match.
     */
    private BuildJob activeJobWithSameCall(BuildRequest request) {
        if (running != null && sameCall(running, request)) {
            return running;
        }
        return queued.stream().filter(job -> sameCall(job, request)).findFirst().orElse(null);
    }

    private static boolean sameCall(BuildJob job, BuildRequest request) {
        return job.request().projectKey().equals(request.projectKey())
                && job.request().toolCall().equals(request.toolCall());
    }

    /**
     * The active build a new one for {@code projectKey} would collide with, or null while the project is still under
     * {@link #MAX_ACTIVE_BUILDS_PER_PROJECT}. The oldest is returned, so a refusal names the build that has held the
     * project longest.
     */
    private BuildJob activeJobForProject(String projectKey) {
        List<BuildJob> active = new ArrayList<>();
        if (running != null && running.request().projectKey().equals(projectKey)) {
            active.add(running);
        }
        queued.stream().filter(job -> job.request().projectKey().equals(projectKey)).forEach(active::add);
        return active.size() >= MAX_ACTIVE_BUILDS_PER_PROJECT ? active.get(0) : null;
    }

    private BuildJob findActive(String buildId) {
        if (running != null && running.id().equals(buildId)) {
            return running;
        }
        return queued.stream().filter(job -> job.id().equals(buildId)).findFirst().orElse(null);
    }

    private void ensureWorker() {
        if (worker == null) {
            worker = new Thread(this::workLoop, "ai-coder-build-queue");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void workLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            BuildJob job;
            synchronized (lock) {
                while (queued.isEmpty()) {
                    try {
                        lock.wait();
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                job = queued.pollFirst();
                running = job;
                job.markRunning(clock);
            }
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "Build queue: started {0} ({1}) for {2}",
                        new Object[]{job.id(), job.request().toolCall(), job.request().projectPath()});
            }
            job.started().countDown();
            BuildOutcome outcome;
            try {
                outcome = job.request().work().run(job.control());
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Build " + job.id() + " failed unexpectedly", e);
                outcome = BuildOutcome.failed("Build error: " + e.getMessage());
            }
            if (job.control().isCancelled()) {
                BuildCancelReasonEnum reason = job.cancelReason() != null ? job.cancelReason()
                                               : BuildCancelReasonEnum.STOPPED_BY_OWNER;
                String text = cancelledText(reason);
                if (outcome != null && outcome.result() != null && !outcome.result().isBlank()) {
                    text += "\n\n" + outcome.result();
                }
                finish(job, BuildStatusEnum.CANCELLED, text, reason);
            }
            else if (outcome == null) {
                finish(job, BuildStatusEnum.FAILED, "Build produced no result.", null);
            }
            else {
                finish(job, outcome.status(), outcome.result(), null);
            }
        }
    }

    /**
     * The longest a build of {@code projectKey} has actually taken to run successfully, or null when none has. Only
     * successful runs count, so a build that failed, timed out or was cancelled — none of which ran to completion — can
     * never inflate it.
     */
    public Duration longestSuccessFor(String projectKey) {
        synchronized (lock) {
            return longestSuccessByProject.get(projectKey);
        }
    }

    /**
     * How long an inline build of {@code projectKey} may run: {@link TimeoutEnum#BUILD_LOCK_LIFETIME_MILLIS} as a
     * floor, or this project's longest successful build plus {@link #OBSERVED_TIME_MARGIN} when that is longer, so a
     * genuinely slow project stops failing inline once it has proved how long it needs (decision 29).
     * <p>
     * Fed by every success, async included — deliberately. A project slower than the floor could never succeed inline,
     * so if only inline runs counted it could never record a time and the limit could never grow: one async success is
     * what lifts it.
     */
    public long inlineTimeoutMillisFor(String projectKey) {
        long floor = TimeoutEnum.BUILD_LOCK_LIFETIME_MILLIS.millis();
        Duration longest = longestSuccessFor(projectKey);
        if (longest == null) {
            return floor;
        }
        return Math.max(floor, Math.round(longest.toMillis() * OBSERVED_TIME_MARGIN));
    }

    private void finish(BuildJob job, BuildStatusEnum status, String result, BuildCancelReasonEnum reason) {
        synchronized (lock) {
            job.markFinished(status, result, reason, clock.instant());
            if (running == job) {
                running = null;
            }
            recordIfLongestSuccess(job, status);
            recent.addFirst(job);
            while (recent.size() > RECENT_BUILD_LIMIT) {
                recent.removeLast();
            }
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Build queue: finished {0} as {1}{2}",
                    new Object[]{job.id(), status, reason != null ? " (" + reason.description() + ")" : ""});
        }
        // Also released for a build that never started (cancelled while queued), so an inline caller blocked in
        // awaitStart wakes up at once instead of waiting out its full start limit.
        job.started().countDown();
        job.finished().complete(job);
        try {
            completionListener.onFinished(job);
        }
        catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Build completion listener failed for " + job.id(), e);
        }
    }

    private static String cancelledText(BuildCancelReasonEnum reason) {
        return "Build cancelled: " + reason.description() + ".";
    }
}
