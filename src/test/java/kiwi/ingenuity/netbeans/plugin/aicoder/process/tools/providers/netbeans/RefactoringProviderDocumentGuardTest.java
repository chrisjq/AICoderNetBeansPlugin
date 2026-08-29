package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The guard on {@code applyEdit}'s locked-file fallback.
 *
 * <p>
 * When the direct byte write fails with a held lock, the file is open in the editor with unsaved changes — and the
 * likeliest reason is that the USER typed after the content was verified against disk. The fallback used to replace the
 * document wholesale and save, which overwrote their typing silently. It now refuses unless the document still holds
 * the verified text.</p>
 *
 * <p>
 * Like {@code describeShortRead}, the comparison is pure and separate from the write so it can be driven here without a
 * live IDE. The CRLF case is the one that matters most: it is where a naive {@code equals} would not merely be
 * imprecise but would report "changed" for every CRLF file, replacing a data-loss bug with a path that never works.</p>
 */
class RefactoringProviderDocumentGuardTest {

    @Test
    void identicalTextPasses() {
        String text = "class A {\n    void go() {}\n}\n";

        assertTrue(RefactoringProvider.documentMatchesVerified(text, text));
    }

    @Test
    void changedTextIsRefused() {
        assertFalse(RefactoringProvider.documentMatchesVerified(
                "class A {\n    void go() { typedByTheUser(); }\n}\n",
                "class A {\n    void go() {}\n}\n"));
    }

    /**
     * The trap. NetBeans documents hold {@code \n} internally whatever the file uses on disk, so a CRLF file compared
     * raw differs on every line and the fallback would refuse every time. Same content, different line endings, must
     * read as unchanged.
     */
    @Test
    void crlfOnDiskMatchesLfInTheDocument() {
        assertTrue(RefactoringProvider.documentMatchesVerified(
                "class A {\n    void go() {}\n}\n",
                "class A {\r\n    void go() {}\r\n}\r\n"));
    }

    /**
     * Normalisation must not become a licence to ignore real edits: a CRLF file whose text actually changed is still
     * refused.
     */
    @Test
    void crlfNormalisationStillCatchesARealChange() {
        assertFalse(RefactoringProvider.documentMatchesVerified(
                "class A {\n    void go() { typedByTheUser(); }\n}\n",
                "class A {\r\n    void go() {}\r\n}\r\n"));
    }

    /**
     * Old-Mac bare CR is normalised too — it costs nothing here and leaving it out would mean the same broken-refusal
     * behaviour on any file that uses it.
     */
    @Test
    void bareCarriageReturnsAreNormalisedAsWell() {
        assertTrue(RefactoringProvider.documentMatchesVerified("a\nb\nc", "a\rb\rc"));
    }

    /**
     * Absent text cannot be shown to match, so it must not be written over. Fail closed.
     */
    @Test
    void nullOnEitherSideIsRefused() {
        assertFalse(RefactoringProvider.documentMatchesVerified(null, "a"));
        assertFalse(RefactoringProvider.documentMatchesVerified("a", null));
        assertFalse(RefactoringProvider.documentMatchesVerified(null, null));
    }

    @Test
    void emptyTextMatchesEmptyText() {
        assertTrue(RefactoringProvider.documentMatchesVerified("", ""));
    }

    /**
     * A newly created file is written by the create branch of writeFileContent, which returns before the flush and the
     * locked fallback are ever reached — so the guard never has to establish a baseline for a file that is not yet on
     * disk. Pinned at source level because that early return is the whole reason the baseline read is safe to do
     * unconditionally.
     */
    @Test
    void theNewFileBranchReturnsBeforeTheBaselineRead() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/process/tools/providers/netbeans/"
                + "RefactoringProvider.java"));
        int method = source.indexOf("public static String writeFileContent(");
        assertTrue(method >= 0, "writeFileContent must exist");
        int created = source.indexOf("return \"File created and saved\";", method);
        int baselineRead = source.indexOf("Files.readAllBytes(diskPath)", method);
        assertTrue(created > method, "the create branch must be present");
        assertTrue(baselineRead > created,
                "the new-file branch must return BEFORE the baseline read, so a file not yet on disk is never read");
    }

    /**
     * There must be no unguarded way to replace a document. The 2-arg writeViaDocument overload existed only for the
     * whole-file write that had no baseline; now that it establishes one, an overload that skips the check is a hole
     * the next caller falls into rather than a deliberate choice.
     */
    @Test
    void thereIsNoUnguardedWriteViaDocument() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/process/tools/providers/netbeans/"
                + "RefactoringProvider.java"));

        assertFalse(source.contains("writeViaDocument(FileObject fo, String content)"),
                "the unguarded 2-arg overload must not exist");
        assertFalse(source.contains("verifiedContent != null &&"),
                "the guard must not have a null-baseline escape — that can only ever fail open");
    }
}
