package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * A send dropped by Stop before the backend had it must not leave the provider believing the baseline and the
 * instructions went: the next preamble has to carry them again.
 */
class ContextProviderSentContextTest {

    private static final String BASELINE_MARKER = "AI Coder NetBeans Plugin v";

    private static ContextProvider provider() {
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(AiSession.create(null, AiTypeEnum.CLAUDE));
        return provider;
    }

    @Test
    void aPreambleThatWasNeverDeliveredOffersItsBaselineAndInstructionsAgain() {
        ContextProvider provider = provider();

        ContextProvider.SentContext before = provider.captureSentContext();
        String dropped = provider.buildPreamble("first", "the instructions");
        assertTrue(dropped.contains(BASELINE_MARKER));
        assertTrue(provider.consumeSessionInstructionsInjected());

        provider.restoreSentContext(before);
        String retried = provider.buildPreamble("first again", "the instructions");

        assertTrue(retried.contains(BASELINE_MARKER), "the baseline never reached the model");
        assertTrue(retried.contains("the instructions"), "the instructions never reached the model");
        assertTrue(provider.consumeSessionInstructionsInjected());
    }

    @Test
    void aDeliveredPreambleIsNotOfferedAgain() {
        ContextProvider provider = provider();

        provider.captureSentContext();
        provider.buildPreamble("first", "the instructions");
        String next = provider.buildPreamble("second", "the instructions");

        assertFalse(next.contains(BASELINE_MARKER));
        assertFalse(next.contains("the instructions"));
    }
}
