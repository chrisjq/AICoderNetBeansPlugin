package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider.TargetDirectoryResolution;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * #17b: {@link RefactoringProvider#resolveMoveTargetDirectory} is pure path arithmetic (no FileObject/Project
 * resolution), so — unlike the live-project-only paths in {@code moveClass}/{@code moveFile} — it is fully testable
 * headlessly. {@code MoveFileTool} calls this BEFORE its access check and passes the result on to
 * {@code RefactoringProvider.moveFile}, so its correctness is what keeps the access check and the actual move looking
 * at the same path.
 */
class RefactoringProviderMoveTargetDirectoryTest {

    @Test
    void targetProjectPathOmitted_targetDirectoryUsedAsIs() {
        TargetDirectoryResolution result = RefactoringProvider.resolveMoveTargetDirectory("/some/dir", null);

        assertNull(result.error());
        assertEquals("/some/dir", result.path());
    }

    @Test
    void targetProjectPathOmitted_blankTreatedAsOmitted() {
        TargetDirectoryResolution result = RefactoringProvider.resolveMoveTargetDirectory("/some/dir", "   ");

        assertNull(result.error());
        assertEquals("/some/dir", result.path());
    }

    @Test
    void relativeTargetDirectoryResolvedAgainstTargetProjectPath() {
        TargetDirectoryResolution result = RefactoringProvider.resolveMoveTargetDirectory(
                "src/main/java/kiwi/ingenuity/platform/rest/oauth", "/path/to/app-platform-rest");

        assertNull(result.error());
        assertEquals("/path/to/app-platform-rest/src/main/java/kiwi/ingenuity/platform/rest/oauth", result.path());
    }

    @Test
    void absoluteTargetDirectoryUnderTargetProjectPathIsAccepted() {
        TargetDirectoryResolution result = RefactoringProvider.resolveMoveTargetDirectory(
                "/path/to/app-platform-rest/src/main/java/oauth", "/path/to/app-platform-rest");

        assertNull(result.error());
        assertEquals("/path/to/app-platform-rest/src/main/java/oauth", result.path());
    }

    @Test
    void absoluteTargetDirectoryOutsideTargetProjectPathIsRefused() {
        TargetDirectoryResolution result = RefactoringProvider.resolveMoveTargetDirectory(
                "/somewhere/else/entirely", "/path/to/app-platform-rest");

        assertNotNull(result.error());
        assertNull(result.path());
        assertTrue(result.error().contains("/somewhere/else/entirely"), result.error());
        assertTrue(result.error().contains("/path/to/app-platform-rest"), result.error());
    }

    @Test
    void relativeTargetDirectoryEscapingViaDotDotIsRefused() {
        TargetDirectoryResolution result = RefactoringProvider.resolveMoveTargetDirectory(
                "../escaped", "/path/to/app-platform-rest");

        assertNotNull(result.error());
        assertNull(result.path());
    }
}
