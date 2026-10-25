package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class SummaryTemplateTest {

    private final SummaryTemplate template = new SummaryTemplate();

    private static String completeSummary() {
        StringBuilder sb = new StringBuilder();
        for (String heading : SummaryTemplate.HEADINGS) {
            sb.append("## ").append(heading).append("\nsome text\n\n");
        }
        return sb.toString();
    }

    @Test
    void freshPromptNamesEverySectionAndCarriesTheTranscript() {
        String prompt = template.freshPrompt("[User] fix the build");

        for (String heading : SummaryTemplate.HEADINGS) {
            assertTrue(prompt.contains("## " + heading), heading);
        }
        assertTrue(prompt.contains("[User] fix the build"));
    }

    @Test
    void updatePromptCarriesThePreviousSummaryAndTheNewTranscript() {
        String prompt = template.updatePrompt("## Goal\nship it", "[User] now test");

        assertTrue(prompt.contains("## Goal\nship it"));
        assertTrue(prompt.contains("[User] now test"));
        for (String heading : SummaryTemplate.HEADINGS) {
            assertTrue(prompt.contains("## " + heading), heading);
        }
    }

    @Test
    void aCompleteSummaryIsValid() {
        assertTrue(template.isValid(completeSummary()));
    }

    @Test
    void aSummaryMissingAHeadingIsRejected() {
        String missing = completeSummary().replace("## Relevant files", "## Files");

        assertFalse(template.isValid(missing));
    }

    @Test
    void headingsMayCarryMarkupAndDifferentCase() {
        String decorated = completeSummary()
                .replace("## Goal", "**goal:**")
                .replace("## Next steps", "- NEXT STEPS");

        assertTrue(template.isValid(decorated));
    }

    @Test
    void nullAndEmptySummariesAreRejected() {
        assertFalse(template.isValid(null));
        assertFalse(template.isValid(""));
    }

    @Test
    void repairNudgeNamesEveryRequiredHeading() {
        String nudge = template.repairNudge();

        for (String heading : SummaryTemplate.HEADINGS) {
            assertTrue(nudge.contains("## " + heading), heading);
        }
    }
}
