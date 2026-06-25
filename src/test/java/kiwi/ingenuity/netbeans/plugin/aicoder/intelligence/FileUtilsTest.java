package kiwi.ingenuity.netbeans.plugin.aicoder.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FileUtils;

class FileUtilsTest {

    @Test
    void resolveByPathReturnsNullForNull() {
        assertNull(FileUtils.resolveByPath(null));
    }

    @Test
    void resolveByPathReturnsNullForBlank() {
        assertNull(FileUtils.resolveByPath("   "));
    }

    @Test
    void resolveByPathReturnsNullForMissingFile() {
        assertNull(FileUtils.resolveByPath("/nonexistent/path/to/Foo.java"));
    }

    @Test
    void locateSourceFileReturnsNullForUnknownClass() {
        assertNull(FileUtils.locateSourceFile("com.example.DoesNotExist"));
    }
}
