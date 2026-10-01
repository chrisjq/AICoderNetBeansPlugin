package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files.ApplyEditTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files.WriteFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.CopyFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.CreateDirectoryTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.DeleteDirectoryTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.DeleteFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.MoveFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.SaveFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.FixImportsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.OrganiseImportsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.OrganiseMembersTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.ReformatFileTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolInvokerFileLockConcurrencyTest {

    private static final long WAIT_SECONDS = 5;

    @Test
    void differentFilesMayRunInsideTheirCriticalSectionsTogether() throws Exception {
        String fileA = "/tmp/file-lock-a-" + System.nanoTime();
        String fileB = "/tmp/file-lock-b-" + System.nanoTime();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "different-a", List.of(fileA), () -> {
                entered.countDown();
                awaitLatch(release);
                return "a";
            }));
            Future<String> second = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "different-b", List.of(fileB), () -> {
                entered.countDown();
                awaitLatch(release);
                return "b";
            }));

            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "different paths must both enter before either action is released");
            release.countDown();
            assertEquals("a", first.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("b", second.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sameFileSerializesEvenWhenOneCallerUsesASymlinkAlias() throws Exception {
        Path real = Files.createTempFile("file-lock-real-", ".txt");
        Path alias = real.resolveSibling(real.getFileName() + "-alias");
        try {
            Files.createSymbolicLink(alias, real);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch firstRelease = new CountDownLatch(1);
            CountDownLatch secondAttempted = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<String> first = executor.submit(() -> McpToolInvoker.withFileMutation(
                        "same-first", List.of(real.toString()), () -> {
                    firstEntered.countDown();
                    awaitLatch(firstRelease);
                    return "first";
                }));
                assertTrue(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

                Future<String> second = executor.submit(() -> {
                    secondAttempted.countDown();
                    return McpToolInvoker.withFileMutation(
                            "same-second", List.of(alias.toString()), () -> {
                        secondEntered.countDown();
                        return "second";
                    });
                });
                assertTrue(secondAttempted.await(WAIT_SECONDS, TimeUnit.SECONDS));
                assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS),
                        "the symlink alias must remain blocked while the real path is held");

                firstRelease.countDown();
                assertEquals("first", first.get(WAIT_SECONDS, TimeUnit.SECONDS));
                assertTrue(secondEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
                assertEquals("second", second.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            finally {
                firstRelease.countDown();
                executor.shutdownNow();
            }
        }
        finally {
            Files.deleteIfExists(alias);
            Files.deleteIfExists(real);
        }
    }

    @Test
    void exclusiveRefactorWaitsForSharedFileWork() throws Exception {
        CountDownLatch sharedEntered = new CountDownLatch(1);
        CountDownLatch sharedRelease = new CountDownLatch(1);
        CountDownLatch exclusiveAttempted = new CountDownLatch(1);
        CountDownLatch exclusiveEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> shared = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "shared-holder", List.of("/tmp/refactor-waits-shared"), () -> {
                sharedEntered.countDown();
                awaitLatch(sharedRelease);
                return "shared";
            }));
            assertTrue(sharedEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Future<String> exclusive = executor.submit(() -> {
                exclusiveAttempted.countDown();
                return McpToolInvoker.withExclusiveMutation(() -> {
                    exclusiveEntered.countDown();
                    return "exclusive";
                });
            });
            assertTrue(exclusiveAttempted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertFalse(exclusiveEntered.await(250, TimeUnit.MILLISECONDS),
                    "an exclusive refactor must wait for in-flight file work");

            sharedRelease.countDown();
            assertEquals("shared", shared.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(exclusiveEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("exclusive", exclusive.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            sharedRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void fileWorkWaitsForExclusiveRefactor() throws Exception {
        CountDownLatch exclusiveEntered = new CountDownLatch(1);
        CountDownLatch exclusiveRelease = new CountDownLatch(1);
        CountDownLatch sharedAttempted = new CountDownLatch(1);
        CountDownLatch sharedEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> exclusive = executor.submit(() -> McpToolInvoker.withExclusiveMutation(() -> {
                exclusiveEntered.countDown();
                awaitLatch(exclusiveRelease);
                return "exclusive";
            }));
            assertTrue(exclusiveEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Future<String> shared = executor.submit(() -> {
                sharedAttempted.countDown();
                return McpToolInvoker.withFileMutation(
                        "shared-waiter", List.of("/tmp/shared-waits-refactor"), () -> {
                    sharedEntered.countDown();
                    return "shared";
                });
            });
            assertTrue(sharedAttempted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertFalse(sharedEntered.await(250, TimeUnit.MILLISECONDS),
                    "file work must wait for an in-flight exclusive refactor");

            exclusiveRelease.countDown();
            assertEquals("exclusive", exclusive.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(sharedEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("shared", shared.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            exclusiveRelease.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * Item 8d: the previous version only synchronised SUBMISSION — with an instantaneous action, the thread
     * pool could run both calls fully back-to-back and never actually overlap, so the test proved nothing
     * about contention. Here the winner of the race into the acquisition is forced to HOLD both paths (via
     * {@code release}) while the loser is provably still waiting ({@code oneEntered} fires only once), so the
     * two calls are guaranteed to genuinely overlap before either completes.
     *
     * <p>
     * Checked empirically (mutation-removing the sort in {@code FileUtils#normaliseLockPaths}): this test
     * still passes without it, because {@code tryAcquireFileLocks} checks and commits every path in one
     * {@code synchronized} call — no thread can ever be observed mid-acquisition holding one path while
     * waiting on another, so A→B/B→A ordering cannot deadlock here regardless of key order. The sort's actual
     * job — a canonical, deterministic key order — is covered separately by
     * {@link #normalizedMultiPathKeysHaveOneDeterministicAcquisitionOrder}, which DOES fail without it.
     */
    @Test
    void oppositeDirectionMultiPathOperationsCompleteWithoutDeadlock() throws Exception {
        String fileA = "/tmp/copy-a-" + System.nanoTime();
        String fileB = "/tmp/copy-b-" + System.nanoTime();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        CountDownLatch oneEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> aToB = executor.submit(() -> {
                awaitBarrier(startTogether);
                return McpToolInvoker.withFileMutation("copy-a", List.of(fileA, fileB), () -> {
                    oneEntered.countDown();
                    awaitLatch(release);
                    return "a-to-b";
                });
            });
            Future<String> bToA = executor.submit(() -> {
                awaitBarrier(startTogether);
                return McpToolInvoker.withFileMutation("copy-b", List.of(fileB, fileA), () -> {
                    oneEntered.countDown();
                    awaitLatch(release);
                    return "b-to-a";
                });
            });
            assertTrue(oneEntered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "whichever direction wins the race must actually enter its critical section");
            release.countDown();

            assertEquals("a-to-b", aToB.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("b-to-a", bToA.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void readCompletesWhileExclusiveRefactorIsHeld() throws Exception {
        String file = "/tmp/read-during-refactor-" + System.nanoTime();
        CountDownLatch exclusiveEntered = new CountDownLatch(1);
        CountDownLatch exclusiveRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> exclusive = executor.submit(() -> McpToolInvoker.withExclusiveMutation(() -> {
                exclusiveEntered.countDown();
                awaitLatch(exclusiveRelease);
                return "exclusive";
            }));
            assertTrue(exclusiveEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Future<String> read = executor.submit(() -> McpToolInvoker.withFileRead(
                    "reader", List.of(file), () -> "read-ran"));
            assertEquals("read-ran", read.get(WAIT_SECONDS, TimeUnit.SECONDS),
                    "a read must not wait for an in-flight exclusive refactor");

            exclusiveRelease.countDown();
            assertEquals("exclusive", exclusive.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            exclusiveRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void mutationHelpersRefuseImmediatelyOnTheEdtRatherThanBlockingIt() throws Exception {
        String[] fileResult = new String[1];
        String[] exclusiveResult = new String[1];
        String[] directoryResult = new String[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            fileResult[0] = McpToolInvoker.withFileMutation(
                    "edt-session", List.of("/tmp/edt-mutation-test"), () -> "should-not-run");
            exclusiveResult[0] = McpToolInvoker.withExclusiveMutation(() -> "should-not-run");
            directoryResult[0] = McpToolInvoker.withDirectoryMutation(
                    "edt-session", "/tmp/edt-directory-test", () -> "should-not-run");
        });
        assertEquals("Error: mutation lock cannot wait on the EDT.", fileResult[0]);
        assertEquals("Error: mutation lock cannot wait on the EDT.", exclusiveResult[0]);
        assertEquals("Error: mutation lock cannot wait on the EDT.", directoryResult[0]);
    }

    /**
     * Item 1 / the HIGH finding: two PARALLEL calls from the SAME session on the SAME path used to both enter
     * their critical section at once — tryAcquireFileLocks treated same-session as automatic reentrancy
     * regardless of which thread was calling, so the second call ran concurrently with the first, and
     * whichever finished first released the lock while the other was still writing. Reentrancy is now keyed
     * on the acquiring THREAD, not merely the session, so a second parallel call must wait like any other
     * contender.
     */
    @Test
    void sameSessionParallelWritesSerialise() throws Exception {
        String file = "/tmp/same-session-parallel-" + System.nanoTime();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "same-session", List.of(file), () -> {
                firstEntered.countDown();
                awaitLatch(firstRelease);
                return "first";
            }));
            assertTrue(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> {
                secondAttempted.countDown();
                return McpToolInvoker.withFileMutation("same-session", List.of(file), () -> {
                    secondEntered.countDown();
                    return "second";
                });
            });
            assertTrue(secondAttempted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS),
                    "a second PARALLEL call from the SAME session must wait, not run concurrently with the first");

            firstRelease.countDown();
            assertEquals("first", first.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(secondEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("second", second.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            firstRelease.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * N4: reads must serialise only against a WRITE of the same path, never against another read — two
     * sessions reading the same file must both proceed.
     */
    @Test
    void concurrentReadsOfTheSameFileBothProceed() throws Exception {
        String file = "/tmp/concurrent-reads-" + System.nanoTime();
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> McpToolInvoker.withFileRead(
                    "reader-a", List.of(file), () -> {
                bothEntered.countDown();
                awaitLatch(release);
                return "a";
            }));
            Future<String> second = executor.submit(() -> McpToolInvoker.withFileRead(
                    "reader-b", List.of(file), () -> {
                bothEntered.countDown();
                awaitLatch(release);
                return "b";
            }));
            assertTrue(bothEntered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "two reads of the same file must both proceed concurrently, neither waiting for the other");

            release.countDown();
            assertEquals("a", first.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("b", second.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void readStillWaitsForAnInFlightWriteOfTheSameFile() throws Exception {
        String file = "/tmp/read-waits-write-" + System.nanoTime();
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch writerRelease = new CountDownLatch(1);
        CountDownLatch readerAttempted = new CountDownLatch(1);
        CountDownLatch readerEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> writer = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "writer", List.of(file), () -> {
                writerEntered.countDown();
                awaitLatch(writerRelease);
                return "written";
            }));
            assertTrue(writerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Future<String> reader = executor.submit(() -> {
                readerAttempted.countDown();
                return McpToolInvoker.withFileRead("reader", List.of(file), () -> {
                    readerEntered.countDown();
                    return "read";
                });
            });
            assertTrue(readerAttempted.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertFalse(readerEntered.await(250, TimeUnit.MILLISECONDS),
                    "a read must wait for an in-flight write of the same file");

            writerRelease.countDown();
            assertEquals("written", writer.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(readerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("read", reader.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            writerRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void everyPathLockedToolDeclaresItsOwnFileLocking() {
        McpHookServer server = new McpHookServer(0);
        List<McpToolInterface> pathLockedTools = List.of(
                new ApplyEditTool(),
                new WriteFileTool(),
                new SaveFileTool(server),
                new CopyFileTool(server),
                new MoveFileTool(server),
                new DeleteFileTool(server),
                new CreateDirectoryTool(server),
                new DeleteDirectoryTool(server),
                new ReformatFileTool(),
                new OrganiseImportsTool(),
                new OrganiseMembersTool(),
                new FixImportsTool());
        for (McpToolInterface tool : pathLockedTools) {
            assertTrue(tool.usesOwnFileLocking(), tool.getClass().getSimpleName()
                                                  + " must override usesOwnFileLocking() so it takes the shared gate, not the exclusive one");
        }
    }

    /**
     * M2: registry-driven, so a new mutating tool cannot silently fall through to the exclusive gate
     * unreviewed. Every {@code isMutating()} handler in {@link ToolHandlerFactory} must be deliberately wired
     * into exactly one of: path-locked ({@code usesOwnFileLocking()}), the whole-workspace EXCLUSIVE
     * allowlist below, or a tool that bypasses the global lock entirely because it has its own
     * synchronisation (build queue, git's own lock, the in-memory inbox broker).
     */
    @Test
    void everyMutatingHandlerIsWiredForLockingDeliberately() {
        McpHookServer server = new McpHookServer(0);
        List<String> unwired = new ArrayList<>();
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : ToolHandlerFactory.getToolHandlers(server).entrySet()) {
            McpToolInterface tool = entry.getValue();
            if (!tool.isMutating()) {
                continue;
            }
            boolean ownLocking = tool.usesOwnFileLocking();
            boolean exclusiveByDesign = EXCLUSIVE_ALLOWLIST.contains(tool.getClass());
            // The allowlist must never paper over a genuine path tool: if usesOwnFileLocking() is
            // already true, listing the class in EXCLUSIVE_ALLOWLIST too would hide which category
            // actually applies and could mask a future mistake going the other way.
            assertFalse(ownLocking && exclusiveByDesign, entry.getKey() + " (" + tool.getClass().getSimpleName()
                                                         + ") is both usesOwnFileLocking() and on EXCLUSIVE_ALLOWLIST — pick one category");
            boolean bypassesGlobalLockWithItsOwnSynchronisation = !tool.requiresGlobalMutationLock() && !ownLocking;
            if (!(ownLocking || exclusiveByDesign || bypassesGlobalLockWithItsOwnSynchronisation)) {
                unwired.add(entry.getKey() + " (" + tool.getClass().getSimpleName() + ")");
            }
        }
        assertTrue(unwired.isEmpty(), "wired into none of usesOwnFileLocking()/EXCLUSIVE_ALLOWLIST/an explicit "
                                      + "global-lock bypass, so it would silently fall through to the exclusive gate: " + unwired);
    }

    /**
     * Whole-workspace operations deliberately left on the default exclusive gate — not path-locked, and not
     * exempted via their own synchronisation. Grown only by deliberate review: see
     * {@link #everyMutatingHandlerIsWiredForLockingDeliberately}.
     */
    private static final Set<Class<?>> EXCLUSIVE_ALLOWLIST = Set.of(
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor.RenameSymbolTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor.MoveClassTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor.InlineVariableTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor.ChangeMethodSignatureTool.class,
            // Git write operations: serialised against refactors and path writes too, on top of
            // their own GIT_LOCK (ToolLockRegistry) — pre-existing, unrelated to this review.
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitAddTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommitTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitPushTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitPullTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCheckoutTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitBranchTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitDeleteBranchTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitStashTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitFetchTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitResetTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitMergeTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitRebaseTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCherryPickTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitTagTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitRemoteTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitRevertTool.class,
            // Neither path-shaped nor given its own bypass; a mutating IDE-state tool pre-dating
            // this review.
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.RefreshFileStatusTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.diag.RunInspectTool.class,
            kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.CloseFileTool.class);

    @Test
    void normalizedMultiPathKeysHaveOneDeterministicAcquisitionOrder() throws IOException {
        Path root = Files.createTempDirectory("file-lock-order-");
        try {
            Path first = root.resolve("a-destination");
            Path second = root.resolve("z-destination");
            List<String> keys = new ArrayList<>(LockManager.getInstance().normalisePaths(
                    List.of(second.toString(), first.toString())));
            assertEquals(List.of(first.toString(), second.toString()), keys);
        }
        finally {
            Files.deleteIfExists(root);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test release");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for test release", ex);
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        catch (Exception ex) {
            throw new AssertionError("could not synchronize concurrent operations", ex);
        }
    }
}
