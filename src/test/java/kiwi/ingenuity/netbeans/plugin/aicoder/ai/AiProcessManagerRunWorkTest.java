package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins the UI contract {@link AiProcessManager#runWork} guarantees every backend: exactly one BUSY when
 * non-turn work starts, and exactly one closing status on every path — so a session is never left locked and
 * never unlocked twice.
 */
class AiProcessManagerRunWorkTest {

    private static final class Manager extends AiProcessManager {

        Manager(List<AiProcessEvent> events) {
            super(events::add);
        }

        boolean exit(String reason) {
            return failWorkInFlight(reason);
        }

        @Override
        public void start(String executableOrConfig, String modelOrConfig) {
        }

        @Override
        public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        }

        @Override
        public void interrupt(InterruptTypeEnum type) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void resumeSession(String existingSessionId) {
        }

        @Override
        public boolean isMcpActive() {
            return false;
        }
    }

    private final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
    private final Manager manager = new Manager(events);

    private static StatusEvent ready(Object result) {
        return new StatusEvent(StatusEventTypeEnum.READY, "done: " + result);
    }

    private static StatusEvent failed(Throwable t) {
        return new StatusEvent(StatusEventTypeEnum.FAILED, "failed: " + t.getMessage());
    }

    private List<StatusEvent> statuses() {
        return events.stream().map(StatusEvent.class::cast).toList();
    }

    @Test
    void successReportsBusyThenOneReady() {
        CompletableFuture<String> work = new CompletableFuture<>();
        assertTrue(manager.runWork("Compacting…", false, 5_000, () -> work, AiProcessManagerRunWorkTest::ready,
                AiProcessManagerRunWorkTest::failed));

        assertEquals(1, events.size(), "BUSY must be reported the moment the work starts");
        StatusEvent busy = statuses().get(0);
        assertEquals(StatusEventTypeEnum.BUSY, busy.type());
        assertEquals("Compacting…", busy.text());
        assertFalse(busy.cancellable(), "the cancellable flag must reach the UI as given");
        assertTrue(manager.isWorkInFlight());
        assertTrue(manager.isBusy(), "isBusy() must cover non-turn work, not only turns");

        work.complete("ok");

        assertEquals(2, events.size());
        assertEquals(StatusEventTypeEnum.READY, statuses().get(1).type());
        assertEquals("done: ok", statuses().get(1).text());
        assertFalse(manager.isWorkInFlight());
        assertFalse(manager.isBusy());
    }

    @Test
    void failureReportsOneClosingStatusFromTheUnwrappedCause() {
        CompletableFuture<String> work = new CompletableFuture<>();
        manager.runWork("Compacting…", false, 5_000, () -> work, AiProcessManagerRunWorkTest::ready,
                AiProcessManagerRunWorkTest::failed);

        work.completeExceptionally(new IllegalStateException("nothing to compact"));

        assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.FAILED),
                statuses().stream().map(StatusEvent::type).toList());
        assertEquals("failed: nothing to compact", statuses().get(1).text());
    }

    @Test
    void anExceptionWhileStartingStillClosesTheWork() {
        manager.runWork("Compacting…", false, 5_000, () -> {
            throw new IllegalStateException("broker is null");
        }, AiProcessManagerRunWorkTest::ready, AiProcessManagerRunWorkTest::failed);

        assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.FAILED),
                statuses().stream().map(StatusEvent::type).toList(),
                "a throw before any future exists must not leave the session locked (an exception thrown while starting compaction)");
        assertFalse(manager.isWorkInFlight());
    }

    @Test
    void theTimeoutClosesWorkThatNeverFinishes() throws Exception {
        manager.runWork("Compacting…", false, 50, CompletableFuture::new, AiProcessManagerRunWorkTest::ready,
                AiProcessManagerRunWorkTest::failed);

        long deadline = System.currentTimeMillis() + 5_000;
        while (manager.isWorkInFlight() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.FAILED),
                statuses().stream().map(StatusEvent::type).toList(),
                "work that never completes must be closed as FAILED by the timeout");
    }

    @Test
    void processExitClosesTheWorkOnceAndALateCompletionIsDropped() {
        CompletableFuture<String> work = new CompletableFuture<>();
        manager.runWork("Compacting…", false, 5_000, () -> work, AiProcessManagerRunWorkTest::ready,
                AiProcessManagerRunWorkTest::failed);

        assertTrue(manager.exit("process exited"), "the first exit must close the in-flight work");
        assertFalse(manager.exit("duplicate exit"), "a second exit must not close work twice");
        work.complete("late");

        assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.FAILED),
                statuses().stream().map(StatusEvent::type).toList(),
                "exactly one closing status: the late completion after exit must not unlock a second time");
        assertEquals("process exited", statuses().get(1).text());
    }

    @Test
    void aSecondRequestWhileBusyIsRefusedAndReportsNothing() {
        CompletableFuture<String> work = new CompletableFuture<>();
        assertTrue(manager.runWork("Compacting…", false, 5_000, () -> work, AiProcessManagerRunWorkTest::ready,
                AiProcessManagerRunWorkTest::failed));

        assertFalse(manager.runWork("Compacting…", false, 5_000, () -> {
            throw new AssertionError("refused work must not start");
        }, AiProcessManagerRunWorkTest::ready, AiProcessManagerRunWorkTest::failed));

        assertEquals(1, events.size(), "a refused request must report nothing — no second BUSY");
    }

    @Test
    void aStaleCompletionCannotCloseNewerWork() {
        CompletableFuture<String> first = new CompletableFuture<>();
        manager.runWork("first", false, 5_000, () -> first, AiProcessManagerRunWorkTest::ready,
                AiProcessManagerRunWorkTest::failed);
        manager.exit("process exited");            // first closed by exit
        CompletableFuture<String> second = new CompletableFuture<>();
        manager.runWork("second", false, 5_000, () -> second, AiProcessManagerRunWorkTest::ready,
                AiProcessManagerRunWorkTest::failed);

        first.complete("stale");                  // the first work's late completion

        assertTrue(manager.isWorkInFlight(), "a stale completion of earlier work must not close the newer work");
        assertEquals(List.of(StatusEventTypeEnum.BUSY, StatusEventTypeEnum.FAILED, StatusEventTypeEnum.BUSY),
                statuses().stream().map(StatusEvent::type).toList());

        second.complete("ok");
        assertEquals(StatusEventTypeEnum.READY, statuses().get(3).type());
    }
}
