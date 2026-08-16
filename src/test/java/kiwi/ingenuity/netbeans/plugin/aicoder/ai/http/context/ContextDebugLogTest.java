package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ContextDebugLogTest {

    @Test
    void shortContentIsPassedThroughUnchanged() {
        assertEquals("hello", ContextDebugLog.truncate("hello"));
    }

    @Test
    void longContentIsCutAtOneHundredCharsWithFullLengthAppended() {
        String out = ContextDebugLog.truncate("x".repeat(250));
        assertTrue(out.startsWith("x".repeat(100)));
        assertTrue(out.contains("250 chars"), "the real size must survive truncation");
        assertTrue(out.length() < 150);
    }

    @Test
    void newlinesAreEscapedSoOneEntryStaysOneLine() {
        String out = ContextDebugLog.truncate("line one\nline two");
        assertFalse(out.contains("\n"));
        assertTrue(out.contains("\\n"));
    }

    @Test
    void nullContentIsRendered() {
        assertEquals("<null>", ContextDebugLog.truncate(null));
    }
}
