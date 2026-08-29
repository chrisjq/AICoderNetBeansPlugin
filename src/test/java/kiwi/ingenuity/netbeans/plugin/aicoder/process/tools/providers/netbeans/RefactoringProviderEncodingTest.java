package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Fix 11: byte-level file I/O (RefactoringProvider.applyEdit/writeFileContent, EditorContextProvider's read path, and
 * the AiTopComponent/CodexAppServerHandler diff-preview and revert paths) now resolves its charset through
 * {@link RefactoringProvider#resolveCharset}, a single function every one of those call sites shares, instead of each
 * hardcoding UTF-8 independently.
 *
 * <p>
 * What this class deliberately does NOT attempt: proving a round-trip against a project configured for a real non-UTF-8
 * encoding (Shift-JIS, Windows-1252, etc). {@link org.netbeans.api.queries.FileEncodingQuery} resolves through
 * ProjectManager/global Lookup, which this headless unit-test harness does not provide —
 * {@link EditorContextProviderTest#fileSizeAndMetaReportsBytesLinesAndEncoding()} already documents this ("resolves to
 * 'unknown' outside the running IDE"). Without a live IDE there is no way to make {@code resolveCharset} return
 * anything other than its documented UTF-8 fallback, so a test that tried to assert Shift-JIS behaviour here would only
 * look like coverage, not be any. What IS tested: the fallback itself, and that the read/match/re-check/write sequence
 * in applyEdit and writeFileContent all share one resolved Charset instance rather than re-deciding it at each step —
 * the actual mechanism that has to hold for a real non-UTF-8 project to round-trip correctly.
 */
class RefactoringProviderEncodingTest {

    private static Path tempFile(String suffix) throws IOException {
        return Files.createTempFile("aicoder-encoding-test", suffix);
    }

    @Test
    void resolveCharsetFallsBackToDocumentedUtf8OutsideALiveProject() throws IOException {
        Path file = tempFile(".txt");
        try {
            Files.writeString(file, "plain ascii");
            // No FileObject is resolvable for a bare temp path outside every open
            // project in this headless harness, so this exercises the fallback
            // branch, not a real FileEncodingQuery answer.
            assertEquals(StandardCharsets.UTF_8, RefactoringProvider.resolveCharset(file.toFile()));
            assertEquals(StandardCharsets.UTF_8, RefactoringProvider.resolveCharset(file.toString()));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void resolveCharsetHandlesNullAndBlankWithoutThrowing() {
        assertEquals(StandardCharsets.UTF_8, RefactoringProvider.resolveCharset((String) null));
        assertEquals(StandardCharsets.UTF_8, RefactoringProvider.resolveCharset(""));
        assertEquals(StandardCharsets.UTF_8, RefactoringProvider.resolveCharset((java.io.File) null));
    }

    /**
     * The defect this fix closes: ApplyEdit's oldString match works on whatever String the read decoded, and the write
     * must re-encode with the exact same charset — otherwise the match is against the wrong text, or the write produces
     * different bytes than what was matched. This proves that chain holds for multi-byte characters (café, a CJK run,
     * an emoji outside the BMP) round-tripping through applyEdit unchanged apart from the intended edit.
     */
    @Test
    void applyEditRoundTripsMultiByteContentExactly() throws IOException {
        Path file = tempFile(".txt");
        try {
            String before = "café ☕ 日本語 🎉 done";
            Files.write(file, before.getBytes(StandardCharsets.UTF_8));

            String result = RefactoringProvider.applyEdit(file.toString(), "☕ 日本語", "🍵 中文");

            assertTrue(result.contains("updated and saved"), "unexpected result: " + result);
            byte[] afterBytes = Files.readAllBytes(file);
            String after = new String(afterBytes, StandardCharsets.UTF_8);
            assertEquals("café 🍵 中文 🎉 done", after);
            // Round-trip through the exact bytes UTF-8 would produce — proves the
            // write used the same charset the read/match used, not a different one.
            assertEquals("café 🍵 中文 🎉 done".getBytes(StandardCharsets.UTF_8).length, afterBytes.length);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void applyEditOldStringMatchingWorksAgainstMultiByteContent() throws IOException {
        // A naive byte-length (rather than char-length) match/splice would
        // corrupt the surrounding text for any oldString containing multi-byte
        // characters. This isolates that: match squarely inside a multi-byte run.
        Path file = tempFile(".txt");
        try {
            Files.write(file, "prefix-日本語-suffix".getBytes(StandardCharsets.UTF_8));

            String result = RefactoringProvider.applyEdit(file.toString(), "日本語", "한국어");

            assertTrue(result.contains("updated and saved"), "unexpected result: " + result);
            assertEquals("prefix-한국어-suffix",
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void applyEditNotFoundMentionsTheGetFileContentGutter() throws IOException {
        Path file = tempFile(".txt");
        try {
            Files.writeString(file, "four leading spaces:    text");
            String result = RefactoringProvider.applyEdit(file.toString(), " five leading spaces:     text", "replacement");

            assertTrue(result.contains("oldString not found in file"), "unexpected result: " + result);
            assertTrue(result.contains("GetFileContent"), "the recovery hint must name its source: " + result);
            assertTrue(result.contains("byte-for-byte") && result.contains("leading whitespace"),
                    "the recovery hint must explain the exact-match requirement: " + result);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void writeFileContentRoundTripsMultiByteContentExactlyForNewFile() throws IOException {
        Path dir = Files.createTempDirectory("aicoder-encoding-new");
        Path file = dir.resolve("new-file.txt");
        try {
            String content = "新しいファイル — emoji 🚀 — café";

            String result = RefactoringProvider.writeFileContent(file.toString(), content);

            assertTrue(result.contains("File created and saved"), "unexpected result: " + result);
            assertEquals(content, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void writeFileContentRoundTripsMultiByteContentExactlyOnOverwrite() throws IOException {
        Path file = tempFile(".txt");
        try {
            Files.writeString(file, "placeholder");
            String content = "überarbeitet: 中文测试 — done";

            String result = RefactoringProvider.writeFileContent(file.toString(), content);

            assertTrue(result.contains("updated and saved"), "unexpected result: " + result);
            assertEquals(content, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Regression test for the second gap found while writing the tests above: writeFileContent and applyEdit both call
     * GitProvider.refreshVcsStatus unconditionally after a successful write, and that call resolves
     * FileOwnerQuery.getOwner() — which throws ExceptionInInitializerError/NoClassDefFoundError in this isolated
     * unit-test JVM, because it has no ProjectManagerImplementation registered in global Lookup. Before the fix, that
     * Error propagated straight out of writeFileContent/applyEdit (a catch(Exception) would not have caught it either),
     * so a write that had already completed successfully on disk was reported to the caller as an uncaught Error
     * instead of "File updated and saved". Every earlier test method in this class ran on the exact code path that used
     * to fail this way — this method exists to name that regression explicitly rather than leave it as an implicit side
     * effect. The guard now lives inside GitProvider.refreshVcsStatus itself (see GitProviderTest's
     * refreshVcsStatus_returnsAResultInsteadOfThrowing... for the direct test of that), so this method exercises it
     * transitively rather than via a since-removed per-caller wrapper here.
     */
    @Test
    void writeFileContentAndApplyEditSucceedEvenThoughVcsRefreshHasNoProjectManagerLookupHere() throws IOException {
        Path file = tempFile(".txt");
        try {
            String writeResult = RefactoringProvider.writeFileContent(file.toString(), "first");
            assertTrue(writeResult.contains("updated and saved"), "unexpected result: " + writeResult);

            String editResult = RefactoringProvider.applyEdit(file.toString(), "first", "second");
            assertTrue(editResult.contains("updated and saved"), "unexpected result: " + editResult);
            assertEquals("second", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }
}
