package kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link AbstractAcpProcessManager#startProcessOnOwnerThread} — the fix for a confirmed live Grok failure:
 * {@code grok agent stdio} arms {@code prctl(PR_SET_PDEATHSIG, SIGTERM)}, which fires when the THREAD that
 * forked it exits, not the process. GrokAiProcessManager used to call {@code ProcessBuilder.start()} directly
 * on its short-lived "grok-handshake" thread, which returns moments after the first prompt is sent — so the
 * kernel SIGTERM'd grok the instant that thread finished (exit 143, no stderr).
 *
 * <p>
 * No ordinary test process arms PDEATHSIG itself, so this cannot reproduce the kernel-level symptom directly
 * (a plain {@code sleep} survives even the OLD buggy code, since nothing asked the kernel to kill it on
 * thread exit). What IS directly testable, and what these tests pin, is the STRUCTURAL fix: the process is
 * started on a dedicated thread that outlives the (short-lived) caller, and that owner thread itself does not
 * end until the process does — the exact shape needed for PDEATHSIG safety regardless of which agent arms it.
 */
class AbstractAcpProcessManagerTest {

    @AfterEach
    void resetTimeoutSeam() {
        AbstractAcpProcessManager.processStartTimeoutSecondsForTests = null;
    }

    @Test
    @Timeout(10)
    void ownerThreadOutlivesTheShortLivedCallingThread() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sleep", "2");
        AtomicReference<Process> processRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // Simulates GrokAiProcessManager's real bug shape: the thread that calls startProcessOnOwnerThread
        // (standing in for the "grok-handshake" thread) finishes almost immediately afterward.
        Thread callingThread = new Thread(() -> {
            try {
                processRef.set(AbstractAcpProcessManager.startProcessOnOwnerThread(pb));
            }
            catch (Exception e) {
                errorRef.set(e);
            }
            finally {
                done.countDown();
            }
        }, "fake-handshake-thread");
        callingThread.start();

        assertTrue(done.await(5, TimeUnit.SECONDS), "startProcessOnOwnerThread must return promptly");
        callingThread.join(5_000);
        assertFalse(callingThread.isAlive(), "the calling thread must be able to finish, exactly like the real handshake thread does");
        if (errorRef.get() != null) {
            throw new AssertionError("startProcessOnOwnerThread threw", errorRef.get());
        }

        Process process = processRef.get();
        assertNotNull(process, "the process must have been handed back despite the calling thread already finishing");
        try {
            // The regression this guards against would have the kernel SIGTERM an agent that arms PDEATHSIG
            // right about here, the instant the forking thread (above) exited. A plain sleep never arms
            // PDEATHSIG, so it would survive even the old bug — this only pins that OUR side keeps a thread
            // alive for it, not that the kernel actually would have killed it.
            Thread.sleep(300);
            assertTrue(process.isAlive(), "the process must still be running after the spawning thread exited");
        }
        finally {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(10)
    void ownerThreadEndsOnlyWhenTheProcessExits() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sleep", "1");
        AtomicReference<Thread> ownerThreadRef = new AtomicReference<>();

        Process process = AbstractAcpProcessManager.startProcessOnOwnerThread(pb::start, ownerThreadRef::set);

        Thread owner = ownerThreadRef.get();
        assertNotNull(owner, "the observer must have seen the owner thread before it started");
        assertTrue(owner.isAlive(), "the owner thread must still be blocked in waitFor() while the process is alive");

        process.waitFor(5, TimeUnit.SECONDS);
        owner.join(5_000);
        assertFalse(owner.isAlive(), "the owner thread must end once the process it owns has exited");
    }

    /**
     * Review finding: the owner thread used to catch only {@code IOException} around {@code pb.start()}. A
     * {@code RuntimeException} (NPE, SecurityException, ...) left {@code started} incomplete forever, so the
     * caller's (then-unbounded) {@code get()} hung. {@code ProcessBuilder} is {@code final} and cannot be
     * subclassed to throw one for real, so this uses the {@link java.util.concurrent.Callable} test seam to
     * stand in for whatever runtime failure a real {@code start()} could throw.
     */
    @Test
    @Timeout(10)
    void aRuntimeExceptionFromStart_completesExceptionally_doesNotHangTheCaller() {
        Callable<Process> throwing = () -> {
            throw new NullPointerException("simulated runtime failure from start()");
        };

        IOException thrown = assertThrows(IOException.class,
                () -> AbstractAcpProcessManager.startProcessOnOwnerThread(throwing, t -> {
                }));
        assertTrue(thrown.getCause() instanceof NullPointerException, "the original failure must be reachable: " + thrown.getCause());
    }

    /**
     * Review finding: if the caller gives up on {@code started.get()} (interrupted, or — exercised here,
     * deterministically — timed out) before {@code pb.start()} completes, and {@code start()} THEN succeeds
     * moments later on the owner thread, the live process used to be orphaned: nothing holds a reference to
     * it, and the owner thread would sit in {@code waitFor()} on it forever. The fix destroys it the moment
     * it starts after all, which also unblocks the owner thread's {@code waitFor()} so it exits cleanly.
     * {@code processStartTimeoutSecondsForTests} shrinks the giving-up wait to 1 s so this does not need the
     * real 30 s; the starter sleeps 2 s before actually starting the real process, so the timeout always
     * fires first.
     */
    @Test
    @Timeout(10)
    void givingUpBeforeStartSucceeds_destroysTheLateProcess_andTheOwnerThreadExits() throws Exception {
        AbstractAcpProcessManager.processStartTimeoutSecondsForTests = 1L;
        AtomicReference<Thread> ownerThreadRef = new AtomicReference<>();
        ProcessBuilder realPb = new ProcessBuilder("sleep", "5");
        Callable<Process> slowToStart = () -> {
            Thread.sleep(2000);
            return realPb.start();
        };

        assertThrows(IOException.class,
                () -> AbstractAcpProcessManager.startProcessOnOwnerThread(slowToStart, ownerThreadRef::set));

        Thread owner = ownerThreadRef.get();
        assertNotNull(owner, "the observer must have seen the owner thread even though the caller gave up");
        owner.join(5_000);
        assertFalse(owner.isAlive(), "the owner thread must exit once its late-started process is destroyed, not leak forever");
    }
}
