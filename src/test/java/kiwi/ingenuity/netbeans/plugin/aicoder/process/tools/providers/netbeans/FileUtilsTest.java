package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class FileUtilsTest {

    @Test
    void fileNotFoundMessageQuotesEveryPathValue() {
        assertEquals("File not found: \"/tmp/missing file.txt\"",
                     FileUtils.fileNotFoundMessage("/tmp/missing file.txt"));
        assertEquals("File not found: \"/tmp/missing | startLine=1\"",
                     FileUtils.fileNotFoundMessage("/tmp/missing | startLine=1"));
        assertEquals("File not found: \"\"", FileUtils.fileNotFoundMessage(null));
    }
}
