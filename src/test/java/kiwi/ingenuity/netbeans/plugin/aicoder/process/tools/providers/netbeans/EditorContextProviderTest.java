package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class EditorContextProviderTest {

    /**
     * The file-content header reports the exact whole-file byte count so a caller whose result limit clips large reads
     * knows precisely whether it must page with startLine/endLine, rather than discovering it from a clipped read.
     */
    @Test
    void headerReportsExactByteCount() throws IOException {
        Path file = Files.createTempFile("aicoder-getfilecontent", ".txt");
        try {
            byte[] content = "line one\nline two\nline three\n".getBytes(StandardCharsets.UTF_8);
            Files.write(file, content);

            String out = EditorContextProvider.getFileContent(file.toString(), 0, 0);

            assertTrue(out.contains(content.length + " bytes"),
                    "header must state the exact byte count (" + content.length + "): " + out);
            assertTrue(out.contains("of 3, "), "header must still report the line count: " + out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * GetFileSizeAndMeta reports the exact byte size, line count and an encoding without returning the file's content,
     * so a caller can size a read before spending tokens on it. (Encoding resolves to "unknown" outside the running
     * IDE, where the path is not a registered FileObject — the byte/line facts are what this asserts.)
     */
    @Test
    void fileSizeAndMetaReportsBytesLinesAndEncoding() throws IOException {
        Path file = Files.createTempFile("aicoder-getfilesize", ".txt");
        try {
            byte[] content = "line one\nline two\nline three\n".getBytes(StandardCharsets.UTF_8);
            Files.write(file, content);

            String out = EditorContextProvider.getFileSizeAndMeta(file.toString());

            assertTrue(out.contains(content.length + " bytes"),
                    "must state the exact byte count (" + content.length + "): " + out);
            assertTrue(out.contains("3 lines"), "must report the line count: " + out);
            assertTrue(out.contains("encoding "), "must report an encoding: " + out);
            assertTrue(out.contains("modified ") && out.contains("s ago)"),
                    "must report the last-modified time and age in seconds: " + out);
            assertTrue(out.contains("writable") || out.contains("read-only"),
                    "must report the writable flag: " + out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fileSizeAndMetaReportsMissingFile() {
        String out = EditorContextProvider.getFileSizeAndMeta("/no/such/aicoder/file.txt");
        assertTrue(out.startsWith("File not found:"), "missing file must be reported: " + out);
    }

    // ---- Bug fix: GetFileContent miss self-correction ----
    @Test
    void buildNotFoundMessage_nameMatchFound_returnsDidYouMean() throws IOException {
        Path tempRoot = Files.createTempDirectory("aicoder-fnf-match");
        try {
            Path realFile = Files.createDirectories(tempRoot.resolve("ai/http/context"))
                    .resolve("PinSlotEnum.java");
            Files.writeString(realFile, "// placeholder");

            String result = EditorContextProvider.buildNotFoundMessage(
                    "/wrong/process/broker/PinSlotEnum.java",
                    List.of(tempRoot.toFile()));

            assertTrue(result.startsWith("File not found: /wrong/process/broker/PinSlotEnum.java"),
                    "first line must be unchanged: " + result);
            assertTrue(result.contains("Did you mean:"), "must contain Did you mean section: " + result);
            assertTrue(result.contains(realFile.toString()), "must list the real path: " + result);
            assertFalse(result.contains("GetProjectStructure"), "must not show fallback hint when match found: " + result);
        }
        finally {
            Files.walk(tempRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        }
                        catch (IOException ignore) {
                        }
                    });
        }
    }

    @Test
    void buildNotFoundMessage_nameMatchNotFound_returnsHint() throws IOException {
        Path tempRoot = Files.createTempDirectory("aicoder-fnf-nomatch");
        try {
            Files.createDirectories(tempRoot.resolve("ai/http/context"));

            String result = EditorContextProvider.buildNotFoundMessage(
                    "/wrong/NoSuchClass.java",
                    List.of(tempRoot.toFile()));

            assertTrue(result.startsWith("File not found: /wrong/NoSuchClass.java"),
                    "first line must be unchanged: " + result);
            assertFalse(result.contains("Did you mean:"), "must not show match list when none found: " + result);
            assertTrue(result.contains("GetProjectStructure"), "must show GetProjectStructure hint: " + result);
        }
        finally {
            Files.walk(tempRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        }
                        catch (IOException ignore) {
                        }
                    });
        }
    }

    @Test
    void getFileContent_existingFile_noSuggestions() throws IOException {
        Path file = Files.createTempFile("aicoder-fnf-guard", ".java");
        try {
            Files.writeString(file, "class Guard {}");
            String result = EditorContextProvider.getFileContent(file.toString(), 0, 0);
            assertTrue(result.startsWith("File: "), "existing file must return content: " + result);
            assertFalse(result.contains("Did you mean:"), "no suggestion for successful read: " + result);
            assertFalse(result.contains("GetProjectStructure"), "no hint for successful read: " + result);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void getFileContent_missingFile_firstLineUnchanged() {
        String result = EditorContextProvider.getFileContent("/no/such/aicoder/Missing.java", 0, 0);
        assertTrue(result.startsWith("File not found: /no/such/aicoder/Missing.java"),
                "not-found first line must be preserved: " + result);
    }

    // ---- FilterFileContent ----
    @Test
    void filterFileContent_literalMatch_findsExactLines() throws IOException {
        Path file = Files.createTempFile("aicoder-filter-literal", ".log");
        try {
            Files.writeString(file, "BUILD SUCCESS\nsome noise\nBUILD FAILED\nmore noise\n");

            String out = EditorContextProvider.filterFileContent(file.toString(), "BUILD", false, false, 0, 0);

            assertTrue(out.startsWith("Found 2 match(es) in " + file), "header must report the true total: " + out);
            assertTrue(out.contains("1: BUILD SUCCESS"), "must show the matched line with its number: " + out);
            assertTrue(out.contains("3: BUILD FAILED"), "must show the second matched line: " + out);
            assertFalse(out.contains("some noise"), "non-matching lines must not appear without context: " + out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * The specific behaviour Pattern.quote provides (via LineMatcher, shared with SearchInFilesTool) and the one most
     * likely to be lost if someone later "simplifies" the compilation: a literal query must never be interpreted as a
     * regex.
     */
    @Test
    void filterFileContent_literalMode_treatsRegexMetacharactersLiterally() throws IOException {
        Path file = Files.createTempFile("aicoder-filter-literal-dot", ".log");
        try {
            Files.writeString(file, "a.b\naxb\n");

            String out = EditorContextProvider.filterFileContent(file.toString(), "a.b", false, false, 0, 0);

            assertTrue(out.startsWith("Found 1 match(es)"), "'.' must not act as a wildcard in literal mode: " + out);
            assertTrue(out.contains("1: a.b"));
            assertFalse(out.contains("axb"), "the literal dot must not match an arbitrary character: " + out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void filterFileContent_regexMatch_findsPatternLines() throws IOException {
        Path file = Files.createTempFile("aicoder-filter-regex", ".log");
        try {
            Files.writeString(file, "error: something broke\nall good\nERROR: something else broke\n");

            String out = EditorContextProvider.filterFileContent(file.toString(), "^error:.*", true, false, 0, 0);

            assertTrue(out.startsWith("Found 2 match(es)"), "regex must match both case variants case-insensitively: " + out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void filterFileContent_caseSensitive_excludesDifferentCase() throws IOException {
        Path file = Files.createTempFile("aicoder-filter-case", ".log");
        try {
            Files.writeString(file, "Warning: low disk\nWARNING: low memory\n");

            String out = EditorContextProvider.filterFileContent(file.toString(), "Warning", false, true, 0, 0);

            assertTrue(out.startsWith("Found 1 match(es)"), "case-sensitive search must exclude the differently-cased line: " + out);
            assertTrue(out.contains("1: Warning: low disk"));
            assertFalse(out.contains("WARNING: low memory"));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void filterFileContent_noMatch_reportsNoMatches() throws IOException {
        Path file = Files.createTempFile("aicoder-filter-nomatch", ".log");
        try {
            Files.writeString(file, "nothing interesting here\n");

            String out = EditorContextProvider.filterFileContent(file.toString(), "mvnw", false, false, 0, 0);

            assertEquals("No matches found for: mvnw", out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void filterFileContent_missingFile_reportsNotFound() {
        String out = EditorContextProvider.filterFileContent("/no/such/aicoder/build.log", "error", false, false, 0, 0);
        assertTrue(out.startsWith("File not found: /no/such/aicoder/build.log"), out);
    }

    /**
     * Silent truncation is the failure mode to avoid: the header must report the true total (5) even though only the
     * first 2 are shown.
     */
    @Test
    void filterFileContent_capsMatchesButReportsTrueTotal() throws IOException {
        Path file = Files.createTempFile("aicoder-filter-cap", ".log");
        try {
            Files.writeString(file, "hit\nhit\nhit\nhit\nhit\n");

            String out = EditorContextProvider.filterFileContent(file.toString(), "hit", false, false, 0, 2);

            assertTrue(out.startsWith("Found 5 match(es) in " + file + " (showing first 2)"), out);
            assertTrue(out.contains("1: hit"));
            assertTrue(out.contains("2: hit"));
            assertFalse(out.contains("3: hit"), "must not show matches past the cap: " + out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void filterFileContent_contextLines_includesSurroundingLinesMarkedDifferently() throws IOException {
        Path file = Files.createTempFile("aicoder-filter-context", ".log");
        try {
            Files.writeString(file, "before\nMATCH\nafter\n");

            String out = EditorContextProvider.filterFileContent(file.toString(), "MATCH", false, false, 1, 0);

            assertTrue(out.contains("1- before"), "context line must use the '-' marker: " + out);
            assertTrue(out.contains("2: MATCH"), "matched line must use the ':' marker: " + out);
            assertTrue(out.contains("3- after"), "trailing context line must be included: " + out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * resolveCharset falls back to UTF-8 outside a running IDE (no FileObject to query — see
     * RefactoringProvider.resolveCharset), so this cannot exercise "decodes a non-UTF-8 file correctly" in a plain unit
     * test; that path only runs live, inside NetBeans, against a registered project's encoding settings. What IS
     * testable here, and what this pins: bytes that are not valid UTF-8 must fail safely with a controlled message,
     * never an uncaught exception or a corrupted/silent result.
     */
    @Test
    void filterFileContent_nonUtf8Bytes_failsSafelyRatherThanThrowing() throws IOException {
        Path file = Files.createTempFile("aicoder-filter-latin1", ".log");
        try {
            // 0xE9 ('é' in Latin-1) is a UTF-8 lead byte for a 3-byte sequence with no
            // continuation bytes following — malformed under strict UTF-8 decoding.
            Files.write(file, new byte[]{'c', 'a', 'f', (byte) 0xE9, '\n'});

            String out = EditorContextProvider.filterFileContent(file.toString(), "caf", false, false, 0, 0);

            assertTrue(out.startsWith("Error reading file:"), "malformed input must fail safely, not throw: " + out);
        }
        finally {
            Files.deleteIfExists(file);
        }
    }
}
