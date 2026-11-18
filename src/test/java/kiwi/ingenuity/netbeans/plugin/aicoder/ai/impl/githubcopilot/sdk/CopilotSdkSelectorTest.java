package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CopilotSdkSelectorTest {

    @TempDir
    Path temp;

    @AfterEach
    void resetSeams() {
        CopilotSdkSelector.setProtocolProbeForTests(null);
        CopilotSdkSelector.setSdkFactoriesForTests(null, null);
    }

    private Path cli() throws Exception {
        Path file = temp.resolve("copilot-test-cli");
        Files.writeString(file, "#!/bin/sh\n");
        file.toFile().setExecutable(true);
        return file;
    }

    private static CopilotSdk sdk(String path) throws CopilotSdkException {
        return CopilotSdkSelector.sdkFor(path);
    }

    /**
     * Runs {@code sdkFor} on the executor and records the thread that runs it, so a test can tell when that thread is
     * parked on the probe another caller is running.
     */
    private static Future<CopilotSdk> submitCaller(ExecutorService executor, Path cli, AtomicReference<Thread> thread) {
        return executor.submit(() -> {
            thread.set(Thread.currentThread());
            return sdk(cli.toString());
        });
    }

    /**
     * Waits until the caller is really waiting inside the selector's wait for the running probe: its state is WAITING
     * or TIMED_WAITING and the selector's {@code await} method is on its stack. A fixed sleep would race a slow
     * scheduler, where the caller has not got that far yet and would become a probe owner itself.
     */
    private static void awaitParkedOnTheProbe(AtomicReference<Thread> thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread caller = thread.get();
            if (caller != null && isParkedOnTheProbe(caller)) {
                return;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        fail("the second caller never parked on the running probe");
    }

    private static boolean isParkedOnTheProbe(Thread caller) {
        Thread.State state = caller.getState();
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
            return false;
        }
        return Arrays.stream(caller.getStackTrace()).anyMatch(
                frame -> frame.getClassName().endsWith("CopilotSdkSelector") && frame.getMethodName().equals("await"));
    }

    @Test
    void selectsV1ForProtocolsTwoAndThree() throws Exception {
        for (int protocol : new int[]{2, 3}) {
            CopilotSdkSelector.setProtocolProbeForTests(path -> protocol);
            assertTrue(sdk("test-cli").label().contains("v1"), "protocol " + protocol);
        }
    }

    @Test
    void selectsV2ForProtocolFourAndAbove() throws Exception {
        for (int protocol : new int[]{4, 5, 99}) {
            CopilotSdkSelector.setProtocolProbeForTests(path -> protocol);
            assertTrue(sdk("test-cli").label().contains("v2"), "protocol " + protocol);
        }
    }

    @Test
    void rejectsProtocolsBelowTwo() {
        CopilotSdkSelector.setProtocolProbeForTests(path -> 1);

        CopilotSdkException exception = assertThrows(CopilotSdkException.class, () -> sdk("test-cli"));

        assertEquals("This Copilot CLI speaks protocol 1; the plugin supports 2 and above. "
                + "Update the plugin or the Copilot CLI.", exception.getMessage());
    }

    @Test
    void cachesTheProbeUntilInvalidated() throws Exception {
        Path cli = cli();
        AtomicInteger calls = new AtomicInteger();
        CopilotSdkSelector.setProtocolProbeForTests(path -> {
            calls.incrementAndGet();
            return 4;
        });

        sdk(cli.toString());
        sdk(cli.toString());
        assertEquals(1, calls.get(), "a second call for the same unchanged file reuses the probe");
        CopilotSdkSelector.invalidate(cli.toString());
        sdk(cli.toString());

        assertEquals(2, calls.get(), "after invalidate the next call probes again");
    }

    @Test
    void aChangedModificationTimeProbesAgain() throws Exception {
        Path cli = cli();
        AtomicInteger calls = new AtomicInteger();
        CopilotSdkSelector.setProtocolProbeForTests(path -> calls.incrementAndGet() + 2);

        assertTrue(sdk(cli.toString()).label().contains("v1"));
        Files.setLastModifiedTime(cli, FileTime.fromMillis(Files.getLastModifiedTime(cli).toMillis() + 60_000L));

        assertTrue(sdk(cli.toString()).label().contains("v2"), "the second probe reports protocol 4");
        assertEquals(2, calls.get());
    }

    @Test
    void aChangedSizeProbesAgain() throws Exception {
        Path cli = cli();
        AtomicInteger calls = new AtomicInteger();
        CopilotSdkSelector.setProtocolProbeForTests(path -> calls.incrementAndGet() == 1 ? 3 : 4);

        assertTrue(sdk(cli.toString()).label().contains("v1"));
        FileTime modified = Files.getLastModifiedTime(cli);
        Files.writeString(cli, "#!/bin/sh\necho changed\n");
        Files.setLastModifiedTime(cli, modified);

        assertTrue(sdk(cli.toString()).label().contains("v2"), "the new size must be noticed even with the same time");
        assertEquals(2, calls.get());
    }

    @Test
    void aPathThatCannotBeResolvedIsNeverCached() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopilotSdkSelector.setProtocolProbeForTests(path -> {
            calls.incrementAndGet();
            return 4;
        });
        String missing = temp.resolve("no-such-cli").toString();

        sdk(missing);
        sdk(missing);

        assertEquals(2, calls.get());
    }

    @Test
    void aFailedProbeThrowsAndCachesNothing() throws Exception {
        Path cli = cli();
        AtomicInteger calls = new AtomicInteger();
        CopilotSdkSelector.setProtocolProbeForTests(path -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("ping failed");
            }
            return 3;
        });

        CopilotSdkException failure = assertThrows(CopilotSdkException.class, () -> sdk(cli.toString()));
        assertEquals("Could not determine Copilot CLI protocol", failure.getMessage());

        assertTrue(sdk(cli.toString()).label().contains("v1"));
        assertEquals(2, calls.get(), "the failure must not be remembered");
    }

    @Test
    void concurrentCallsShareOneProbe() throws Exception {
        Path cli = cli();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CopilotSdkSelector.setProtocolProbeForTests(path -> {
            calls.incrementAndGet();
            probeStarted.countDown();
            try {
                releaseProbe.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return 4;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CopilotSdk> first = executor.submit(() -> sdk(cli.toString()));
            assertTrue(probeStarted.await(10, TimeUnit.SECONDS));
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<CopilotSdk> second = submitCaller(executor, cli, secondThread);
            awaitParkedOnTheProbe(secondThread);
            assertFalse(second.isDone(), "the second caller must wait for the running probe");
            releaseProbe.countDown();

            assertTrue(first.get(10, TimeUnit.SECONDS).label().contains("v2"));
            assertTrue(second.get(10, TimeUnit.SECONDS).label().contains("v2"));
            assertEquals(1, calls.get(), "both callers must be served by a single probe");
        }
        finally {
            releaseProbe.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void waitersSeeTheFailureOfTheSharedProbeAndTheNextCallProbesAgain() throws Exception {
        Path cli = cli();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CopilotSdkSelector.setProtocolProbeForTests(path -> {
            if (calls.incrementAndGet() == 1) {
                probeStarted.countDown();
                try {
                    releaseProbe.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("ping failed");
            }
            return 2;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CopilotSdk> first = executor.submit(() -> sdk(cli.toString()));
            assertTrue(probeStarted.await(10, TimeUnit.SECONDS));
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<CopilotSdk> second = submitCaller(executor, cli, secondThread);
            awaitParkedOnTheProbe(secondThread);
            releaseProbe.countDown();

            ExecutionException firstFailure = assertThrows(ExecutionException.class,
                                                           () -> first.get(10, TimeUnit.SECONDS));
            ExecutionException secondFailure = assertThrows(ExecutionException.class,
                                                            () -> second.get(10, TimeUnit.SECONDS));
            assertTrue(firstFailure.getCause() instanceof CopilotSdkException);
            assertTrue(secondFailure.getCause() instanceof CopilotSdkException);
            assertEquals(1, calls.get(), "the waiter must not start a probe of its own");
        }
        finally {
            releaseProbe.countDown();
            executor.shutdownNow();
        }

        assertTrue(sdk(cli.toString()).label().contains("v1"));
        assertEquals(2, calls.get());
    }

    @Test
    void aLinkageErrorLoadingAnSdkBecomesAFixedMessageNamingOnlyTheErrorClass() {
        CopilotSdkSelector.setSdkFactoriesForTests(
                () -> {
                    throw new NoClassDefFoundError("secret/path/Hidden");
                },
                () -> {
                    throw new ExceptionInInitializerError("secret init text");
                });

        CopilotSdkSelector.setProtocolProbeForTests(path -> 3);
        CopilotSdkException v1 = assertThrows(CopilotSdkException.class, () -> sdk("test-cli"));
        assertEquals("Copilot SDK v1 failed to load: java.lang.NoClassDefFoundError", v1.getMessage());
        assertTrue(v1.getCause() instanceof NoClassDefFoundError);

        CopilotSdkSelector.setProtocolProbeForTests(path -> 4);
        CopilotSdkException v2 = assertThrows(CopilotSdkException.class, () -> sdk("test-cli"));
        assertEquals("Copilot SDK v2 failed to load: java.lang.ExceptionInInitializerError", v2.getMessage());
    }

    @Test
    void aBareNameIsResolvedThroughThePathVariable() throws Exception {
        Path cli = cli();

        assertEquals(cli.toRealPath(), CopilotSdkSelector.resolve("copilot-test-cli", temp.toString()));
        assertEquals(cli.toRealPath(), CopilotSdkSelector.resolve("copilot-test-cli",
                                                                  temp.resolve("elsewhere") + File.pathSeparator
                                                                  + temp));
        assertNull(CopilotSdkSelector.resolve("absent-cli", temp.toString()));
        assertNull(CopilotSdkSelector.resolve("copilot-test-cli", null));
    }

    @Test
    @Timeout(30)
    void anErrorThrownByTheProbeReachesAParkedWaiterInsteadOfHangingIt() throws Exception {
        Path cli = cli();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CopilotSdkSelector.setProtocolProbeForTests(path -> {
            probeStarted.countDown();
            try {
                releaseProbe.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("probe blew up");
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CopilotSdk> owner = executor.submit(() -> sdk(cli.toString()));
            assertTrue(probeStarted.await(10, TimeUnit.SECONDS));
            AtomicReference<Thread> waiterThread = new AtomicReference<>();
            Future<CopilotSdk> waiter = submitCaller(executor, cli, waiterThread);
            awaitParkedOnTheProbe(waiterThread);
            releaseProbe.countDown();

            ExecutionException waiterFailure = assertThrows(ExecutionException.class,
                                                            () -> waiter.get(10, TimeUnit.SECONDS));
            assertTrue(waiterFailure.getCause() instanceof CopilotSdkException, "the waiter must fail, not hang");
            ExecutionException ownerFailure = assertThrows(ExecutionException.class,
                                                           () -> owner.get(10, TimeUnit.SECONDS));
            assertTrue(ownerFailure.getCause() instanceof AssertionError, "an Error is not swallowed by the owner");
        }
        finally {
            releaseProbe.countDown();
            executor.shutdownNow();
        }

        CopilotSdkSelector.setProtocolProbeForTests(path -> 3);
        assertTrue(sdk(cli.toString()).label().contains("v1"), "the failed probe must not stay in flight or cached");
    }

    @Test
    void aBadPathEntryDoesNotHideTheEntriesAfterIt() throws Exception {
        Path cli = cli();
        String illegal = "bad" + (char) 0 + "entry";

        assertEquals(cli.toRealPath(), CopilotSdkSelector.resolve("copilot-test-cli",
                                                                  illegal + File.pathSeparator + temp));
    }

    @Test
    void aQuotedPathEntryIsUsedWithoutItsQuotes() throws Exception {
        Path cli = cli();

        assertEquals(cli.toRealPath(), CopilotSdkSelector.resolve("copilot-test-cli", "\"" + temp + "\""));
        assertEquals(cli.toRealPath(), CopilotSdkSelector.resolve("copilot-test-cli",
                                                                  "  \"" + temp + "\"  " + File.pathSeparator));
    }

    @Test
    void aFileWithoutTheExecuteBitEarlierOnThePathIsSkipped() throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path first = Files.createDirectory(temp.resolve("first"));
        Path second = Files.createDirectory(temp.resolve("second"));
        Path plain = first.resolve("copilot-test-cli");
        Files.writeString(plain, "not executable\n");
        plain.toFile().setExecutable(false, false);
        assumeFalse(Files.isExecutable(plain), "this file system or user ignores the execute bit");
        Path runnable = second.resolve("copilot-test-cli");
        Files.writeString(runnable, "#!/bin/sh\n");
        runnable.toFile().setExecutable(true);

        assertEquals(runnable.toRealPath(), CopilotSdkSelector.resolve("copilot-test-cli",
                                                                       first + File.pathSeparator + second));
        assertNull(CopilotSdkSelector.resolve("copilot-test-cli", first.toString()));
    }

    @Test
    void theStampChangesWithTheModificationTimeAndSizeAndIsNullForAnUnresolvablePath() throws Exception {
        Path cli = cli();
        String original = CopilotSdkSelector.stampFor(cli.toString());
        assertNotNull(original);
        assertEquals(original, CopilotSdkSelector.stampFor(cli.toString()), "stable while the file is unchanged");

        FileTime modified = Files.getLastModifiedTime(cli);
        Files.setLastModifiedTime(cli, FileTime.fromMillis(modified.toMillis() + 60_000L));
        String afterTime = CopilotSdkSelector.stampFor(cli.toString());
        assertNotEquals(original, afterTime);

        Files.writeString(cli, "#!/bin/sh\necho changed\n");
        Files.setLastModifiedTime(cli, FileTime.fromMillis(modified.toMillis() + 60_000L));
        assertNotEquals(afterTime, CopilotSdkSelector.stampFor(cli.toString()), "a new size must change the stamp");

        assertNull(CopilotSdkSelector.stampFor(temp.resolve("no-such-cli").toString()));
    }

    @Test
    void aPathWithADirectoryPartIsUsedAsItIsAndNotSearchedOnThePath() throws Exception {
        Path cli = cli();

        assertEquals(cli.toRealPath(), CopilotSdkSelector.resolve(cli.toString(), null));
        assertNull(CopilotSdkSelector.resolve(temp.resolve("sub").resolve("copilot-test-cli").toString(),
                                              temp.toString()));
    }
}
