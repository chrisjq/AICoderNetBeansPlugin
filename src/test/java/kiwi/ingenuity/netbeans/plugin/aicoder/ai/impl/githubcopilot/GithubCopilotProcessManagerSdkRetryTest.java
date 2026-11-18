package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSessionSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
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
 * Drives {@code start()} end to end with fake SDKs: the SDK is selected on every start, a failure is retried once only
 * when the CLI file changed and a fresh probe selects a different SDK, the first client is closed before the second
 * starts, and every failure — a LinkageError included — reaches FAILED and drops the MCP registrar.
 */
class GithubCopilotProcessManagerSdkRetryTest {

    private final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
    /**
     * Every start, create and close, in order, across all fake SDKs and clients.
     */
    private final List<String> log = new CopyOnWriteArrayList<>();
    private GithubCopilotProcessManager manager;
    private String executable;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        McpServerRegistry.portOverride = 0;
        // start() checks that the CLI exists and is executable; the fake SDKs never run it.
        Path script = dir.resolve("copilot");
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        assumeTrue(script.toFile().setExecutable(true), "needs a filesystem with an executable bit");
        executable = script.toString();
        manager = new GithubCopilotProcessManager(events::add);
        manager.setCurrentSession(new AiSession("gh-sdk-retry", "Test", null, AiTypeEnum.GitHubCoPilot, null,
                                                new GithubCopilotSessionSettings(), Instant.now(), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    @Timeout(60)
    void aFailedStartOnAChangedCliIsRetriedOnceWithTheNewlySelectedSdkAfterClosingTheFirstClient() {
        FakeSelection selection = new FakeSelection(new SdkA(log, true), new SdkB(log, false))
                .withStamps("old-cli", "new-cli");
        manager.setSdkSelectionForTests(selection);

        manager.start(executable, "gpt-a");

        assertEquals(List.of("A:start", "A:create", "A:close", "B:start", "B:create"), log,
                     "the first client must be closed before the second SDK starts");
        assertEquals(1, selection.invalidations, "the changed CLI is re-probed exactly once");
        assertEquals(2, selection.calls);
        assertTrue(manager.isRunning(), "the retry with the new SDK must bring the session up");
        assertTrue(hasStatus(StatusEventTypeEnum.READY), () -> "events were: " + events);
        assertFalse(hasStatus(StatusEventTypeEnum.FAILED), () -> "a successful retry reports no failure: " + events);
    }

    @Test
    @Timeout(60)
    void aFailureOnAChangedCliIsNotRetriedWhenTheFreshProbeSelectsTheSameSdk() {
        FakeSelection selection = new FakeSelection(new SdkA(log, true), new SdkA(log, true))
                .withStamps("old-cli", "new-cli");
        manager.setSdkSelectionForTests(selection);

        manager.start(executable, "gpt-a");

        assertEquals(List.of("A:start", "A:create", "A:close"), log, "same SDK class: no second attempt");
        assertEquals(1, selection.invalidations, "the changed CLI is still re-probed, so the cache is fresh next time");
        assertFalse(manager.isRunning());
        assertTrue(hasStatus(StatusEventTypeEnum.FAILED), () -> "events were: " + events);
        assertFalse(manager.isMcpActive(), "a failed start must drop the MCP registrar");
    }

    @Test
    @Timeout(60)
    void aFailureOnAnUnchangedCliIsNeitherReprobedNorRetried() {
        FakeSelection selection = new FakeSelection(new SdkA(log, true), new SdkB(log, false))
                .withStamps("same-cli");
        manager.setSdkSelectionForTests(selection);

        manager.start(executable, "gpt-a");

        assertEquals(List.of("A:start", "A:create", "A:close"), log, "an unchanged CLI gets no second attempt");
        assertEquals(0, selection.invalidations, "the cached probe still describes this CLI file");
        assertEquals(1, selection.calls, "no second probe for an unchanged CLI");
        assertFalse(manager.isRunning());
        assertTrue(hasStatus(StatusEventTypeEnum.FAILED), () -> "events were: " + events);
    }

    @Test
    @Timeout(60)
    void aFailureOnACliWithoutAStampIsReprobed() {
        FakeSelection selection = new FakeSelection(new SdkA(log, true), new SdkB(log, false))
                .withStamps((String) null);
        manager.setSdkSelectionForTests(selection);

        manager.start(executable, "gpt-a");

        assertEquals(List.of("A:start", "A:create", "A:close", "B:start", "B:create"), log,
                     "a CLI whose file cannot be stamped is always probed again");
        assertEquals(1, selection.invalidations);
        assertTrue(manager.isRunning());
    }

    @Test
    @Timeout(60)
    void aFailedFirstSelectionIsFinal() {
        FakeSelection selection = new FakeSelection();
        manager.setSdkSelectionForTests(selection);

        manager.start(executable, "gpt-a");

        assertEquals(List.of(), log, "no SDK was started");
        assertEquals(0, selection.invalidations, "a probe failure is not retried");
        assertTrue(hasStatus(StatusEventTypeEnum.FAILED), () -> "events were: " + events);
        assertFalse(manager.isMcpActive());
    }

    @Test
    @Timeout(60)
    void aLinkageErrorReachesFailedAndDropsTheRegistrar() {
        manager.setSdkForTests(new CopilotSdk() {
            @Override
            public CopilotSdkClient start(CopilotLaunch launch) {
                throw new NoClassDefFoundError("com/microsoft/copilot/CopilotClient");
            }

            @Override
            public String label() {
                return "broken";
            }
        });

        manager.start(executable, "gpt-a");

        assertTrue(hasStatus(StatusEventTypeEnum.FAILED), () -> "a LinkageError must still reach FAILED: " + events);
        assertFalse(manager.isRunning());
        assertFalse(manager.isMcpActive(), "the MCP registrar must be dropped after a LinkageError");
    }

    @Test
    @Timeout(60)
    void aPinnedSdkIsNeverReselectedOrRetried() {
        FakeSelection selection = new FakeSelection(new SdkB(log, false));
        manager.setSdkSelectionForTests(selection);
        manager.setSdkForTests(new SdkA(log, true));

        manager.start(executable, "gpt-a");

        assertEquals(List.of("A:start", "A:create", "A:close"), log);
        assertEquals(0, selection.calls, "a pinned SDK bypasses selection entirely");
        assertEquals(0, selection.invalidations);
        assertTrue(hasStatus(StatusEventTypeEnum.FAILED), () -> "events were: " + events);
    }

    @Test
    @Timeout(60)
    void thePinnedSdkSurvivesTheStopThatStartBeginsWith() {
        manager.setSdkForTests(new SdkB(log, false));

        manager.start(executable, "gpt-a");
        manager.start(executable, "gpt-a");

        assertEquals(2, log.stream().filter("B:start"::equals).count(),
                     () -> "both starts must use the pinned SDK: " + log);
        assertTrue(manager.isRunning());
    }

    private boolean hasStatus(StatusEventTypeEnum type) {
        return events.stream().anyMatch(event -> event instanceof StatusEvent status && status.type() == type);
    }

    /**
     * Hands out the given SDKs in order, one per {@code sdkFor}; with none left it fails like a probe failure. Stamps
     * are handed out in order too, one per {@code stampFor}, the last repeating; by default the CLI never changes.
     */
    private static final class FakeSelection implements GithubCopilotProcessManager.SdkSelection {

        private final Deque<CopilotSdk> answers;
        private List<String> stamps = List.of("unchanged-cli");
        private int stampCalls;
        private int calls;
        private int invalidations;

        FakeSelection(CopilotSdk... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        FakeSelection withStamps(String... stamps) {
            this.stamps = Arrays.asList(stamps);
            return this;
        }

        @Override
        public String stampFor(String executablePath) {
            String stamp = stamps.get(Math.min(stampCalls, stamps.size() - 1));
            stampCalls++;
            return stamp;
        }

        @Override
        public CopilotSdk sdkFor(String executablePath) throws CopilotSdkException {
            calls++;
            CopilotSdk next = answers.poll();
            if (next == null) {
                throw new CopilotSdkException("Could not determine Copilot CLI protocol");
            }
            return next;
        }

        @Override
        public void invalidate(String executablePath) {
            invalidations++;
        }
    }

    /**
     * A fake SDK whose client either fails every create (as a protocol mismatch would) or opens a session.
     */
    private abstract static class FakeSdk implements CopilotSdk {

        private final String name;
        private final List<String> log;
        private final boolean createFails;

        FakeSdk(String name, List<String> log, boolean createFails) {
            this.name = name;
            this.log = log;
            this.createFails = createFails;
        }

        @Override
        public CopilotSdkClient start(CopilotLaunch launch) {
            log.add(name + ":start");
            return new FakeClient(name, log, createFails);
        }

        @Override
        public String label() {
            return name;
        }
    }

    private static final class SdkA extends FakeSdk {

        SdkA(List<String> log, boolean createFails) {
            super("A", log, createFails);
        }
    }

    private static final class SdkB extends FakeSdk {

        SdkB(List<String> log, boolean createFails) {
            super("B", log, createFails);
        }
    }

    private static final class FakeClient implements CopilotSdkClient {

        private final String name;
        private final List<String> log;
        private final boolean createFails;

        FakeClient(String name, List<String> log, boolean createFails) {
            this.name = name;
            this.log = log;
            this.createFails = createFails;
        }

        @Override
        public CompletableFuture<List<String>> listSessionIds() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<CopilotSdkSession> create(CopilotSessionSpec spec) {
            log.add(name + ":create");
            if (createFails) {
                return CompletableFuture.failedFuture(new IllegalStateException("protocol version mismatch"));
            }
            return CompletableFuture.completedFuture(new FakeSession(spec.sessionId()));
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
            log.add(name + ":close");
        }
    }

    private static final class FakeSession implements CopilotSdkSession {

        private final String sessionId;
        private final List<CopilotEventSink> sinks = new ArrayList<>();

        FakeSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public CompletableFuture<String> send(String prompt) {
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
            sinks.add(sink);
            return () -> sinks.remove(sink);
        }

        @Override
        public void close() {
        }
    }
}
