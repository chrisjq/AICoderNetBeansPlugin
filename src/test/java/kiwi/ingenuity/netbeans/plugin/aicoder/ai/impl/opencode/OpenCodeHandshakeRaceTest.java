package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpMethodEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OpenCode handshake against a real fake agent process, with the first handshake held at the point just
 * before it publishes its connection — the window in which the agent can die, the user can press Stop, or the
 * session can be restarted. Holding it there makes each interleaving happen every run instead of only under
 * load.
 */
class OpenCodeHandshakeRaceTest {

    private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"));

    @BeforeEach
    void useEphemeralMcpPort() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
    }

    @AfterEach
    void releaseMcpPort() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    /**
     * The agent dies between answering {@code session/new} and the publish. The exit is reported first, so
     * the handshake must not then publish a connection to the dead process, nor add a FAILED to the EXITED.
     */
    @Test
    void agentExitsBeforePublish_deadConnectionIsClosedNotPublished() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        HeldHandshakeManager manager = startManager(fakeAgent("exit 7"), events);
        try {
            manager.sendPrompt("hi", WORK_DIR, List.of());
            manager.awaitHeld();
            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.EXITED) == 1,
                    "EXITED from the exit callback while the handshake is held");

            manager.releaseAndJoinHeld();

            assertNull(manager.connection, "a connection to the dead process must not be published");
            assertFalse(manager.isProcessing(), "the turn must not stay processing");
            assertTrue(manager.connections.get(0).isClosed(), "the unpublished connection must be closed");
            assertEquals(1, statusCount(events, StatusEventTypeEnum.EXITED));
            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED),
                    "EXITED already closed the turn; a FAILED as well would be a second closing status: " + events);
        }
        finally {
            manager.release.countDown();
            manager.stopAndCloseConnections();
        }
    }

    /**
     * Stop pressed while the first prompt's handshake is still running, the agent staying alive. STOPPED
     * closes the turn, so the handshake must not send the prompt anyway; the live connection is kept.
     */
    @Test
    void stopDuringHandshake_keepsTheConnection_butNeverSendsTheStoppedPrompt() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        HeldHandshakeManager manager = startManager(fakeAgent("sleep 60"), events);
        try {
            manager.sendPrompt("hi", WORK_DIR, List.of());
            manager.awaitHeld();

            manager.interrupt(InterruptTypeEnum.Cancel);
            manager.releaseAndJoinHeld();

            assertEquals(1, statusCount(events, StatusEventTypeEnum.STOPPED));
            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED), "STOPPED already closed the turn: " + events);
            // The fake never answers session/prompt, so a prompt sent as a turn would leave this true.
            assertFalse(manager.isProcessing(), "the stopped prompt must not have been sent as a turn");
            assertNotNull(manager.connection, "the live connection is kept for the next prompt");
        }
        finally {
            manager.release.countDown();
            manager.stopAndCloseConnections();
        }
    }

    /**
     * The session was stopped and restarted, and a new prompt is in flight, while the first prompt's
     * handshake was still running. That stale handshake must report nothing and must not clear the new turn's
     * processing flag.
     */
    @Test
    void staleHandshakeAfterRestart_reportsNothing_andLeavesTheNewTurnProcessing() throws Exception {
        File script = fakeAgent("sleep 60");
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        HeldHandshakeManager manager = startManager(script, events);
        try {
            manager.sendPrompt("first", WORK_DIR, List.of());
            manager.awaitHeld();

            manager.stop();
            manager.start(script.getAbsolutePath(), null);
            manager.sendPrompt("second", WORK_DIR, List.of());
            awaitTrue(() -> manager.threads.size() == 2, "the second prompt's handshake");
            manager.threads.get(1).join(10_000);
            assertTrue(manager.isProcessing(), "the second prompt is in flight");

            manager.releaseAndJoinHeld();

            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED),
                    "the first turn ended with the stop; a FAILED now would close the second turn: " + events);
            assertTrue(manager.isProcessing(), "the stale handshake must not clear the second turn's processing");
            assertTrue(manager.connections.get(0).isClosed(), "the stale connection must be closed");
            assertSame(manager.connections.get(1), manager.connection, "the second prompt's connection stays published");
        }
        finally {
            manager.release.countDown();
            manager.stopAndCloseConnections();
        }
    }

    /**
     * The agent crashes right after answering, and the handshake reaches its publish point after the process
     * is dead and its output ended but before the process's exit callback has run. That is a crash, not a
     * hang: it must be reported once as EXITED with the exit code, not as a FAILED "stopped responding". The
     * exit callback is held back so this ordering happens every run.
     */
    @Test
    void crashSeenByTheHandshakeBeforeItsExitCallback_isReportedOnceAsExited() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        HeldHandshakeManager manager = new HeldHandshakeManager(events::add);
        manager.exitCallbackGate = new CountDownLatch(1);
        manager.setCurrentSession(new AiSession("s1", "Test", null, AiTypeEnum.OPENCODE, null,
                new OpenCodeSessionSettings(), Instant.now(), Instant.now()));
        manager.start(fakeAgent("exit 7").getAbsolutePath(), null);
        try {
            manager.sendPrompt("hi", WORK_DIR, List.of());
            manager.awaitHeld();
            awaitTrue(() -> manager.connections.get(0).isStreamEnded()
                            && manager.process() != null && !manager.process().isAlive(),
                    "the agent to have died, its exit not yet handled");

            manager.releaseAndJoinHeld();

            assertEquals(1, statusCount(events, StatusEventTypeEnum.EXITED), "the crash is reported: " + events);
            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                     && se.type() == StatusEventTypeEnum.EXITED && se.text().contains("7")),
                    "with its exit code: " + events);
            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED), "and only once: " + events);
            assertNull(manager.connection, "no connection to the dead agent is published");
            assertTrue(manager.connections.get(0).isClosed(), "and its connection is closed");
            assertFalse(manager.isProcessing());
        }
        finally {
            manager.exitCallbackGate.countDown();
            manager.release.countDown();
            manager.stopAndCloseConnections();
        }
    }

    /**
     * The agent's output ends after it answers, before the publish, while the process stays alive — so there
     * is no exit to report anything. The handshake must not publish a connection nothing can be read from; it
     * must close it, kill and detach from the process, and report exactly one FAILED.
     */
    @Test
    void outputEndsBeforePublish_processAlive_failsOnceAndPublishesNothing() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        // Sleeps long enough to outlast the test: an early exit would clean up after a wrongly published
        // connection and let the test pass without the fix.
        HeldHandshakeManager manager = startManager(fakeAgent("exec 1>&-\nsleep 60"), events);
        try {
            manager.sendPrompt("hi", WORK_DIR, List.of());
            manager.awaitHeld();
            awaitTrue(() -> manager.connections.get(0).isStreamEnded(), "end of the agent's output while held");

            manager.releaseAndJoinHeld();

            assertNull(manager.connection, "a connection nothing can be read from must not be published");
            assertFalse(manager.isProcessing(), "the turn must not stay processing");
            assertTrue(manager.connections.get(0).isClosed(), "the unpublished connection must be closed");
            assertNull(manager.process(), "detached from the killed process, so its exit is stale and adds no EXITED");
            assertEquals(1, statusCount(events, StatusEventTypeEnum.FAILED), "exactly one closing status: " + events);
            assertEquals(0, statusCount(events, StatusEventTypeEnum.EXITED), events.toString());
        }
        finally {
            manager.release.countDown();
            manager.stopAndCloseConnections();
        }
    }

    /**
     * The agent crashes after start-up, mid-turn. The connection used to stay published, so every retry the
     * user made was written to the dead process and the session stayed broken until its tab was closed. Now
     * the exit drops the connection and remembers the ACP session, so the next prompt starts a new agent and
     * resumes the conversation.
     */
    @Test
    void agentCrashesMidTurn_nextPromptReconnectsAndResumesTheSession() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        HeldHandshakeManager manager = startManager(fakeAgent("read -r _prompt\nexit 7"), events);
        manager.release.countDown(); // nothing to hold here
        try {
            manager.sendPrompt("first", WORK_DIR, List.of());
            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.EXITED) == 1, "EXITED when the agent crashes");

            assertNull(manager.connection, "the dead connection must be dropped");
            assertTrue(manager.connections.get(0).isClosed(), "and closed, or its executor threads leak");
            assertEquals("ses_fake", manager.pendingAcpResumeId, "the conversation is kept to resume");
            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED),
                    "EXITED closed the turn; closing the connection fails its prompt, which must stay silent: " + events);

            manager.sendPrompt("retry", WORK_DIR, List.of());
            awaitTrue(() -> manager.threads.size() == 2, "the retry to start a new agent instead of using the dead one");
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    /**
     * The agent's output ends mid-turn while the process keeps running, so no exit is ever reported. Only the
     * reader's disconnect knows. The turn used to wait forever for a reply that could not come, with the dead
     * connection kept for every later prompt. Now the disconnect drops and closes it — failing the turn once
     * — and the next prompt starts a new agent.
     */
    @Test
    void agentOutputEndsMidTurn_processAlive_turnFailsOnceAndNextPromptReconnects() throws Exception {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        HeldHandshakeManager manager = startManager(fakeAgent("read -r _prompt\nexec 1>&-\nsleep 60"), events);
        manager.release.countDown(); // nothing to hold here
        try {
            manager.sendPrompt("first", WORK_DIR, List.of());
            awaitTrue(() -> statusCount(events, StatusEventTypeEnum.FAILED) == 1, "the in-flight turn to fail");

            assertNull(manager.connection, "the dead connection must be dropped");
            assertFalse(manager.isProcessing());
            assertNull(manager.process(), "the hung agent is killed and detached, so its exit adds no EXITED");
            assertEquals(0, statusCount(events, StatusEventTypeEnum.EXITED), "the process never exited: " + events);
            assertEquals("ses_fake", manager.pendingAcpResumeId, "the conversation is kept to resume");

            manager.sendPrompt("retry", WORK_DIR, List.of());
            awaitTrue(() -> manager.threads.size() == 2, "the retry to start a new agent instead of using the dead one");
        }
        finally {
            manager.stopAndCloseConnections();
        }
    }

    /**
     * A turn's response that arrives after the session has moved on to a newer turn — for example the failure
     * of a prompt whose agent crashed, landing after the user's retry has started. It must not clear the
     * newer turn's processing or report a FAILED that the UI would take as the newer turn's closer.
     */
    @Test
    void staleTurnResponseAfterANewerTurnStarted_isIgnored() {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add);
        StubConnection conn = new StubConnection();
        manager.connection = conn;
        manager.acpSessionId = "ses_fake";
        try {
            manager.sendTurn("first");
            manager.sendTurn("second"); // a newer turn now owns the session

            conn.requests.get(0).completeExceptionally(new RuntimeException("AcpConnection closed"));

            assertEquals(0, statusCount(events, StatusEventTypeEnum.FAILED), "the stale failure must be ignored: " + events);
            assertTrue(manager.isProcessing(), "the newer turn must stay processing");

            conn.requests.get(1).complete(new JsonObject());
            assertFalse(manager.isProcessing(), "the newer turn's own response still ends it");
        }
        finally {
            conn.close();
        }
    }

    /**
     * Captures each request's future instead of writing it, so a test decides when and how each response
     * arrives.
     */
    private static final class StubConnection extends AcpConnection {

        final List<CompletableFuture<JsonObject>> requests
                                                  = new CopyOnWriteArrayList<>();

        StubConnection() {
            super(new ByteArrayOutputStream(), InputStream.nullInputStream(),
                    new OpenCodeAcpClientHandler(e -> {
                    }, () -> {
                    }));
        }

        @Override
        public CompletableFuture<JsonObject> sendRequest(
                AcpMethodEnum method, JsonObject params) {
            CompletableFuture<JsonObject> future
                                          = new CompletableFuture<>();
            requests.add(future);
            return future;
        }
    }

    private static HeldHandshakeManager startManager(File script, List<AiProcessEvent> events) {
        HeldHandshakeManager manager = new HeldHandshakeManager(events::add);
        manager.setCurrentSession(new AiSession("s1", "Test", null, AiTypeEnum.OPENCODE, null,
                new OpenCodeSessionSettings(), Instant.now(), Instant.now()));
        manager.start(script.getAbsolutePath(), null);
        assertTrue(manager.isRunning(), "the fake agent session must start: " + events);
        return manager;
    }

    /**
     * A fake {@code opencode acp}: answers {@code initialize} (id 1) and {@code session/new} or
     * {@code session/resume} (id 2) the way the real agent does, then runs {@code after}.
     */
    private static File fakeAgent(String after) throws IOException {
        File script = File.createTempFile("fake-opencode-", ".sh");
        script.deleteOnExit();
        String body = "#!/bin/sh\n"
                      + "read -r _init\n"
                      + "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}\\n'\n"
                      + "read -r _session\n"
                      + "printf '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"ses_fake\"}}\\n'\n"
                      + after + "\n";
        Files.writeString(script.toPath(), body, StandardCharsets.UTF_8);
        script.setExecutable(true);
        return script;
    }

    private static long statusCount(List<AiProcessEvent> events, StatusEventTypeEnum type) {
        return events.stream().filter(e -> e instanceof StatusEvent se && se.type() == type).count();
    }

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    /**
     * Holds the FIRST handshake at the publish point until released; later handshakes run straight through.
     * Skips the version probe, so no real opencode binary or shared database is touched.
     */
    private static final class HeldHandshakeManager extends OpenCodeAiProcessManager {

        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<AcpConnection> connections = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        HeldHandshakeManager(AiProcessEventListener listener) {
            super(listener);
        }

        @Override
        protected String probeOpenCodeVersion() {
            return null;
        }

        @Override
        protected void beforeHandshakePublish(AcpConnection conn) {
            connections.add(conn);
            threads.add(Thread.currentThread());
            if (threads.size() == 1) {
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * When set, the process's own onExit callback waits for it, so a test can have the handshake see a
         * crash before that callback has handled it. Calls made from a handshake thread are never held.
         */
        volatile CountDownLatch exitCallbackGate;

        @Override
        void handleProcessExit(Process dead) {
            CountDownLatch gate = exitCallbackGate;
            if (gate != null && !threads.contains(Thread.currentThread())) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.handleProcessExit(dead);
        }

        Process process() {
            synchronized (this) {
                return currentProcess;
            }
        }

        /**
         * stop() closes a published connection only after waiting, on a background thread, for the agent to
         * answer session/close — which these fakes never do. Closing every connection here (close is
         * idempotent) keeps their acp-notify/acp-dispatch threads from outliving the test and failing
         * AcpConnectionTest's JVM-wide thread check.
         */
        void stopAndCloseConnections() {
            stop();
            connections.forEach(AcpConnection::close);
        }

        void awaitHeld() throws InterruptedException {
            assertTrue(held.await(10, TimeUnit.SECONDS), "the handshake must reach the publish point");
        }

        void releaseAndJoinHeld() throws InterruptedException {
            release.countDown();
            threads.get(0).join(10_000);
            assertFalse(threads.get(0).isAlive(), "the held handshake thread must finish");
        }
    }
}
