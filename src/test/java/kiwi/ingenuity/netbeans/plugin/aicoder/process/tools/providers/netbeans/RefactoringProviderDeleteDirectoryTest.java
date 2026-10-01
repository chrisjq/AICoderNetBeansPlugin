package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openide.filesystems.FileObject;

/**
 * Exercises {@link RefactoringProvider#deleteDirectory} paths that need its package-private test seams —
 * {@link RefactoringProvider#testOpenProjectRoots} (since {@code OpenProjects.getDefault().getOpenProjects()}
 * is reliably empty in this headless harness, see e.g.
 * {@code RefactoringProviderMoveClassTargetProjectPathTest}) and
 * {@link RefactoringProvider#beforeDeleteDirectoryDeletes} — so these live alongside the provider rather than
 * in the Tool-layer test, which cannot see package-private members from another package.
 */
class RefactoringProviderDeleteDirectoryTest {

    @Test
    void refusesAnOpenProjectRoot(@TempDir Path dir) {
        RefactoringProvider.testOpenProjectRoots = List.of(dir.toFile());
        try {
            String result = RefactoringProvider.deleteDirectory(dir.toString());

            assertTrue(result.contains("open project's root"), result);
            assertTrue(Files.exists(dir));
        }
        finally {
            RefactoringProvider.testOpenProjectRoots = null;
        }
    }

    /**
     * Mutation-prove target: the nested-project overlap check in {@link RefactoringProvider#deleteDirectory}
     * must catch a project root NESTED INSIDE the target (a descendant), not only an exact match — a target
     * that is otherwise a file-free tree of empty directories would not be caught by the files-found refusal.
     */
    @Test
    void refusesWhenAProjectRootIsNestedInsideTheTarget(@TempDir Path dir) throws Exception {
        Path target = Files.createDirectory(dir.resolve("victim"));
        Path nestedProjectRoot = Files.createDirectories(target.resolve("modules/nested-project"));
        RefactoringProvider.testOpenProjectRoots = List.of(nestedProjectRoot.toFile());
        try {
            String result = RefactoringProvider.deleteDirectory(target.toString());

            assertTrue(result.contains("overlaps an open project's root"), result);
            assertTrue(Files.exists(nestedProjectRoot), "the nested project root must survive");
        }
        finally {
            RefactoringProvider.testOpenProjectRoots = null;
        }
    }

    /**
     * Mutation-prove target: a target nested INSIDE an open project's root (the project root is an ancestor
     * of the target, not a descendant or the target itself) is the normal case for almost every real call —
     * e.g. deleting a project's own build/scratch directory — and must be ALLOWED. Containment within the
     * open projects is already enforced by the session scope check before this method ever runs; refusing
     * here too, as an earlier version of this guard mistakenly did, would make the tool refuse nearly
     * everything.
     */
    @Test
    void deletesAnEmptyDirectoryInsideAProjectRoot(@TempDir Path dir) throws Exception {
        Path projectRoot = Files.createDirectory(dir.resolve("project"));
        Path target = Files.createDirectories(projectRoot.resolve("build/scratch"));
        RefactoringProvider.testOpenProjectRoots = List.of(projectRoot.toFile());
        try {
            String result = RefactoringProvider.deleteDirectory(target.toString());

            assertEquals("Directory deleted", result);
            assertFalse(Files.exists(target));
            assertTrue(Files.exists(projectRoot), "the project root itself must survive");
        }
        finally {
            RefactoringProvider.testOpenProjectRoots = null;
        }
    }

    /**
     * Mutation-prove target: {@code collectTree} must never follow a symlink. A link inside the tree pointing
     * at a directory OUTSIDE it must stop the delete entirely — nothing inside the tree is removed either —
     * and the outside directory it points to must be completely untouched.
     */
    @Test
    void refusesALinkToAnOutsideDirectory_outsideDirSurvives(@TempDir Path dir) throws Exception {
        Path victim = Files.createDirectory(dir.resolve("victim"));
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Path outsideEmpty = Files.createDirectory(outside.resolve("empty"));
        Files.createSymbolicLink(victim.resolve("link"), outside);

        String result = RefactoringProvider.deleteDirectory(victim.toString());

        assertTrue(result.contains("symbolic link"), result);
        assertTrue(Files.exists(victim), "the tree holding the link must survive");
        assertTrue(Files.exists(outside) && Files.exists(outsideEmpty), "nothing reached through the link may be touched");
    }

    /**
     * A link that cycles back into the tree (or onto itself) must be refused instead of walked forever.
     */
    @Test
    void refusesACyclicLink_withoutHanging(@TempDir Path dir) throws Exception {
        Path victim = Files.createDirectory(dir.resolve("victim"));
        Files.createSymbolicLink(victim.resolve("self"), victim);

        String result = RefactoringProvider.deleteDirectory(victim.toString());

        assertTrue(result.contains("symbolic link"), result);
        assertTrue(Files.exists(victim));
    }

    /**
     * The target path itself — not something found while walking it — being a symlink must also refuse,
     * before any walk starts.
     */
    @Test
    void refusesWhenTheTargetItselfIsASymlink(@TempDir Path dir) throws Exception {
        Path real = Files.createDirectory(dir.resolve("real"));
        Path link = dir.resolve("link");
        Files.createSymbolicLink(link, real);

        String result = RefactoringProvider.deleteDirectory(link.toString());

        assertTrue(result.contains("symbolic link"), result);
        assertTrue(Files.exists(real));
    }

    /**
     * Mutation-prove target: {@link RefactoringProvider#beforeDeleteDirectoryDeletes} fires once, after the
     * emptiness check and before the first delete — this test uses it to create a file in the tree at exactly
     * that point, deterministically, every run. A recursive top-folder delete (the old implementation, and
     * the shape of the regression this guards against) would remove the whole tree including that file; the
     * deepest-first {@code java.nio.file.Files.delete} loop must instead fail on the now-non-empty directory
     * and report it, leaving the file — and the directory that gained it — in place.
     */
    @Test
    void raceWinningFileStopsTheDeleteAndSurvivesIt(@TempDir Path dir) throws Exception {
        Path victim = dir.resolve("victim");
        Path sub = Files.createDirectories(victim.resolve("a/b"));
        Path raceFile = sub.resolve("appeared.txt");
        RefactoringProvider.beforeDeleteDirectoryDeletes = () -> {
            try {
                Files.writeString(raceFile, "appeared mid-delete");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        try {
            String result = RefactoringProvider.deleteDirectory(victim.toString());

            assertTrue(result.contains("a file appeared") && result.contains("nothing containing files was removed"), result);
            assertTrue(Files.exists(raceFile), "the file that appeared mid-delete must survive");
            assertTrue(Files.exists(sub), "the race file's own directory must still be there holding it");
        }
        finally {
            RefactoringProvider.beforeDeleteDirectoryDeletes = () -> {
            };
        }
    }

    /**
     * The deletes go through {@code java.nio}, bypassing NetBeans entirely, so without an explicit refresh
     * the FileObject resolved before the delete would keep reporting stale, deleted-but-still-valid state.
     *
     * <p>
     * {@code fo.isValid()}/{@code FileUtil.toFileObject(...)==null} themselves are NOT asserted here: this
     * headless harness has no live {@code MasterFileSystem}, so a {@code @TempDir} FileObject's cached
     * validity does not reliably track disk the way it does inside a running IDE — the same documented
     * limitation {@code RefactoringProvider.applyEdit}'s own javadoc calls out for its truncation guard. What
     * IS verifiable here, and is what the refresh call is actually for, is that the real directory is gone
     * from disk and that resolving/refreshing it beforehand does not stop the delete from happening.
     */
    @Test
    void successfulDeleteRemovesTheDirectoryFromDisk(@TempDir Path dir) throws Exception {
        Path victim = Files.createDirectory(dir.resolve("victim"));
        FileObject fo = FileUtils.resolveByFile(victim.toFile());
        assertTrue(fo.isValid());

        String result = RefactoringProvider.deleteDirectory(victim.toString());

        assertEquals("Directory deleted", result);
        assertFalse(Files.exists(victim));
    }

    /**
     * A partial failure still removes real directories (the deepest ones, before the race file blocks their
     * parent) — the parent refresh must run regardless. Disk state is what this harness can verify (see
     * {@link #successfulDeleteRemovesTheDirectoryFromDisk} for why FileObject validity itself is not asserted
     * here): the deepest directory is actually gone, and the one the race file landed in survives holding it.
     */
    @Test
    void partialFailureStillRemovesWhatWasDeletedBeforeTheRaceFile(@TempDir Path dir) throws Exception {
        Path victim = dir.resolve("victim");
        Path b = Files.createDirectories(victim.resolve("a/b"));
        Path c = Files.createDirectory(b.resolve("c"));
        Path raceFile = b.resolve("appeared.txt");
        RefactoringProvider.beforeDeleteDirectoryDeletes = () -> {
            try {
                Files.writeString(raceFile, "appeared mid-delete");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        try {
            String result = RefactoringProvider.deleteDirectory(victim.toString());

            assertTrue(result.contains("a file appeared"), result);
            assertFalse(Files.exists(c), "c was actually deleted before the race file blocked its parent");
            assertTrue(Files.exists(b), "b survived the partial failure, holding the race file");
            assertTrue(Files.exists(raceFile));
        }
        finally {
            RefactoringProvider.beforeDeleteDirectoryDeletes = () -> {
            };
        }
    }

    /**
     * Mutation-prove target: the re-check immediately before each {@code Files.delete} must catch a recorded
     * directory that has been replaced by a plain file since the walk — {@code Files.delete} itself would
     * otherwise delete whatever is now at that path, file included.
     */
    @Test
    void refusesWhenARecordedDirectoryWasReplacedByAFile(@TempDir Path dir) throws Exception {
        Path victim = dir.resolve("victim");
        Path b = Files.createDirectories(victim.resolve("a/b"));
        RefactoringProvider.beforeDeleteDirectoryDeletes = () -> {
            try {
                Files.delete(b);
                Files.writeString(b, "now a file, not a directory");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        try {
            String result = RefactoringProvider.deleteDirectory(victim.toString());

            assertTrue(result.contains("is no longer a directory"), result);
            assertTrue(Files.isRegularFile(b), "the file that replaced the directory must survive");
            assertTrue(Files.exists(victim), "nothing above the swapped entry may be deleted either");
        }
        finally {
            RefactoringProvider.beforeDeleteDirectoryDeletes = () -> {
            };
        }
    }

    /**
     * An unreadable directory must be refused with a message naming it as unreadable, not misreported as a
     * race ("a file appeared") once its hidden contents later block a delete that should never have been
     * attempted in the first place.
     */
    @Test
    void refusesAnUnreadableDirectory(@TempDir Path dir) throws Exception {
        Path victim = Files.createDirectory(dir.resolve("victim"));
        Path locked = Files.createDirectory(victim.resolve("locked"));
        assertTrue(locked.toFile().setReadable(false), "test setup needs to be able to lock the directory");
        // Running as root bypasses the permission bit entirely, so listFiles() would still succeed — skip
        // rather than fail in that environment instead of asserting a permission model that does not apply.
        assumeTrue(locked.toFile().listFiles() == null, "needs a user that is actually blocked by the unreadable bit");
        try {
            String result = RefactoringProvider.deleteDirectory(victim.toString());

            assertTrue(result.contains("cannot read"), result);
            assertTrue(Files.exists(locked));
        }
        finally {
            locked.toFile().setReadable(true);
        }
    }
}
