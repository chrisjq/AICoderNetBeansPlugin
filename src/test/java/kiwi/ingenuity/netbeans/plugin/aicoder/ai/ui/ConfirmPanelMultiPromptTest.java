package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * The multi-file confirm prompt is pure so its wording can be pinned without a running IDE, matching
 * {@code ConfirmPanelPromptTest}'s treatment of the single-file one.
 */
class ConfirmPanelMultiPromptTest {

    /**
     * Every file is named individually. The plan requires this rather than a count: "Codex wants to modify 3 files" is
     * exactly the blind approval this feature exists to replace, and a user cannot decide on a number.
     */
    @Test
    void namesEveryFileIndividually() {
        assertEquals("""
                     Allow MultiEdit: 3 files
                       proj/A.java
                       proj/B.java
                       proj/C.java""",
                ConfirmPanel.buildMultiConfirmPrompt(List.of("proj/A.java", "proj/B.java", "proj/C.java")));
    }

    /**
     * The order is the one the AI supplied — it reflects how the model sequenced its own work, which is the order that
     * reads coherently when reviewing. The paths here are non-alphabetical so any sorting would show.
     */
    @Test
    void keepsTheSuppliedOrder() {
        assertEquals("""
                     Allow MultiEdit: 3 files
                       proj/C.java
                       proj/A.java
                       proj/B.java""",
                ConfirmPanel.buildMultiConfirmPrompt(List.of("proj/C.java", "proj/A.java", "proj/B.java")));
    }

    @Test
    void singularForOneFile() {
        assertEquals("""
                     Allow MultiEdit: 1 file
                       proj/A.java""",
                ConfirmPanel.buildMultiConfirmPrompt(List.of("proj/A.java")));
    }
}
