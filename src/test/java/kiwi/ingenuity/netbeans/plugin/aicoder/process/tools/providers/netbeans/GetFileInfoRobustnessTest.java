package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * GetFileInfo's "nothing throws out of this tool" guarantee, at the one place it was not held.
 * <p>
 * Every filesystem call inside the method is guarded, but the very first statement — turning the caller's string into a
 * {@code Path} — was not. A malformed path therefore escaped as an uncaught exception before any guard could run.
 * Reproduced live against 1.3.50: a filePath containing a NUL byte returned
 * {@code Internal error: Nul character not allowed}, which tells the caller the tool broke rather than that its
 * argument was unusable.
 */
class GetFileInfoRobustnessTest {

    /**
     * Assembled from a char at runtime — NEVER write the control character straight into this source file. A raw one
     * makes git classify the whole file as BINARY (no diffs, no reviewable history) and invites an editor or
     * formatter to drop it silently, which would leave this class green while testing nothing. A source-level escape
     * for the same code point is no better: Java resolves those before lexing, so it becomes the identical raw byte.
     * Concatenating {@code (char) 0} keeps the file plain ASCII while the string stays byte-identical at runtime.
     */
    private static final String NUL_PATH = "/tmp/bad" + ((char) 0) + "name.txt";

    /**
     * Establishes the premise instead of assuming it, mirroring FileScopeMalformedPathTest's fixtureCheck. Without
     * this, if the NUL byte were ever lost (a formatter stripping it, an editor normalising the constant), every
     * assertion below would keep passing for the wrong reason — {@code getFileInfo} would simply report "File not
     * found" for a now-ordinary path, and this class would go quietly vacuous instead of failing loudly.
     */
    @Test
    void fixtureCheck_thePathIsGenuinelyUnrepresentable() {
        assertThrows(InvalidPathException.class, () -> Path.of(NUL_PATH),
                "if this no longer throws, the rest of this class proves nothing");
    }

    @Test
    void aMalformedPathIsReportedRatherThanThrown() {
        String result = assertDoesNotThrow(() -> EditorContextProvider.getFileInfo(NUL_PATH),
                "a bad argument must not escape as an exception");

        assertNotNull(result);
        assertTrue(result.toLowerCase().contains("path"),
                "the refusal should name what was wrong with the input: " + result);
    }

    @Test
    void aNullPathIsReportedRatherThanThrown() {
        String result = assertDoesNotThrow(() -> EditorContextProvider.getFileInfo(null),
                "a null filePath must not escape as an exception");

        assertNotNull(result);
    }

    @Test
    void aBlankPathIsReportedRatherThanThrown() {
        String result = assertDoesNotThrow(() -> EditorContextProvider.getFileInfo("   "),
                "a blank filePath must not escape as an exception");

        assertNotNull(result);
    }

    /**
     * The negative control: a perfectly ordinary missing path must still produce the normal not-found answer, not the
     * malformed-input one. Without this, the guard above could pass by treating every path as malformed.
     */
    @Test
    void anOrdinaryMissingPathStillReportsFileNotFound() {
        String result = EditorContextProvider.getFileInfo("/tmp/definitely-does-not-exist-9e3a1f.txt");

        assertTrue(result.startsWith("File not found:"),
                "a well-formed but absent path is a different failure from a malformed one: " + result);
    }
}
