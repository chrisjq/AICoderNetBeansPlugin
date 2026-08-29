package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage for {@link RefactoringProvider#moveClasses}, in the same style as
 * {@link MoveClassLineValidationTest}: every case here fails before any real {@code FileObject} resolution is needed,
 * so it runs without a live NetBeans project. The success path (several files actually moved in one refactoring) and
 * the multi-top-level-type guard firing INSIDE a batch both need a resolvable Java source file and are not covered
 * here for the same reason the single-file success path and its own multi-type guard have no headless test either —
 * this is an existing gap in the suite, not a new one.
 */
class RefactoringProviderMoveClassesTest {

    private static final String VALID_PACKAGE = "com.example.target";

    @Test
    void emptyFilePathsListIsRejected() {
        String result = RefactoringProvider.moveClasses(List.of(), VALID_PACKAGE, false);

        assertTrue(result.contains(McpToolPropertyEnum.FILE_PATHS.key()),
                "the refusal must name the empty parameter: " + result);
        assertTrue(result.startsWith("Error:"), "an empty batch must be reported as an error: " + result);
    }

    @Test
    void nullTargetPackageIsRejectedBeforeAnyFileIsTouched() {
        String result = RefactoringProvider.moveClasses(List.of("/tmp/a.java", "/tmp/b.java"), null, false);

        assertTrue(result.contains(McpToolPropertyEnum.TARGET_PACKAGE.key()),
                "target package validation must run before any path is resolved: " + result);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void invalidTargetPackageIsRejectedBeforeAnyFileIsTouched() {
        String result = RefactoringProvider.moveClasses(List.of("/tmp/a.java", "/tmp/b.java"), "1.not.valid", false);

        assertTrue(result.contains("invalid target package"),
                "an invalid package name must be caught before touching the batch: " + result);
    }

    @Test
    void firstUnresolvableFileInABatchStopsTheWholeBatch() {
        // Neither path exists; the point is that the message names the FIRST one specifically, proving validation
        // runs in order and stops there rather than silently continuing to (or reporting) the second.
        String first = "/tmp/does-not-exist-first-" + System.nanoTime() + ".java";
        String second = "/tmp/does-not-exist-second-" + System.nanoTime() + ".java";

        String result = RefactoringProvider.moveClasses(List.of(first, second), VALID_PACKAGE, false);

        assertTrue(result.contains(first), "must name the file that actually failed: " + result);
        assertTrue(!result.contains(second),
                "must not have moved on to describe the second path — nothing after the first failure is examined: " + result);
    }
}
