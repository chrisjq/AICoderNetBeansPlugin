package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorContextProviderTest {

    /**
     * The file-content header reports the exact whole-file byte count so a caller
     * whose result limit clips large reads knows precisely whether it must page
     * with startLine/endLine, rather than discovering it from a clipped read.
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
     * GetFileSizeAndMeta reports the exact byte size, line count and an encoding
     * without returning the file's content, so a caller can size a read before
     * spending tokens on it. (Encoding resolves to "unknown" outside the running
     * IDE, where the path is not a registered FileObject — the byte/line facts
     * are what this asserts.)
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
        } finally {
            Files.walk(tempRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignore) {} });
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
        } finally {
            Files.walk(tempRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignore) {} });
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
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void getFileContent_missingFile_firstLineUnchanged() {
        String result = EditorContextProvider.getFileContent("/no/such/aicoder/Missing.java", 0, 0);
        assertTrue(result.startsWith("File not found: /no/such/aicoder/Missing.java"),
                "not-found first line must be preserved: " + result);
    }
}
