package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ToolResultCapTest {

    @Test
    void aResultWithinTheCapIsReturnedUnchanged() {
        assertEquals("abc", ToolResultCap.cap(McpToolEnum.GET_FILE_CONTENT.toolName(), "abc", 10));
    }

    @Test
    void aFileReadNotesTheStartLineAndEndLineWayOut() {
        String out = ToolResultCap.cap(McpToolEnum.GET_FILE_CONTENT.toolName(), "x".repeat(20), 5);

        assertTrue(out.startsWith("xxxxx\n"), out);
        assertTrue(out.contains("startLine/endLine"), out);
    }

    @Test
    void aSearchNotesThatTheSearchShouldBeNarrowed() {
        String out = ToolResultCap.cap(McpToolEnum.SEARCH_IN_FILES.toolName(), "x".repeat(20), 5);

        assertTrue(out.contains("narrow the search"), out);
    }

    @Test
    void aBinaryFileToolGetsNoFileReadAdvice() {
        String out = ToolResultCap.cap(McpToolEnum.GET_FILE_BINARY.toolName(), "x".repeat(20), 5);

        assertEquals("xxxxx\n[truncated at 5 characters]", out);
    }
}
