package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ToolResultCapTest {

    private static final String FILE_NOTE_SUFFIX
            = " characters — read the rest with startLine/endLine or FilterFileContent]";
    private static final String SEARCH_NOTE_SUFFIX = " characters — narrow the search]";

    @Test
    @Timeout(5)
    void shortResultIsUnchanged() {
        assertEquals("abc", ToolResultCap.cap("GetFileContent", "abc", 10));
    }

    @Test
    @Timeout(5)
    void resultExactlyAtTheLimitIsUnchanged() {
        assertEquals("abcde", ToolResultCap.cap("GetFileContent", "abcde", 5));
    }

    @Test
    @Timeout(5)
    void fileReadsGetTheReadTheRestNote() {
        for (String tool : new String[]{"GetFileContent", "GetCurrentFileContent", "FilterFileContent"}) {
            assertEquals("xxxxxxxxxx\n[truncated at 10" + FILE_NOTE_SUFFIX, ToolResultCap.cap(tool, "x".repeat(20), 10),
                         tool);
        }
    }

    @Test
    @Timeout(5)
    void searchesGetTheNarrowTheSearchNote() {
        for (String tool : new String[]{"SearchInFiles", "SearchSymbols", "SearchTypes", "FindUsages", "FindFile"}) {
            assertEquals("xxxxxxxxxx\n[truncated at 10" + SEARCH_NOTE_SUFFIX, ToolResultCap.cap(tool, "x".repeat(20), 10),
                         tool);
        }
    }

    @Test
    @Timeout(5)
    void otherToolsGetTheBareNote() {
        assertEquals("xxxxxxxxxx\n[truncated at 10 characters]",
                     ToolResultCap.cap("GitLog", "x".repeat(20), 10));
    }

    @Test
    @Timeout(5)
    void aSurrogatePairSplitAtTheCutIsDroppedWhole() {
        String pair = new String(Character.toChars(0x1F600));

        assertEquals("a\n[truncated at 1 characters]",
                     ToolResultCap.cap("GitLog", "a" + pair, 2));
    }

    @Test
    @Timeout(5)
    void aSurrogatePairEndingExactlyAtTheCutIsKept() {
        String pair = new String(Character.toChars(0x1F600));

        assertEquals(pair + "\n[truncated at 2 characters]",
                     ToolResultCap.cap("GitLog", pair + "a", 2));
    }
}
