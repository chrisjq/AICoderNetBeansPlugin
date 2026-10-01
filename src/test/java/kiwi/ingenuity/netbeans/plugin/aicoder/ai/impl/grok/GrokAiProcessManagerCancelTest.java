package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Cancel/stop closer contract around {@link GrokAiProcessManager#runTurn}, driven through the
 * {@link GrokAiProcessManager#startProcess} test seam instead of a real grok CLI process. Before this seam
 * existed, the fix that gated {@code interrupt(Cancel)} on {@code processing} and moved STOPPED into
 * runTurn's own teardown (mirroring Ollama's fix) shipped with zero tests — this locks in the same
 * exactly-one-closer contract for every path a real process can take through that teardown: mid-turn cancel,
 * idle cancel, a cancel that lands before the process is even assigned, a cancel racing a normal exit, a full
 * stop() (session gone — never a closer), a reprompt during the cancelled turn's wind-down, and the
 * unrelated EXITED/TurnComplete happy paths that must stay exactly as they were.
 */
@Timeout(10)
class GrokAiProcessManagerCancelTest {

    @TempDir
    Path tempHome;

    @TempDir
    File workDir;

    private String originalUserHome;

    /**
     * {@code GrokUsageSignalsReader} and {@code sessionExists} read {@code user.home} directly; without this
     * redirect every test would scan the real {@code ~/.grok/sessions} on whatever machine runs the suite.
     */
    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    private static TestableGrokAiProcessManager newReadyManager(AiProcessEventListener listener) {
        TestableGrokAiProcessManager manager = new TestableGrokAiProcessManager(listener);
        manager.forceReady("sid", "grok-4");
        return manager;
    }

    private static void awaitActiveProcess(TestableGrokAiProcessManager manager) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!manager.hasActiveProcess() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(manager.hasActiveProcess(), "the turn never reached an active process within 5s");
    }

    @Test
    void cancelMidTurnEmitsExactlyOneStoppedAfterProcessingClears() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        List<Boolean> busyWhenStopped = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        TestableGrokAiProcessManager[] ref = {null};
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.STOPPED) {
                busyWhenStopped.add(ref[0].isBusy());
                stopped.countDown();
            }
        };
        TestableGrokAiProcessManager manager = newReadyManager(listener);
        ref[0] = manager;

        FakeProcess fp = new FakeProcess();
        manager.queueProcess(fp);

        manager.sendPrompt("hello", workDir, List.of());
        awaitActiveProcess(manager);

        manager.interrupt(InterruptTypeEnum.Cancel);
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "the STOPPED closer never arrived within 5s");

        assertEquals(1, events.stream().filter(e -> e instanceof StatusEvent se
                                                    && se.type() == StatusEventTypeEnum.STOPPED).count(),
                "exactly one STOPPED for a mid-turn cancel");
        assertTrue(events.stream().noneMatch(e -> e instanceof TurnCompleteEvent),
                "a cancelled turn must never also complete");
        assertEquals(List.of(false), busyWhenStopped,
                "isBusy() must already be false the instant STOPPED is observed");
    }

    @Test
    void cancelWhenIdleEmitsNothing() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        TestableGrokAiProcessManager manager = newReadyManager(events::add);

        assertFalse(manager.isProcessing(), "test setup: no turn must be in flight");
        manager.interrupt(InterruptTypeEnum.Cancel);

        assertTrue(events.isEmpty(), "a Cancel with no turn in flight must do nothing and emit nothing");
        assertEquals(0, manager.startProcessInvocations, "no process may ever be spawned for an idle Cancel");
    }

    /**
     * The narrowest race: Cancel arrives in the window after {@code sendPrompt} sets {@code processing} but
     * before the turn thread has assigned {@code currentProcess} — so {@code interrupt()} has nothing to
     * kill yet. The process is left to start and run to completion, but cancelledByUser is already set, so
     * runTurn's own {@code shouldReport} check still discards the result and reports exactly one STOPPED —
     * the UI is never left locked forever even though the kill itself was a no-op.
     */
    @Test
    void cancelBeforeProcessStartedStillClosesWithExactlyOneStopped() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.STOPPED) {
                stopped.countDown();
            }
        };
        TestableGrokAiProcessManager manager = newReadyManager(listener);

        FakeProcess fp = new FakeProcess();
        fp.writeStdout("{\"result\":\"too late\"}");
        fp.closeStdout();
        fp.completeExit(0);
        manager.queueProcess(fp);

        CountDownLatch gate = new CountDownLatch(1);
        manager.startProcessGate = gate;

        manager.sendPrompt("hello", workDir, List.of());
        manager.awaitStartProcessCalled();
        assertFalse(manager.hasActiveProcess(), "test setup: currentProcess must not be assigned yet");

        manager.interrupt(InterruptTypeEnum.Cancel);
        gate.countDown();
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "the STOPPED closer never arrived within 5s");

        assertEquals(1, events.stream().filter(e -> e instanceof StatusEvent se
                                                    && se.type() == StatusEventTypeEnum.STOPPED).count(),
                "exactly one STOPPED even though the process started after the cancel");
        assertTrue(events.stream().noneMatch(e -> e instanceof TurnCompleteEvent),
                "the discarded result must never surface as a completion");
    }

    /**
     * The other side of the same race: the process finishes normally and {@code processing} clears before
     * any Cancel is ever issued. A Cancel arriving after that must be the no-op every other idle Cancel is —
     * never a second closer alongside the TurnComplete that already fired.
     */
    @Test
    void cancelRacingNormalCompletionAfterProcessingClearsIsANoOp() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch turnComplete = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                turnComplete.countDown();
            }
        };
        TestableGrokAiProcessManager manager = newReadyManager(listener);

        FakeProcess fp = new FakeProcess();
        fp.writeStdout("{\"result\":\"hi\"}");
        fp.closeStdout();
        fp.completeExit(0);
        manager.queueProcess(fp);

        manager.sendPrompt("hello", workDir, List.of());
        assertTrue(turnComplete.await(5, TimeUnit.SECONDS), "test setup: the turn must have already completed");
        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "test setup: the turn must have already completed");

        events.clear();
        manager.interrupt(InterruptTypeEnum.Cancel);

        assertTrue(events.isEmpty(), "a Cancel that lands after the turn already closed must emit nothing");
    }

    /**
     * A full session stop() also sets cancelledByUser while tearing everything down, but the session it
     * would be closing no longer exists by the time the unwinding turn thread wakes up — it must never emit
     * a closer for a session that is already gone.
     */
    @Test
    void stopMidTurnEmitsNoStoppedEvent() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        TestableGrokAiProcessManager manager = newReadyManager(events::add);

        FakeProcess fp = new FakeProcess();
        manager.queueProcess(fp);

        manager.sendPrompt("hello", workDir, List.of());
        awaitActiveProcess(manager);

        manager.stop();
        assertFalse(manager.isProcessing(), "stop() clears processing itself, synchronously");

        long deadline = System.currentTimeMillis() + 500L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(events.isEmpty(), "a full stop() must not emit anything for the turn it tore down");
    }

    /**
     * Luna's review finding: interrupt() (and stop()) can be reached directly on the EDT, and the kill used
     * to block the caller for up to 5s waiting for the process to die before escalating to a forced kill —
     * exactly the "nothing long-blocking on the EDT" rule. destroy() must now return immediately regardless
     * of whether the process responds, with the wait + destroyForcibly escalation moved to a background
     * thread — the fake process here ignores destroy() outright, so the only way it can ever die is that
     * escalation actually running.
     */
    @Test
    void interruptReturnsPromptlyEvenWhenTheProcessIgnoresDestroy() throws Exception {
        TestableGrokAiProcessManager manager = newReadyManager(event -> {
        });

        FakeProcess fp = new FakeProcess();
        fp.ignoreDestroy = true;
        manager.queueProcess(fp);

        manager.sendPrompt("hello", workDir, List.of());
        awaitActiveProcess(manager);

        long startNanos = System.nanoTime();
        manager.interrupt(InterruptTypeEnum.Cancel);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(elapsedMillis < 1000L,
                "interrupt() must not block waiting for a process that ignores destroy(); took " + elapsedMillis + "ms");
        assertTrue(fp.awaitDestroyForciblyCalled(7, TimeUnit.SECONDS),
                "the 5s wait-then-escalate must still happen, just on a background thread instead of the caller's");
    }

    /**
     * Characterisation: an ordinary non-zero exit with no cancellation involved must still take the EXITED
     * path exactly as before — the cancelledByUser/running gating added around STOPPED must never engage for
     * this case.
     *
     * <p>
     * Waits for the EXITED event itself (a latch counted down from inside the listener) rather than the
     * {@code awaitIdle}-then-inspect-events pattern: runTurn's EXITED branch clears {@code processing}
     * inside one synchronized block and only then emits the event in a separate statement right after, so a
     * poll-based wait on {@code isProcessing()} can — rarely, but reproducibly under load — observe "idle"
     * a hair before the event that made it idle has actually been appended to an observer's list. That is a
     * real ordering gap, but only a polling TEST can ever see it: every real listener (and the second
     * review's identical conclusion for the analogous STOPPED-ordering question) reacts to the event's
     * arrival directly, never by polling a flag and separately checking for the event after.
     */
    @Test
    void nonCancelledNonZeroExitStillReportsExitedNotStopped() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch exited = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.EXITED) {
                exited.countDown();
            }
        };
        TestableGrokAiProcessManager manager = newReadyManager(listener);

        FakeProcess fp = new FakeProcess();
        fp.closeStdout();
        fp.completeExit(1);
        manager.queueProcess(fp);

        manager.sendPrompt("hello", workDir, List.of());
        assertTrue(exited.await(5, TimeUnit.SECONDS), "the EXITED closer never arrived within 5s");

        assertEquals(1, events.stream().filter(e -> e instanceof StatusEvent se
                                                    && se.type() == StatusEventTypeEnum.EXITED).count());
        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent se
                                                  && se.type() == StatusEventTypeEnum.STOPPED));
        assertTrue(events.stream().noneMatch(e -> e instanceof TurnCompleteEvent));
    }

    /**
     * Characterisation: the ordinary happy path must produce exactly one TurnComplete and no STOPPED —
     * unaffected by the Cancel/stop teardown changes above.
     */
    @Test
    void normalTurnCompletesWithExactlyOneTurnCompleteAndNoStopped() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch turnComplete = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof TurnCompleteEvent) {
                turnComplete.countDown();
            }
        };
        TestableGrokAiProcessManager manager = newReadyManager(listener);

        FakeProcess fp = new FakeProcess();
        fp.writeStdout("{\"result\":\"hello from grok\"}");
        fp.closeStdout();
        fp.completeExit(0);
        manager.queueProcess(fp);

        manager.sendPrompt("hello", workDir, List.of());
        assertTrue(turnComplete.await(5, TimeUnit.SECONDS), "the TurnComplete closer never arrived within 5s");

        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count());
        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent se
                                                  && se.type() == StatusEventTypeEnum.STOPPED));
    }

    /**
     * The resurrection window's Grok shape: a reprompt sent while the cancelled turn is still unwinding
     * (processing stays true until the owning thread's own teardown clears it) must be silently refused —
     * not start a second process underneath the first. Once the cancelled turn has genuinely finished, a
     * fresh send must work normally and complete on its own, untouched by the earlier cancel.
     */
    @Test
    void cancelThenRepromptIsRefusedWhileTheCancelledTurnIsStillUnwinding() throws Exception {
        List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch turnComplete = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            events.add(event);
            if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.STOPPED) {
                stopped.countDown();
            }
            else if (event instanceof TurnCompleteEvent) {
                turnComplete.countDown();
            }
        };
        TestableGrokAiProcessManager manager = newReadyManager(listener);

        FakeProcess fp1 = new FakeProcess();
        fp1.autoCompleteOnDestroy = false;
        manager.queueProcess(fp1);

        manager.sendPrompt("one", workDir, List.of());
        awaitActiveProcess(manager);

        manager.interrupt(InterruptTypeEnum.Cancel);
        fp1.awaitWaitForEntered();

        assertTrue(manager.isProcessing(), "test setup: the cancelled turn must still be unwinding");
        manager.sendPrompt("two", workDir, List.of());
        assertEquals(1, manager.startProcessInvocations,
                "a reprompt during the wind-down must not start a second process");

        fp1.completeExit(130);
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "the STOPPED closer never arrived within 5s");

        assertEquals(1, events.stream().filter(e -> e instanceof StatusEvent se
                                                    && se.type() == StatusEventTypeEnum.STOPPED).count());
        assertTrue(events.stream().noneMatch(e -> e instanceof TurnCompleteEvent),
                "neither the cancelled turn nor the refused reprompt may complete");

        events.clear();
        FakeProcess fp2 = new FakeProcess();
        fp2.writeStdout("{\"result\":\"hi\"}");
        fp2.closeStdout();
        fp2.completeExit(0);
        manager.queueProcess(fp2);

        manager.sendPrompt("three", workDir, List.of());
        assertTrue(turnComplete.await(5, TimeUnit.SECONDS), "the TurnComplete closer never arrived within 5s");

        assertEquals(1, events.stream().filter(e -> e instanceof TurnCompleteEvent).count(),
                "a genuinely new send must complete on its own, unaffected by the earlier cancel");
        assertEquals(2, manager.startProcessInvocations);
    }

    /**
     * A controllable stand-in for the real grok CLI process, piping stdout/stderr through
     * {@link PipedInputStream}/{@link PipedOutputStream} so a test can feed output and control exit timing
     * exactly like driving a real process's lifecycle from the outside.
     */
    private static final class FakeProcess extends Process {

        private final PipedOutputStream stdoutSink = new PipedOutputStream();
        private final PipedInputStream stdoutSource;
        private final CountDownLatch exited = new CountDownLatch(1);
        private final CountDownLatch waitForEntered = new CountDownLatch(1);
        private volatile int exitCode;
        /**
         * When true (the default), {@code destroy()} immediately completes the exit — fast and simple for
         * every test that doesn't care about the gap between "killed" and "reaped". The one test that does
         * (a reprompt racing the cancelled turn's wind-down) sets this false and calls {@link #completeExit}
         * itself once it has observed what it needs to.
         */
        volatile boolean autoCompleteOnDestroy = true;
        /**
         * When true, {@code destroy()} does nothing observable — simulating a process that ignores
         * SIGTERM — so only {@code destroyForcibly()} (the 5s escalation) can ever kill it.
         */
        volatile boolean ignoreDestroy;
        private final CountDownLatch destroyForciblyCalled = new CountDownLatch(1);

        FakeProcess() throws IOException {
            stdoutSource = new PipedInputStream(stdoutSink, 4096);
            PipedOutputStream stderrSink = new PipedOutputStream();
            new PipedInputStream(stderrSink, 1);
            // Empty stderr, closed immediately: GrokAiProcessManager's stderr-reader thread must reach EOF
            // right away, or every single test would stall for its 2s join() timeout.
            stderrSink.close();
        }

        void writeStdout(String s) throws IOException {
            stdoutSink.write(s.getBytes(StandardCharsets.UTF_8));
            stdoutSink.flush();
        }

        void closeStdout() throws IOException {
            stdoutSink.close();
        }

        /**
         * Marks the process as having exited with the given code, releasing any thread blocked in waitFor().
         */
        void completeExit(int code) {
            exitCode = code;
            exited.countDown();
        }

        void awaitWaitForEntered() throws InterruptedException {
            waitForEntered.await();
        }

        boolean awaitDestroyForciblyCalled(long timeout, TimeUnit unit) throws InterruptedException {
            return destroyForciblyCalled.await(timeout, unit);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdoutSource;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            waitForEntered.countDown();
            exited.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waitForEntered.countDown();
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (exited.getCount() > 0) {
                throw new IllegalThreadStateException("process hasn't exited");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            if (ignoreDestroy) {
                return;
            }
            try {
                stdoutSink.close();
            }
            catch (IOException ignored) {
                // already closed
            }
            if (autoCompleteOnDestroy) {
                completeExit(130);
            }
        }

        @Override
        public Process destroyForcibly() {
            // Bypasses ignoreDestroy entirely — a forced kill always succeeds, real or fake.
            destroyForciblyCalled.countDown();
            try {
                stdoutSink.close();
            }
            catch (IOException ignored) {
                // already closed
            }
            completeExit(137);
            return this;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() > 0;
        }
    }

    /**
     * Routes process creation through a queue of {@link FakeProcess} instances instead of a real grok CLI,
     * and bypasses {@code start()} (which would spin up a real MCP server) by setting the fields it would
     * otherwise have set directly — {@code start()} itself is out of scope here (Cancel/stop only).
     */
    private static final class TestableGrokAiProcessManager extends GrokAiProcessManager {

        private final List<FakeProcess> pendingProcesses = new CopyOnWriteArrayList<>();
        private final CountDownLatch startProcessCalled = new CountDownLatch(1);
        volatile CountDownLatch startProcessGate;
        volatile int startProcessInvocations;

        TestableGrokAiProcessManager(AiProcessEventListener listener) {
            super(listener);
        }

        void forceReady(String sessionId, String model) {
            this.sessionId = sessionId;
            this.model = model;
            this.executablePath = "/fake/grok";
            this.running = true;
        }

        void queueProcess(FakeProcess p) {
            pendingProcesses.add(p);
        }

        boolean hasActiveProcess() {
            return currentProcess != null;
        }

        void awaitStartProcessCalled() throws InterruptedException {
            startProcessCalled.await();
        }

        @Override
        Process startProcess(ProcessBuilder pb) throws IOException {
            startProcessInvocations++;
            startProcessCalled.countDown();
            CountDownLatch gate = startProcessGate;
            if (gate != null) {
                try {
                    gate.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (pendingProcesses.isEmpty()) {
                throw new IOException("test forgot to queue a FakeProcess");
            }
            return pendingProcesses.remove(0);
        }
    }
}
