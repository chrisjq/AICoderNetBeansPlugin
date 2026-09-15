package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * #17: headless coverage for the {@code targetProjectPath}-given case of {@code moveClass}/{@code moveClasses}, in the
 * same style as {@link MoveClassLineValidationTest}/{@link RefactoringProviderMoveClassesTest} — this suite has no live
 * open project (see those classes' own javadoc), so {@code OpenProjects.getDefault().getOpenProjects()} is reliably
 * empty here. That makes "no open project matches targetProjectPath" the one targetProjectPath-given outcome this suite
 * CAN assert deterministically; the actual cross-module move needs a real multi-project IDE session and is not covered
 * here for the same reason the rest of moveClass's live behaviour isn't.
 */
class RefactoringProviderMoveClassTargetProjectPathTest {

    private static final String VALID_PACKAGE = "com.example.target";

    @Test
    void targetProjectPathNotMatchingAnyOpenProjectIsRefused_singleFile() {
        String targetProjectPath = "/no/such/open/project-" + System.nanoTime();

        String result = RefactoringProvider.moveClass("/tmp/does-not-matter.java", 0, VALID_PACKAGE,
                                                      targetProjectPath, false);

        assertTrue(result.contains(McpToolPropertyEnum.TARGET_PROJECT_PATH.key()),
                   "the refusal must name the offending parameter: " + result);
        assertTrue(result.contains(targetProjectPath), "the refusal must quote the path back: " + result);
    }

    @Test
    void targetProjectPathNotMatchingAnyOpenProjectIsRefused_batch() {
        String targetProjectPath = "/no/such/open/project-" + System.nanoTime();

        String result = RefactoringProvider.moveClasses(List.of("/tmp/a.java", "/tmp/b.java"), VALID_PACKAGE,
                                                        targetProjectPath, false);

        assertTrue(result.contains(McpToolPropertyEnum.TARGET_PROJECT_PATH.key()),
                   "the refusal must name the offending parameter: " + result);
        assertTrue(result.contains(targetProjectPath), "the refusal must quote the path back: " + result);
    }

    @Test
    void targetProjectPathOmitted_noOtherOpenProjectMeansNoMisplacementRefusal() {
        // With no open project at all in this harness, packageBelongsToOtherOpenProjectMessage can never find a
        // competing project — so the omitted-targetProjectPath default must behave exactly as it did before #17,
        // falling through to the existing "cannot resolve source root" (fo cannot be resolved: /tmp/does-not-exist
        // is not a real, IDE-known file), never a spurious "belongs to project" refusal invented from nothing.
        String result = RefactoringProvider.moveClass("/tmp/does-not-exist-" + System.nanoTime() + ".java", 0,
                                                      VALID_PACKAGE, null, false);

        assertTrue(result.startsWith("File not found:"), result);
    }
}
