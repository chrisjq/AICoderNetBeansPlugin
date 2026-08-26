package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * The confirm panel is the approval point, so its question must name the VERB being approved.
 * <p>
 * It previously rendered {@code event.displayText()} alone. For a shell confirm that is the bare command, so the user
 * was asked to approve "opencode --help 2>&1" with no indication of whether the AI wanted to RUN it or read a file of
 * that name — while the outcome line that followed said "Execute: opencode --help 2>&1 — accepted". The answer named
 * the tool and the question did not.
 */
class ConfirmPanelPromptTest {

    @Test
    void shellCommandPromptNamesTheToolSoTheVerbIsVisible() {
        assertEquals("Allow Execute: opencode --help 2>&1",
                ConfirmPanel.buildConfirmPrompt("Execute", "opencode --help 2>&1"));
    }

    @Test
    void promptMatchesTheToolNamingOfTheOutcomeLine() {
        // NotificationUtil.toolNameLabel is what formats the accepted/rejected lines, so the
        // question and its answer cannot drift into two different shapes.
        assertEquals("Allow Delete: Permanently delete MyProject/a.txt?",
                ConfirmPanel.buildConfirmPrompt("Delete", "Permanently delete MyProject/a.txt?"));
    }

    @Test
    void missingToolNameStillProducesAQuestionRatherThanADanglingPrefix() {
        // The colon survives even with no tool name, so the prefix stays visibly separate from
        // the command instead of reading as one run-on phrase.
        assertEquals("Allow: rm -rf /tmp/scratch",
                ConfirmPanel.buildConfirmPrompt(null, "rm -rf /tmp/scratch"));
        assertEquals("Allow: rm -rf /tmp/scratch",
                ConfirmPanel.buildConfirmPrompt("", "rm -rf /tmp/scratch"));
        assertEquals("Allow: rm -rf /tmp/scratch",
                ConfirmPanel.buildConfirmPrompt("   ", "rm -rf /tmp/scratch"));
    }

    @Test
    void absentDetailsNeverRenderAsNull() {
        // A previous bug in this area printed the literal string "null" to the user
        // ("Execute: null — auto-accepted"). Approving a prompt that says "null" is worse than
        // approving one that admits it has no details.
        assertEquals("Allow Execute: (no details)",
                ConfirmPanel.buildConfirmPrompt("Execute", null));
        assertEquals("Allow Execute: (no details)",
                ConfirmPanel.buildConfirmPrompt("Execute", "   "));
    }

    @Test
    void surroundingWhitespaceIsTrimmedSoThePrefixSitsAgainstTheText() {
        assertEquals("Allow Execute: echo hi",
                ConfirmPanel.buildConfirmPrompt("Execute", "  echo hi  "));
    }
}
