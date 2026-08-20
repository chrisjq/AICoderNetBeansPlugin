package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Session instructions must be delivered once, not once per IDE run.
 *
 * <p>
 * {@link ContextProvider} is recreated every time a session is opened, so its
 * in-memory record of what it had already sent did not survive a restart. An
 * ON_FIRST_REQUEST session therefore re-sent its instructions on the first
 * message of every run — while ON_START did not, because its guard
 * ({@code startupInstructionsInjected}) was persisted with the session. The
 * delivered text is now persisted too, so both modes behave the same way across
 * restarts.
 *
 * <p>
 * A restart is simulated by building a second {@link ContextProvider} over the
 * same {@link AiSession}: that is exactly what reopening a session does.
 */
class ContextProviderInstructionReinjectionTest {

    private static final String INSTRUCTIONS = "You are a coordinator. Never commit changes.";
    private static final String HEADING = "## Session Instructions";

    private static ContextProvider providerOver(AiSession session) {
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(session);
        return provider;
    }

    /**
     * Mirrors what AiTopComponent does once a preamble reports delivery.
     */
    private static void recordDelivered(AiSession session, String instructions) {
        session.setLastInjectedInstructions(instructions);
    }

    @Test
    void firstEverSendDeliversTheInstructions() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        ContextProvider provider = providerOver(session);

        String preamble = provider.buildPreamble("hello", INSTRUCTIONS);

        assertTrue(preamble.contains(HEADING), "a session that has never been sent its instructions must get them");
        assertTrue(provider.consumeSessionInstructionsInjected(), "and the UI notice must fire");
    }

    @Test
    void aRestartDoesNotResendTheSameInstructions() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        ContextProvider firstRun = providerOver(session);
        firstRun.buildPreamble("hello", INSTRUCTIONS);
        assertTrue(firstRun.consumeSessionInstructionsInjected());
        recordDelivered(session, INSTRUCTIONS);

        // Reopening the session builds a fresh provider with no memory of the above.
        ContextProvider secondRun = providerOver(session);
        String preamble = secondRun.buildPreamble("hello again", INSTRUCTIONS);

        assertFalse(preamble.contains(HEADING),
                "instructions were already delivered before the restart: " + preamble);
        assertFalse(secondRun.consumeSessionInstructionsInjected(),
                "and the \"Session Instructions Sent\" notice must not fire again");
    }

    @Test
    void editingTheInstructionsRedeliversThemEvenAfterARestart() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        ContextProvider firstRun = providerOver(session);
        firstRun.buildPreamble("hello", INSTRUCTIONS);
        recordDelivered(session, INSTRUCTIONS);

        // Persisting a flag rather than the text would wrongly suppress this.
        String edited = INSTRUCTIONS + " Also: prefer small commits.";
        ContextProvider secondRun = providerOver(session);
        String preamble = secondRun.buildPreamble("hello again", edited);

        assertTrue(preamble.contains(edited), "edited instructions must still be delivered: " + preamble);
        assertTrue(secondRun.consumeSessionInstructionsInjected());
    }

    @Test
    void onStartSessionsStillSuppressAfterTheirStartupDelivery() {
        // Pre-existing behaviour, guarded by a separate persisted flag — kept intact.
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        session.setSessionInstructionsDelivery(SessionInstructionsDeliveryEnum.ON_START);
        session.setStartupInstructionsInjected(true);

        String preamble = providerOver(session).buildPreamble("hello", INSTRUCTIONS);

        assertFalse(preamble.contains(HEADING),
                "ON_START already delivered these at startup: " + preamble);
    }

    @Test
    void ollamaSignalsDeliveryWithoutInjectingAndStillHonoursTheRestartGuard() {
        // Ollama pins identity/instructions into broker slots rather than the
        // preamble, so it reports delivery for the UI notice but injects nothing.
        AiSession session = AiSession.create(null, AiTypeEnum.OLLAMA_LOCAL);
        ContextProvider firstRun = providerOver(session);

        String preamble = firstRun.buildPreamble("hello", INSTRUCTIONS);
        assertFalse(preamble.contains(HEADING), "Ollama must not inject into the preamble: " + preamble);
        assertTrue(firstRun.consumeSessionInstructionsInjected(), "but delivery is still signalled");
        recordDelivered(session, INSTRUCTIONS);

        ContextProvider secondRun = providerOver(session);
        secondRun.buildPreamble("hello again", INSTRUCTIONS);
        assertFalse(secondRun.consumeSessionInstructionsInjected(),
                "the restart guard applies to the pinned-slot path too");
    }

    @Test
    void repeatedSendsWithinOneRunDeliverOnlyOnce() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        ContextProvider provider = providerOver(session);

        provider.buildPreamble("first", INSTRUCTIONS);
        assertTrue(provider.consumeSessionInstructionsInjected());
        recordDelivered(session, INSTRUCTIONS);

        provider.buildPreamble("second", INSTRUCTIONS);
        assertFalse(provider.consumeSessionInstructionsInjected(),
                "within a run the in-memory record already prevented this");
    }
}
