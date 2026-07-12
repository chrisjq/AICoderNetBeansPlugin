package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class StatusMessageUtilTest {

    @Test
    void formatExited_emptyStderr_omitsColon() {
        assertEquals("AI exited (code -1)", StatusMessageUtil.formatExited("AI", -1, List.of()));
    }

    @Test
    void formatExited_nullStderr_omitsColon() {
        assertEquals("AI exited (code 1)", StatusMessageUtil.formatExited("AI", 1, null));
    }

    @Test
    void formatExited_blankStderrLines_omitsColon() {
        assertEquals("AI exited (code -1)", StatusMessageUtil.formatExited("AI", -1, List.of("   ", "")));
    }

    @Test
    void formatExited_withStderr_appendsJoinedLines() {
        assertEquals("Grok exited (code 2): error one\nerror two",
                StatusMessageUtil.formatExited("Grok", 2, List.of("error one", "error two")));
    }
}
