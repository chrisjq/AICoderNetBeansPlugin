package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the path shortening shown in confirm prompts and system notifications.
 *
 * <p>
 * These exercise the package-private overload that takes the open-project
 * directories directly — the public method reads them from a running IDE, which
 * is not available here. The shortening rules are all in the overload.
 */
class ProjectPathUtilTest {

    private static String sep(String... parts) {
        return String.join(File.separator, parts);
    }

    private static Path linkOrSkip(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        }
        catch (IOException | UnsupportedOperationException e) {
            // Windows without developer mode cannot create symlinks.
            Assumptions.abort("symlinks not supported here: " + e.getMessage());
            return null; // unreachable
        }
    }

    // ---- basic shortening ----
    @Test
    void pathInsideProjectIsRelativeToProjectAndKeepsProjectName(@TempDir Path tmp) {
        File project = tmp.resolve("MyProject").toFile();
        String file = new File(project, sep("docs", "notes.md")).getAbsolutePath();

        assertEquals(sep("MyProject", "docs", "notes.md"),
                ProjectPathUtil.shortPath(file, List.of(project)),
                "the project directory name must survive so the prompt says which project");
    }

    @Test
    void projectDirectoryItselfShortensToJustItsName(@TempDir Path tmp) {
        File project = tmp.resolve("MyProject").toFile();

        assertEquals("MyProject",
                ProjectPathUtil.shortPath(project.getAbsolutePath(), List.of(project)),
                "no trailing separator when the path IS the project directory");
    }

    @Test
    void firstMatchingProjectWins(@TempDir Path tmp) {
        File other = tmp.resolve("Other").toFile();
        File project = tmp.resolve("MyProject").toFile();
        String file = new File(project, "pom.xml").getAbsolutePath();

        assertEquals(sep("MyProject", "pom.xml"),
                ProjectPathUtil.shortPath(file, List.of(other, project)));
    }

    // ---- paths that must NOT be shortened ----
    @Test
    void pathOutsideEveryProjectKeepsFullPath(@TempDir Path tmp) {
        File project = tmp.resolve("MyProject").toFile();

        assertEquals("/etc/hosts", ProjectPathUtil.shortPath("/etc/hosts", List.of(project)),
                "a delete prompt must not hide that the file sits outside the project");
    }

    @Test
    void noOpenProjectsKeepsFullPath() {
        assertEquals("/etc/hosts", ProjectPathUtil.shortPath("/etc/hosts", List.of()));
    }

    @Test
    void siblingDirectorySharingAProjectNamePrefixIsNotTreatedAsInside(@TempDir Path tmp) {
        File project = tmp.resolve("MyProject").toFile();
        // Shares the textual prefix "…/MyProject" but is a different directory.
        String file = tmp.resolve("MyProject-old").resolve("notes.md").toFile().getAbsolutePath();

        String result = ProjectPathUtil.shortPath(file, List.of(project));

        assertEquals(file, result,
                "a plain startsWith would report MyProject/-old/notes.md — a path that does not exist");
    }

    // ---- null / blank passthrough ----
    @Test
    void nullAndBlankAreReturnedUnchanged(@TempDir Path tmp) {
        List<File> projects = List.of(tmp.toFile());

        assertNull(ProjectPathUtil.shortPath(null, projects));
        assertEquals("", ProjectPathUtil.shortPath("", projects));
        assertEquals("   ", ProjectPathUtil.shortPath("   ", projects));
    }

    @Test
    void nullProjectDirectoryIsSkippedRatherThanThrowing(@TempDir Path tmp) {
        File project = tmp.resolve("MyProject").toFile();
        String file = new File(project, "pom.xml").getAbsolutePath();
        List<File> withNull = java.util.Arrays.asList(null, project);

        assertEquals(sep("MyProject", "pom.xml"), ProjectPathUtil.shortPath(file, withNull));
    }

    // ---- symlinked project roots ----
    //
    // The reported defect: with /share symlinked to /Users/chris/.SyncShare, a
    // notification printed the full absolute path instead of the short one. A raw
    // prefix comparison only matches when the path and the project directory
    // happen to be spelled with the same alias.
    @Test
    void fileReachedThroughSymlinkedAliasStillShortens(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real").resolve("MyProject"));
        Files.createDirectories(real.resolve("docs"));
        Files.writeString(real.resolve("docs").resolve("notes.md"), "x");

        Path link = linkOrSkip(tmp.resolve("alias"), tmp.resolve("real"));

        // Project opened under the real path; the tool reports the aliased path.
        String viaAlias = link.resolve("MyProject").resolve("docs").resolve("notes.md")
                .toFile().getAbsolutePath();

        assertEquals(sep("MyProject", "docs", "notes.md"),
                ProjectPathUtil.shortPath(viaAlias, List.of(real.toFile())));
    }

    @Test
    void projectOpenedUnderSymlinkStillShortensARealPath(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real").resolve("MyProject"));
        Files.createDirectories(real.resolve("docs"));
        Files.writeString(real.resolve("docs").resolve("notes.md"), "x");

        Path link = linkOrSkip(tmp.resolve("alias"), tmp.resolve("real"));

        // The mirror image: project opened through the symlink, real path reported.
        File projectViaAlias = link.resolve("MyProject").toFile();
        String viaReal = real.resolve("docs").resolve("notes.md").toFile().getAbsolutePath();

        assertEquals(sep("MyProject", "docs", "notes.md"),
                ProjectPathUtil.shortPath(viaReal, List.of(projectViaAlias)));
    }

    @Test
    void shorteningWorksForAPathThatDoesNotExistYet(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real").resolve("MyProject"));
        Path link = linkOrSkip(tmp.resolve("alias"), tmp.resolve("real"));

        // Copy/move/create prompts are shown BEFORE the file exists, so canonical
        // resolution must not depend on the target being present.
        String notYetCreated = link.resolve("MyProject").resolve("target")
                .resolve("generated.txt").toFile().getAbsolutePath();

        assertEquals(sep("MyProject", "target", "generated.txt"),
                ProjectPathUtil.shortPath(notYetCreated, List.of(real.toFile())));
    }

    // ---- truncation ----
    @Test
    void veryLongPathIsTruncatedFromTheLeftKeepingTheFileName(@TempDir Path tmp) {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            deep.append("directory-level-").append(i).append(File.separator);
        }
        File project = tmp.resolve("MyProject").toFile();
        String file = new File(project, deep + "target.txt").getAbsolutePath();

        String result = ProjectPathUtil.shortPath(file, List.of(project));

        assertTrue(result.startsWith("..."), "truncation marker: " + result);
        assertTrue(result.endsWith("target.txt"), "file name must survive truncation: " + result);
        assertEquals(128, result.length(), "truncated to MAX_LEN");
    }

    @Test
    void longPathOutsideAnyProjectIsAlsoTruncated(@TempDir Path tmp) {
        StringBuilder deep = new StringBuilder("/");
        for (int i = 0; i < 40; i++) {
            deep.append("directory-level-").append(i).append("/");
        }
        String file = deep + "target.txt";

        String result = ProjectPathUtil.shortPath(file, List.of(tmp.toFile()));

        assertTrue(result.startsWith("..."), result);
        assertTrue(result.endsWith("target.txt"), result);
    }
}
