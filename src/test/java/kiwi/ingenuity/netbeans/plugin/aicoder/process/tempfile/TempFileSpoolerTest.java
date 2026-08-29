package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileDirEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileSpooler;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The general, reusable spooler ({@link TempFileSpooler}): content round-trip under an enum-chosen directory and
 * extension, the inline-vs-spool cut-off, and best-effort failure. Runs against
 * {@link TempFileRegistry#overrideBasePath}, so no live MCP server is involved.
 */
class TempFileSpoolerTest {

    @TempDir
    Path root;

    @BeforeEach
    void resetRegistryState() {
        TempFileRegistry.resetForTests();
        TempFileRegistry.overrideBasePath = root;
    }

    @AfterEach
    void tearDown() {
        TempFileRegistry.resetForTests();
    }

    @Test
    void spool_writesContent_underEnumDir_andExtension() throws IOException {
        Path file = TempFileSpooler.spool("ses-a", TempFileDirEnum.TOOL_RESULTS, "git-show", ".log", "the complete output");
        assertNotNull(file);
        assertTrue(Files.isRegularFile(file));
        assertEquals("the complete output", Files.readString(file));
        assertEquals(root.resolve("ses-a").resolve(TempFileRegistry.TEMP_DIR_NAME)
                .resolve(TempFileDirEnum.TOOL_RESULTS.dirName()), file.getParent());
        String name = file.getFileName().toString();
        assertTrue(name.startsWith("git-show-"));
        assertTrue(name.endsWith(".log"));
    }

    /**
     * Any caller-supplied extension is honoured, not just {@code .log}.
     *
     * <p>
     * This used to pass {@code null} for the directory and assert the file landed in the session tmp ROOT. That
     * behaviour is gone: a null dir silently reopened the unenumerated root that {@link TempFileDirEnum} exists to keep
     * closed, so it is now rejected — see {@code spool_nullDirIsRejected}. The extension half of the assertion is still
     * worth keeping, so it moved to a real directory.</p>
     */
    @Test
    void spool_handlesJsonExtension() throws IOException {
        Path file = TempFileSpooler.spool("ses-a", TempFileDirEnum.TOOL_RESULTS, "search", ".json", "{\"a\":1}");
        assertNotNull(file);
        assertEquals(root.resolve("ses-a").resolve(TempFileRegistry.TEMP_DIR_NAME)
                .resolve(TempFileDirEnum.TOOL_RESULTS.dirName()), file.getParent());
        assertTrue(file.getFileName().toString().endsWith(".json"));
        assertEquals("{\"a\":1}", Files.readString(file));
    }

    @Test
    void spoolIfLarge_keepsShortOutputInline() {
        String out = TempFileSpooler.spoolIfLarge("ses-a", TempFileDirEnum.TOOL_RESULTS, "git-log", ".log",
                "small output", 20_000);
        assertEquals("small output", out);
        assertEquals(0, TempFileRegistry.trackedFileCount());
    }

    /**
     * THE WHOLE POINT: the returned message must be SMALLER than the payload.
     *
     * <p>
     * This previously asserted {@code out.startsWith(text)} under the message "large output must be preserved verbatim"
     * — the bug written down as a requirement. The old code returned the entire text PLUS a truncation notice PLUS the
     * path, so a 2 MB diff reached the AI as more than 2 MB while claiming to have been truncated. Four git tools
     * adopted it and each grew larger than when they returned raw output.</p>
     */
    @Test
    void spoolIfLarge_returnsTruncatedHead_notTheWholePayload() throws IOException {
        String text = bigText(4000);
        assertTrue(text.length() > 20_000, "fixture precondition");

        String out = TempFileSpooler.spoolIfLarge("ses-a", TempFileDirEnum.TOOL_RESULTS, "git-diff", ".log",
                text, 20_000);

        assertTrue(out.length() < text.length(),
                "the returned message must be shorter than the payload — that is what spooling is for");
        assertTrue(out.startsWith(text.substring(0, 20_000)), "it must begin with the first thresholdChars characters");
        assertFalse(out.startsWith(text), "it must NOT contain the whole payload");
    }

    /**
     * The count must equal exactly what was dropped, or the AI cannot tell how much it is missing.
     */
    @Test
    void spoolIfLarge_statesHowManyCharactersWereOmitted() {
        String text = bigText(4000);
        int expectedOmitted = text.length() - 20_000;

        String out = TempFileSpooler.spoolIfLarge("ses-a", TempFileDirEnum.TOOL_RESULTS, "git-diff", ".log",
                text, 20_000);

        assertTrue(out.contains("... " + expectedOmitted + " chars omitted."),
                "the notice must name the exact number of characters dropped: " + tail(out));
    }

    /**
     * Truncating inline must never lose anything — the file holds the complete text, which is what makes the pointer
     * worth following.
     */
    @Test
    void spoolIfLarge_fileStillHoldsTheCompleteText() throws IOException {
        String text = bigText(4000);

        String out = TempFileSpooler.spoolIfLarge("ses-a", TempFileDirEnum.TOOL_RESULTS, "git-diff", ".log",
                text, 20_000);

        int marker = out.lastIndexOf("Full output written to: ");
        assertTrue(marker >= 0, "a spooled large result must append the path pointer");
        Path written = Path.of(out.substring(marker + "Full output written to: ".length()));
        assertEquals(text, Files.readString(written), "the file must hold the FULL text, not the truncated head");
        assertEquals(1, TempFileRegistry.trackedFileCount());
    }

    /**
     * The boundary, resolved to match the javadoc: AT the threshold it spools. Pinned because the code and its own
     * documentation disagreed here — the doc said "at or above", the code said {@code <= thresholdChars}.
     */
    @Test
    void spoolIfLarge_atExactlyTheThreshold_spools() {
        String text = "x".repeat(100);

        String out = TempFileSpooler.spoolIfLarge("ses-a", TempFileDirEnum.TOOL_RESULTS, "git-diff", ".log",
                text, 100);

        assertTrue(out.contains("chars omitted."), "exactly at the threshold must spool: " + tail(out));
        assertTrue(out.contains("... 0 chars omitted."),
                "nothing is dropped at exactly the threshold, and the count must say so honestly: " + tail(out));
    }

    @Test
    void spoolIfLarge_oneCharBelowTheThreshold_staysInline() {
        String text = "x".repeat(99);

        String out = TempFileSpooler.spoolIfLarge("ses-a", TempFileDirEnum.TOOL_RESULTS, "git-diff", ".log",
                text, 100);

        assertEquals(text, out, "below the threshold the text is returned untouched");
        assertEquals(0, TempFileRegistry.trackedFileCount());
    }

    /**
     * A null directory is rejected rather than silently parking files in the unenumerated tmp root, which is what
     * TempFileDirEnum exists to prevent. Rejection is a null return, not a throw — the class never throws into tool
     * code, and the caller degrades to returning its output inline.
     */
    @Test
    void spool_nullDirIsRejected() {
        assertNull(TempFileSpooler.spool("ses-a", null, "git-diff", ".log", "out"));
        assertEquals(0, TempFileRegistry.trackedFileCount());
    }

    @Test
    void spoolIfLarge_nullDirFallsBackToFullInline() {
        String text = bigText(4000);

        String out = TempFileSpooler.spoolIfLarge("ses-a", null, "git-diff", ".log", text, 20_000);

        assertEquals(text, out, "a rejected spool must not truncate — the tail would exist nowhere");
    }

    /**
     * The contract said "including the leading dot" and nothing enforced it, so "log" produced a filename ending
     * "-1234log". Normalised rather than rejected: the intent is unambiguous and refusing would lose output to make a
     * point.
     */
    @Test
    void spool_extensionWithoutLeadingDotIsNormalised() {
        Path file = TempFileSpooler.spool("ses-a", TempFileDirEnum.TOOL_RESULTS, "git-diff", "log", "out");

        assertNotNull(file);
        assertTrue(file.getFileName().toString().endsWith(".log"),
                "a dotless extension must still yield .log, got: " + file.getFileName());
    }

    private static String bigText(int lines) {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            big.append("line ").append(i).append('\n');
        }
        return big.toString();
    }

    /**
     * The trailing notice, for readable assertion messages without dumping 20 KB.
     */
    private static String tail(String out) {
        return out.length() <= 200 ? out : "..." + out.substring(out.length() - 200);
    }

    @Test
    void spoolIfLarge_failureFallsBackToFullInline() {
        // null session id makes creation fail (best-effort), so the caller gets the full text back.
        String out = TempFileSpooler.spoolIfLarge(null, TempFileDirEnum.TOOL_RESULTS, "git-show", ".log",
                "no temp available", 10);
        assertEquals("no temp available", out);
        assertEquals(0, TempFileRegistry.trackedFileCount());
    }

    @Test
    void spool_failureReturnsNull_neverThrows() {
        assertNull(TempFileSpooler.spool(null, TempFileDirEnum.TOOL_RESULTS, "git-show", ".log", "out"));
        assertNull(TempFileSpooler.spool("", TempFileDirEnum.TOOL_RESULTS, "git-show", ".log", "out"));
        assertEquals(0, TempFileRegistry.trackedFileCount());
        assertFalse(Files.exists(root.resolve("ses-a")), "nothing may be created for a failed call");
    }
}
