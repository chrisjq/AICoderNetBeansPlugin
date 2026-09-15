package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * MoveClass distinguishes "line omitted" from "line malformed".
 * <p>
 * {@code MoveClassTool} defaults the parameter to 0, so 0 legitimately means "move the whole file". A NEGATIVE line is
 * a different thing entirely — a caller sent a value and got it wrong — but it used to fall through the same
 * {@code line > 0} branch and be treated as omitted, silently performing a broader move than was asked for. In a file
 * declaring one top-level type that happened with no complaint at all, since the multi-type guard never fired.
 * <p>
 * Both cases are proven here because either assertion alone is worthless: the first on its own would also pass if the
 * guard rejected every line, which would break the documented whole-file mode.
 */
class MoveClassLineValidationTest {

    private static final String VALID_PACKAGE = "com.example.target";

    @Test
    void negativeLineIsRejectedAsMalformed() {
        String result = RefactoringProvider.moveClass("/tmp/does-not-matter.java", -1, VALID_PACKAGE, null, false);

        assertTrue(result.contains(McpToolPropertyEnum.LINE.key()),
                   "the refusal must name the offending parameter: " + result);
        assertTrue(result.contains("-1"),
                   "the refusal must quote the received value back so the caller can see what it sent: " + result);
        assertTrue(result.startsWith("Error:"),
                   "a malformed argument must be reported as an error, not as a result: " + result);
    }

    @Test
    void omittedLineIsNotRejected() {
        // 0 is what the tool passes when the caller omits line, and it must still mean "move the whole file". The call
        // cannot complete headless, so it is only asserted NOT to fail the line check — it gets past validation and
        // stops at file resolution instead, which is the correct next failure.
        String result = RefactoringProvider.moveClass(null, 0, VALID_PACKAGE, null, false);

        assertFalse(result.contains("must be 1-based"),
                    "an omitted line must not be treated as malformed: " + result);
        assertTrue(result.contains(McpToolPropertyEnum.FILE_PATH.key()),
                   "validation should have moved on to the missing filePath: " + result);
    }

    @Test
    void unusableFileObjectIsRefusedBeforeConstructingARefactoring() throws IOException {
        Path source = Files.createTempFile("move-class-stale-", ".java");
        FileObject fileObject = FileUtil.toFileObject(source.toFile());

        assertTrue(RefactoringProvider.isUsableRefactoringFile(fileObject),
                   "a live local source file is safe to hand to the refactoring engine");
        Files.delete(source);

        assertFalse(RefactoringProvider.isUsableRefactoringFile(fileObject),
                    "a FileObject invalidated after resolution must not reach MoveRefactoring");
    }

    @Test
    void sourceIsRestoredWhenAMoveLeavesNeitherSourceNorTarget() throws IOException {
        Path source = Files.createTempFile("move-class-recovery-", ".java");
        byte[] originalBytes = "class RecoveryFixture {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, originalBytes);
        Path expectedTarget = source.resolveSibling("moved").resolve("RecoveryFixture.java");
        Files.delete(source);

        String result = RefactoringProvider.restoreSourceIfMoveLost(source.toFile(), expectedTarget.toFile(),
                                                                    originalBytes, "engine failed");

        assertTrue(result.contains("source was restored"), result);
        assertArrayEquals(originalBytes, Files.readAllBytes(source),
                          "recovery must restore the exact bytes present before the refactoring engine ran");
        assertFalse(Files.exists(expectedTarget), "the fixture must model a failed target write");
    }

    @Test
    void preExistingTargetIsNotOverwrittenAndSourceIsRestored() throws IOException {
        Path source = Files.createTempFile("move-class-existing-", ".java");
        byte[] original = "class Original {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sentinel = "sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, original);
        Path target = Files.createTempDirectory("move-target-existing-").resolve("Original.java");
        Files.write(target, sentinel);
        Files.delete(source);
        String result = RefactoringProvider.restoreSourceIfMoveLost(source.toFile(), target.toFile(), original,
                                                                    "engine failed", sentinel, Map.of(target, sentinel), target.getParent().toFile());
        assertTrue(result.contains("source was restored"), result);
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(sentinel, Files.readAllBytes(target));
    }

    @Test
    void realSnapshotPreservesExistingTargetAndSiblingWithRelativeDirectory() throws IOException {
        Path root = Files.createTempDirectory("move-target-snapshot-");
        Path targetDir = root.resolve("nested");
        Files.createDirectories(targetDir);
        Path source = root.resolve("Original.java");
        byte[] original = "class Original {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sentinel = "sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sibling = "class Sibling {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, original);
        Path target = targetDir.resolve("Original.java");
        Path siblingPath = targetDir.resolve("Sibling.java");
        Files.write(target, sentinel);
        Files.write(siblingPath, sibling);
        Files.delete(source);
        File relativeTargetDir = root.resolve("nested").resolve("..").resolve("nested").toFile();
        String result = RefactoringProvider.runMoveRefactoringWithRecoveryForTest(
                () -> "engine failed", source.toFile(), relativeTargetDir, "Original.java", original, "engine failed");
        assertTrue(result.contains("source was restored"), result);
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(sentinel, Files.readAllBytes(target));
        assertArrayEquals(sibling, Files.readAllBytes(siblingPath));
    }

    @Test
    void newlyCreatedTargetSuppressesRestore() throws IOException {
        Path source = Files.createTempFile("move-class-new-target-", ".java");
        byte[] original = "class Original {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, original);
        Path target = Files.createTempDirectory("move-target-new-").resolve("Original.java");
        byte[] moved = "class Original { int moved; }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, moved);
        Files.delete(source);
        String result = RefactoringProvider.restoreSourceIfMoveLost(source.toFile(), target.toFile(), original,
                                                                    "engine complete", null, Map.of(), target.getParent().toFile());
        assertFalse(result.contains("source was restored"), result);
        assertFalse(Files.exists(source));
    }

    @Test
    void alternateLandingIsReportedWithoutDuplicateRestore() throws IOException {
        Path source = Files.createTempFile("move-class-alternate-", ".java");
        byte[] original = "class Original {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, original);
        Path targetDir = Files.createTempDirectory("move-target-alternate-");
        Path alternate = targetDir.resolve("Original_1.java");
        Files.write(alternate, original);
        Files.delete(source);
        String result = RefactoringProvider.restoreSourceIfMoveLost(source.toFile(),
                                                                    targetDir.resolve("Original.java").toFile(), original, "engine failed", null,
                                                                    Map.of(), targetDir.toFile());
        assertTrue(result.contains("collision-renamed"), result);
        assertFalse(Files.exists(source));
    }

    @Test
    void changedSourceStillOnDiskIsLeftAsIsWithoutAWarning() throws IOException {
        // Live v1.4.7: extracting one class out of a two-type file legitimately rewrites the source; that success
        // was reported with a false "source changed concurrently" warning.
        Path source = Files.createTempFile("move-class-concurrent-", ".java");
        byte[] original = "class Original {} class Extracted {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] changed = "class Original {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, changed);
        String result = RefactoringProvider.restoreSourceIfMoveLost(source.toFile(),
                                                                    source.resolveSibling("missing").toFile(), original, "Moved class");
        assertEquals("Moved class", result);
        assertArrayEquals(changed, Files.readAllBytes(source));
    }

    @Test
    void recoveryIsWiredWhenRefactoringStepThrows() throws IOException {
        Path source = Files.createTempFile("move-class-wiring-", ".java");
        byte[] original = "class WiringFixture {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, original);
        Path targetDir = Files.createTempDirectory("move-target-wiring-");
        String result = RefactoringProvider.runMoveRefactoringWithRecoveryForTest(() -> {
            try {
                Files.delete(source);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
            throw new IllegalStateException("synthetic engine failure");
        }, source.toFile(), targetDir.toFile(), "WiringFixture.java", original, "engine failed");
        assertTrue(result.contains("source was restored"), result);
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void lineMoveOfSingleTopLevelTypeUsesWholeFileRoute() {
        assertTrue(RefactoringProvider.usesWholeFileMoveForResolvedClass(1),
                   "a line naming the only top-level type must avoid the broken TreePathHandle template route");
        assertFalse(RefactoringProvider.usesWholeFileMoveForResolvedClass(2),
                    "a multi-type file still needs class extraction to avoid moving unnamed sibling types");
    }
}
