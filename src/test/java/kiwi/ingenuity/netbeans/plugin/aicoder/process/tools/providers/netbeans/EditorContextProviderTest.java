package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

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
}
