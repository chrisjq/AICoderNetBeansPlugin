package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The no-image-input decision lasts for the generation it was made in.
 */
class SessionDecisionsImageTest {

    @Test
    @Timeout(5)
    void theDecisionIsRecordedOnceAndLapsesWithANewGeneration() {
        SessionDecisions decisions = new SessionDecisions("g1");
        assertFalse(decisions.noImageInput());

        assertTrue(decisions.recordNoImageInput());
        assertFalse(decisions.recordNoImageInput(), "the second record is not news");
        assertTrue(decisions.noImageInput());

        decisions.generation("g2");
        assertFalse(decisions.noImageInput(), "another model may take images");
    }

    @Test
    @Timeout(5)
    void aRefusalFromAnOlderGenerationIsIgnored() {
        SessionDecisions decisions = new SessionDecisions("old-model");
        String turnGeneration = decisions.generation();
        // The model changed while the image request was in flight.
        decisions.generation("new-model");

        assertFalse(decisions.recordNoImageInput(turnGeneration), "the old server's refusal is not recorded");
        assertFalse(decisions.noImageInput(), "the new model is still tried");

        assertTrue(decisions.recordNoImageInput("new-model"), "a refusal for the current generation is recorded");
        assertTrue(decisions.noImageInput());
        assertFalse(decisions.recordNoImageInput(null));
    }

    @Test
    @Timeout(5)
    void resetClearsTheDecision() {
        SessionDecisions decisions = new SessionDecisions("g1");
        decisions.recordNoImageInput();

        decisions.reset();

        assertFalse(decisions.noImageInput());
    }
}
