package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotCompaction;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotEventSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotLaunch;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotQuotaSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdk;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSessionSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model-change re-establish ({@code sendPrompt} with no live session) against a fake SDK whose re-establish create
 * the test holds open: a result that arrives after a stop and restart is dropped silently, a failure names its cause,
 * and a Stop pressed while it runs ends the turn and keeps the prompt from being sent.
 */
class GithubCopilotProcessManagerReestablishTest {

    private static final String RECYCLE_THREAD = "copilot-model-recycle";

    private final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
    private final CompletableFuture<CopilotSdkSession> heldCreate = new CompletableFuture<>();
    private final CountDownLatch heldCreateCalled = new CountDownLatch(1);
    private GithubCopilotProcessManager manager;
    private String executable;
    private ScriptedSdk sdk;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        McpServerRegistry.portOverride = 0;
        Path script = dir.resolve("copilot");
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        assumeTrue(script.toFile().setExecutable(true), "needs a filesystem with an executable bit");
        executable = script.toString();
        manager = new GithubCopilotProcessManager(events::add);
        manager.setCurrentSession(new AiSession("gh-reestablish", "Test", null, AiTypeEnum.GitHubCoPilot, null,
                                                new GithubCopilotSessionSettings(), Instant.now(), Instant.now()));
        sdk = new ScriptedSdk();
        manager.setSdkForTests(sdk);
    }

    @AfterEach
    void tearDown() throws Exception {
        heldCreate.completeExceptionally(new IllegalStateException("test over"));
        joinRecycleThreads();
        manager.stop();
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    @Timeout(60)
    void aReestablishSupersededByAStopAndRestartStaysSilentAndLeavesTheNewTurnAlone() throws Exception {
        beginReestablish("first");

        manager.stop();
        manager.start(executable, "gpt-a");
        assertTrue(manager.isRunning());
        manager.sendPrompt("second", null, List.of());
        assertTrue(manager.isBusy(), "the restarted session's turn is in flight");
        events.clear();

        FakeSession late = new FakeSession("late");
        heldCreate.complete(late);
        joinRecycleThreads();

        assertTrue(late.closed, "the superseded session must be closed");
        assertEquals(0, late.sends.get(), "the superseded prompt must not be sent");
        assertTrue(manager.isBusy(), "a superseded re-establish must not clear the new generation's turn");
        assertTrue(events.isEmpty(), () -> "a superseded re-establish must report nothing: " + events);
    }

    @Test
    @Timeout(60)
    void aFailedReestablishReportsFailedWithTheSdkDetail() throws Exception {
        beginReestablish("first");

        heldCreate.completeExceptionally(new IllegalStateException("model gpt-z is not enabled for this account"));
        joinRecycleThreads();

        StatusEvent failed = events.stream()
                .filter(StatusEvent.class::isInstance)
                .map(StatusEvent.class::cast)
                .filter(status -> status.type() == StatusEventTypeEnum.FAILED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FAILED status: " + events));
        assertTrue(failed.text().contains("model gpt-z is not enabled for this account"), failed.text());
        assertFalse(manager.isBusy(), "a failed re-establish ends the turn");
    }

    @Test
    @Timeout(60)
    void aStopDuringTheReestablishEndsTheTurnAndTheQueuedPromptIsNotSent() throws Exception {
        beginReestablish("first");

        manager.interrupt(InterruptTypeEnum.Cancel);

        assertTrue(hasStatus(StatusEventTypeEnum.STOPPED), () -> "Stop must be acknowledged: " + events);
        assertFalse(manager.isBusy(), "Stop must clear the turn even though no session exists yet");

        FakeSession fresh = new FakeSession("fresh");
        heldCreate.complete(fresh);
        joinRecycleThreads();

        assertEquals(0, fresh.sends.get(), "the prompt queued before the Stop must not be sent");
        assertFalse(fresh.closed, "the new session is kept for the next turn");
        assertFalse(hasStatus(StatusEventTypeEnum.FAILED), () -> "a stopped re-establish is not a failure: " + events);
        assertFalse(manager.isBusy());

        manager.sendPrompt("next", null, List.of());
        assertEquals(1, fresh.sends.get(), "the next turn uses the re-established session");
    }

    @Test
    @Timeout(60)
    void aSessionThatCannotBePublishedIsClosedAndTheTurnFails() throws Exception {
        beginReestablish("first");

        FakeSession unusable = new FakeSession("unusable");
        unusable.subscribeFailure = new IllegalStateException("subscribe refused");
        heldCreate.complete(unusable);
        joinRecycleThreads();

        assertTrue(unusable.closed, "a session that could not be published must be closed");
        assertTrue(failedText().contains("subscribe refused"), failedText());
        assertFalse(manager.isBusy());
        assertFalse((boolean) field("reestablishing"), "the re-establish must not stay in flight");
    }

    @Test
    @Timeout(60)
    void aFailureOutsideTheReestablishItselfStillEndsTheTurn() throws Exception {
        manager.start(executable, "gpt-a");
        assertTrue(manager.isRunning(), () -> "events were: " + events);
        // An effort change makes the live session stale; the send detaches it and the recycle thread closes it, and
        // that close fails with an Error the close helper does not catch.
        sdk.lastSession.closeError = new AssertionError("close blew up");
        manager.setReasoningEffort("high");

        manager.sendPrompt("first", null, List.of());
        joinRecycleThreads();

        assertTrue(failedText().contains("close blew up"), failedText());
        assertFalse(manager.isBusy(), "the turn must not stay busy");
        assertFalse((boolean) field("reestablishing"), "the tab must not stay \"reconnecting\"");
    }

    @Test
    @Timeout(60)
    void aStopAfterTheSessionWasPublishedKeepsTheQueuedPromptFromBeingSent() throws Exception {
        manager.start(executable, "gpt-a");
        FakeSession live = sdk.lastSession;
        // The Stop lands in the gap between publishing the session and re-entering the send: the ordinary abort.
        manager.interrupt(InterruptTypeEnum.Cancel);

        Method sendAfter = GithubCopilotProcessManager.class.getDeclaredMethod(
                "sendAfterReestablish", long.class, String.class, File.class, List.class);
        sendAfter.setAccessible(true);
        sendAfter.invoke(manager, ((AtomicLong) field("generation")).get(), "queued", null, List.of());

        assertEquals(0, live.sends.get(), "a Stop that came first must not be overridden by the queued send");
        assertFalse(manager.isBusy());
    }

    private String failedText() {
        return events.stream()
                .filter(StatusEvent.class::isInstance)
                .map(StatusEvent.class::cast)
                .filter(status -> status.type() == StatusEventTypeEnum.FAILED)
                .map(StatusEvent::text)
                .findFirst()
                .orElse("no FAILED status: " + events);
    }

    private Object field(String name) throws Exception {
        Field field = GithubCopilotProcessManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(manager);
    }

    /**
     * Starts the session, drops it as a model change would, and sends {@code prompt}, which re-establishes the session
     * on a background thread. Returns once that re-establish is waiting in the held create.
     */
    private void beginReestablish(String prompt) throws InterruptedException {
        manager.start(executable, "gpt-a");
        assertTrue(manager.isRunning(), () -> "events were: " + events);
        manager.recycleForModelChange();
        sdk.holdNextCreate = true;
        manager.sendPrompt(prompt, null, List.of());
        assertTrue(heldCreateCalled.await(30, TimeUnit.SECONDS), "the re-establish must reach the create");
    }

    private boolean hasStatus(StatusEventTypeEnum type) {
        return events.stream().anyMatch(event -> event instanceof StatusEvent status && status.type() == type);
    }

    private static void joinRecycleThreads() throws InterruptedException {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (RECYCLE_THREAD.equals(thread.getName())) {
                thread.join(TimeUnit.SECONDS.toMillis(30));
            }
        }
    }

    /**
     * Every start gets a new client. Creates complete at once with a new session, except the one made after
     * {@link #holdNextCreate} is set, which returns the test's held future.
     */
    private final class ScriptedSdk implements CopilotSdk {

        private volatile boolean holdNextCreate;
        /**
         * The session the last immediate create returned.
         */
        private volatile FakeSession lastSession;

        @Override
        public CopilotSdkClient start(CopilotLaunch launch) {
            return new ScriptedClient();
        }

        @Override
        public String label() {
            return "scripted";
        }

        private final class ScriptedClient implements CopilotSdkClient {

            @Override
            public CompletableFuture<List<String>> listSessionIds() {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<CopilotSdkSession> create(CopilotSessionSpec spec) {
                if (holdNextCreate) {
                    holdNextCreate = false;
                    heldCreateCalled.countDown();
                    return heldCreate;
                }
                FakeSession session = new FakeSession(spec.sessionId());
                lastSession = session;
                return CompletableFuture.completedFuture(session);
            }

            @Override
            public CompletableFuture<CopilotSdkSession> resume(String sessionId, CopilotSessionSpec spec) {
                return create(spec);
            }

            @Override
            public CompletableFuture<List<CopilotModel>> listModels() {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<Map<String, CopilotQuotaSnapshot>> quotaSnapshots() {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override
            public void close() {
            }
        }
    }

    /**
     * A session that counts its sends and remembers whether it was closed. A send is queued and never completes a turn,
     * so the turn it starts stays in flight.
     */
    private static final class FakeSession implements CopilotSdkSession {

        private final String sessionId;
        private final AtomicInteger sends = new AtomicInteger();
        private volatile boolean closed;
        /**
         * Thrown by {@link #subscribe} when set.
         */
        private volatile RuntimeException subscribeFailure;
        /**
         * Thrown by {@link #close} when set.
         */
        private volatile Error closeError;

        FakeSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public CompletableFuture<String> send(String prompt) {
            sends.incrementAndGet();
            return CompletableFuture.completedFuture("message");
        }

        @Override
        public CompletableFuture<String> sendImmediate(String prompt) {
            return CompletableFuture.completedFuture("message");
        }

        @Override
        public CompletableFuture<Void> abort() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CopilotCompaction> compact(String customInstructions) {
            return CompletableFuture.completedFuture(new CopilotCompaction(Boolean.TRUE, 0L, 0L));
        }

        @Override
        public void abortManualCompaction() {
        }

        @Override
        public AutoCloseable subscribe(CopilotEventSink sink) {
            RuntimeException failure = subscribeFailure;
            if (failure != null) {
                throw failure;
            }
            return () -> {
            };
        }

        @Override
        public void close() {
            closed = true;
            Error error = closeError;
            if (error != null) {
                closeError = null;
                throw error;
            }
        }
    }
}
