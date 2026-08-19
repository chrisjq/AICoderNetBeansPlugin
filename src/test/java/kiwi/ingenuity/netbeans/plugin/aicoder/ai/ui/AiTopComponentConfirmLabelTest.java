package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

/**
 * Covers the label shown in confirm notifications
 * ({@code "<tool>: <label> — accepted"}).
 *
 * <p>
 * Regression origin: a live run printed
 * <b>{@code "Execute: null — auto-accepted"}</b>. The approval branching was
 * correct — a shell command really does carry no file path — but the label was
 * built solely from that path, so the missing one was rendered as the literal
 * string "null" and the user was told nothing about what had just been approved
 * on their behalf.
 */
class AiTopComponentConfirmLabelTest {

    // ---- non-file confirms (shell commands, unidentified actions) ----
    @Test
    void nullPathFallsBackToDisplayTextRatherThanPrintingNull() {
        assertEquals("echo hello-from-opencode",
                AiTopComponent.buildConfirmLabel(null, null, "echo hello-from-opencode"),
                "a shell command has no path; the command itself is the subject");
    }

    @Test
    void blankPathAlsoFallsBackToDisplayText() {
        assertEquals("echo hi", AiTopComponent.buildConfirmLabel("   ", null, "echo hi"));
    }

    @Test
    void labelNeverRendersTheLiteralStringNull() {
        String label = AiTopComponent.buildConfirmLabel(null, null, "some command");
        assertFalse(label.contains("null"), "the user must never be shown \"null\": " + label);
    }

    @Test
    void noPathAndNoDisplayTextStillProducesSomethingReadable() {
        assertEquals("(no details)", AiTopComponent.buildConfirmLabel(null, null, null));
        assertEquals("(no details)", AiTopComponent.buildConfirmLabel(null, null, "  "));
    }

    // ---- file confirms are unchanged ----
    @Test
    void sourceOnlyConfirmShowsThePath() {
        assertEquals("MyProject/docs/notes.md",
                AiTopComponent.buildConfirmLabel("MyProject/docs/notes.md", null, "Permanently delete it?"));
    }

    @Test
    void sourceAndTargetConfirmShowsBothPaths() {
        assertEquals("MyProject/docs/notes.md → MyProject/target",
                AiTopComponent.buildConfirmLabel("MyProject/docs/notes.md", "MyProject/target",
                        "Copy notes.md to target?"),
                "copy/move prompts render source → destination");
    }

    @Test
    void displayTextIsIgnoredWhenAPathIsPresent() {
        // Delete/copy/move display text is a full question ("Permanently delete X?").
        // Substituting it for the path here would read badly in "<tool>: <label> — accepted".
        assertEquals("MyProject/a.txt",
                AiTopComponent.buildConfirmLabel("MyProject/a.txt", null, "Permanently delete MyProject/a.txt?"));
    }
}
