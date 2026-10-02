package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * AUDIT 7: the filePath parameter of the five ui/file+source tools (CloseFile, FixImports, OrganiseImports,
 * OrganiseMembers, ReformatFile) is read and changes behaviour. Proves the headless-testable branches: a
 * null/blank value is rejected with "is required" (provider does not fall back to the focused editor), and a
 * value pointing at a nonexistent file reports "File not found". FixImports and OrganiseImports no longer run
 * an editor action: an existing file is handled by HeadlessImportFixer and HeadlessImportOrganiser, and their
 * decisions are covered by ImportCandidatePlannerTest and ImportOrganisePlannerTest. OrganiseMembers and
 * ReformatFile still open an editor on the EDT for a file that exists, which a plain unit test does not stub.
 */
class UiToolsFilePathParamTest {

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    void closeFileRejectsNullAndBlankPath() {
        assertTrue(RefactoringProvider.closeFile(null).contains("is required"));
        assertTrue(RefactoringProvider.closeFile("").contains("is required"));
    }

    @Test
    void closeFileReportsMissingFileByName() {
        assertTrue(RefactoringProvider.closeFile("/no/such/aicoder-ui/close-me.java")
                .contains("File not found: /no/such/aicoder-ui/close-me.java"));
    }

    @Test
    void fixImportsRejectsNullAndBlankPath() {
        assertTrue(RefactoringProvider.fixImports(null).contains("is required"));
        assertTrue(RefactoringProvider.fixImports("").contains("is required"));
    }

    @Test
    void fixImportsReportsMissingFileByName() {
        assertTrue(RefactoringProvider.fixImports("/no/such/aicoder-ui/fix.java")
                .contains("File not found: /no/such/aicoder-ui/fix.java"));
    }

    @Test
    void organiseImportsRejectsNullAndBlankPath() {
        assertTrue(RefactoringProvider.organiseImports(null).contains("is required"));
        assertTrue(RefactoringProvider.organiseImports("").contains("is required"));
    }

    @Test
    void organiseImportsReportsMissingFileByName() {
        assertTrue(RefactoringProvider.organiseImports("/no/such/aicoder-ui/order.java")
                .contains("File not found: /no/such/aicoder-ui/order.java"));
    }

    @Test
    void organiseMembersRejectsNullAndBlankPath() {
        assertTrue(RefactoringProvider.organiseMembers(null).contains("is required"));
        assertTrue(RefactoringProvider.organiseMembers("").contains("is required"));
    }

    @Test
    void organiseMembersReportsMissingFileByName() {
        assertTrue(RefactoringProvider.organiseMembers("/no/such/aicoder-ui/members.java")
                .contains("File not found: /no/such/aicoder-ui/members.java"));
    }

    @Test
    void reformatFileRejectsNullAndBlankPath() {
        assertTrue(RefactoringProvider.reformatFile(null)
                .contains(McpToolPropertyEnum.FILE_PATH.key() + " is required"));
        assertTrue(RefactoringProvider.reformatFile("").contains("is required"));
    }

    @Test
    void reformatFileReportsMissingFileByName() {
        assertTrue(RefactoringProvider.reformatFile("/no/such/aicoder-ui/format.java")
                .contains("File not found: /no/such/aicoder-ui/format.java"));
    }
}
