package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ContextProviderPreambleTest {

    private static final String CREDENTIAL_IMPORTANT
            = "IMPORTANT: When a tool takes sessionId/secretKey, pass the sessionId and secretKey shown above verbatim";

    /**
     * The inter-AI blurb sits immediately before the user's own text, so its
     * final clause carries a lot of weight. Under SOFTEN_TOOL_DIRECTIVES it
     * must lead with the condition rather than end on "do it immediately".
     */
    /**
     * Inter-AI comms is settings-derived, so enable it rather than relying on
     * the default.
     */
    private static ContextProvider providerFor(AiTypeEnum type) {
        AiSession session = AiSession.create(null, type);
        session.settings().setAllowInterAiComms(Boolean.TRUE);
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(session);
        return provider;
    }

    @Test
    void preambleCredentialGatingMatchesFlag() {
        AiSession session = AiSession.create(null, AiTypeEnum.OLLAMA_LOCAL);

        ContextProvider withoutCreds = new ContextProvider(fo -> {
        });
        withoutCreds.setSession(session);
        String noCreds = withoutCreds.buildPreamble("prompt", null);
        assertFalse(noCreds.contains("secretKey:"));
        assertFalse(noCreds.contains(CREDENTIAL_IMPORTANT));

        session = AiSession.create(null, AiTypeEnum.CLAUDE);

        ContextProvider withCreds = new ContextProvider(fo -> {
        });
        withCreds.setSession(session);
        String yesCreds = withCreds.buildPreamble("prompt", null);
        assertTrue(yesCreds.contains("secretKey:"));
        assertTrue(yesCreds.contains(CREDENTIAL_IMPORTANT));
    }

    @Test
    void interAiBlurbIsConditionalForSoftenedTypes() {
        String identity = providerFor(AiTypeEnum.OLLAMA_LOCAL).buildIdentityBlock();

        assertFalse(identity.contains("first action"),
                "an unconditional 'first action is to call X' triggers a tool call on 'hi'");
        assertFalse(identity.contains("without hedging"));
        assertTrue(identity.contains("Only if the user asks you to message"),
                "the inter-AI instruction must lead with its condition");
    }

    /**
     * A rendered "description: null" is an invitation to fill it in — the model
     * called UpdateSessionDescription in response to "hi". Blank fields must be
     * omitted, not printed as the literal string "null".
     */
    @Test
    void blankIdentityFieldsAreOmittedRatherThanRenderedAsNull() {
        for (AiTypeEnum type : new AiTypeEnum[]{AiTypeEnum.OLLAMA_LOCAL, AiTypeEnum.CLAUDE}) {
            String preamble = providerFor(type).buildPreamble("hi", null);
            assertFalse(preamble.contains(": null"),
                    type + " identity block must not render a null field");
            assertFalse(preamble.contains("description: null"), type + " description leaked as null");
        }
    }

    @Test
    void populatedDescriptionIsStillShown() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        session.setDescription("reviewing the MCP options work");
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(session);

        assertTrue(provider.buildPreamble("hi", null)
                .contains("description: reviewing the MCP options work"));
    }

    /**
     * The blurb sits immediately before every user message. Ending it on a
     * blanket "without calling any tool" made the model refuse to read files,
     * replying that it had no access — the negative must name only the two
     * inter-AI tools it is scoping.
     */
    @Test
    void softenedBlurbDoesNotForbidToolsInGeneral() {
        String identity = providerFor(AiTypeEnum.OLLAMA_LOCAL).buildIdentityBlock();

        assertFalse(identity.contains("without calling any tool"),
                "a blanket prohibition suppresses legitimate tool use");
        assertTrue(identity.contains("do not call those two tools for any other reason"),
                "the restriction must name the inter-AI tools");
        assertTrue(identity.contains("Use the other tools freely"));
    }

    /**
     * Ollama builds its message list fresh each turn, so context sent only on
     * the first turn is gone by the second. Asked to read pom.xml on a later
     * turn the model invented "/path/to/pom.xml", never having been told where
     * the project was.
     */
    @Test
    void ollamaNoLongerRepeatsTheProjectBaselineBecauseItIsPinnedInstead() {
        ContextProvider p = providerFor(AiTypeEnum.OLLAMA_LOCAL);
        p.buildPreamble("first", null);
        String second = p.buildPreamble("second", null);

        assertFalse(second.contains("AI Coder NetBeans Plugin v"),
                "the baseline is now a pinned broker slot, not part of every user message");
        assertEquals("second", second.trim(),
                "buildPreamble returns the raw user text for broker-backed backends");
    }

    @Test
    void statefulTypesStillGetDeltasOnly() {
        ContextProvider provider = providerFor(AiTypeEnum.CLAUDE);
        provider.buildPreamble("first", null);
        String second = provider.buildPreamble("second", null);

        assertFalse(second.contains("AI Coder NetBeans Plugin v"),
                "Claude remembers turn one, so repeating the baseline is wasted context");
    }

    @Test
    void pendingStartupDeliveryInjectsOnFirstUserRequest() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        session.setSessionInstructionsDelivery(SessionInstructionsDeliveryEnum.ON_START);
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(session);

        assertTrue(provider.buildPreamble("prompt", "startup-only instruction")
                .contains("## Session Instructions"));
        assertTrue(provider.consumeSessionInstructionsInjected());
    }

    @Test
    void completedStartupDeliveryIsNotRepeatedOnFirstUserRequest() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        session.setSessionInstructionsDelivery(SessionInstructionsDeliveryEnum.ON_START);
        session.setStartupInstructionsInjected(true);
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(session);

        assertFalse(provider.buildPreamble("prompt", "startup-only instruction")
                .contains("## Session Instructions"));
    }

    @Test
    void completedStartupDeliveryIsNotRepeatedOnLaterRequests() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        session.setSessionInstructionsDelivery(SessionInstructionsDeliveryEnum.ON_START);
        session.setStartupInstructionsInjected(true);
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(session);

        provider.buildPreamble("first", "startup-only instruction");

        assertFalse(provider.buildPreamble("second", "startup-only instruction")
                .contains("## Session Instructions"),
                "the ON_START guard must hold on every send, not only the first");
    }

    /**
     * Reopening a session resumes from saved history, which calls
     * resetSentContext() — clearing both the context baseline and the memory of
     * what was last injected. The ON_START guard used to be prefixed with
     * `!isFirstSend ||`, so from the second send after a reopen it
     * short-circuited to true and the "instructions changed" test compared
     * against a nulled lastInjectedSessionInstructions, re-delivering
     * instructions the session had already received at startup.
     */
    @Test
    void completedStartupDeliveryIsNotRepeatedAfterAReopen() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        session.setSessionInstructionsDelivery(SessionInstructionsDeliveryEnum.ON_START);
        session.setStartupInstructionsInjected(true);
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(session);
        provider.resetSentContext();

        assertFalse(provider.buildPreamble("first after reopen", "startup-only instruction")
                .contains("## Session Instructions"));
        assertFalse(provider.buildPreamble("second after reopen", "startup-only instruction")
                .contains("## Session Instructions"),
                "the second send after a reopen is where the re-injection used to appear");
        assertFalse(provider.consumeSessionInstructionsInjected(),
                "no injection means no 'Special Instructions Sent' marker either");
    }

    @Test
    void changedInstructionsStillInjectAfterTheFirstRequest() {
        ContextProvider provider = providerFor(AiTypeEnum.CLAUDE);
        provider.buildPreamble("first", "initial instruction");

        assertTrue(provider.buildPreamble("second", "updated instruction")
                .contains("updated instruction"));
    }

    @Test
    void instructionDeliverySignalReportsOnlyActualInjections() {
        ContextProvider provider = providerFor(AiTypeEnum.CLAUDE);

        provider.buildPreamble("first", "instruction");
        assertTrue(provider.consumeSessionInstructionsInjected());
        assertFalse(provider.consumeSessionInstructionsInjected());

        provider.buildPreamble("second", "instruction");
        assertFalse(provider.consumeSessionInstructionsInjected());

        provider.buildPreamble("third", "changed instruction");
        assertTrue(provider.consumeSessionInstructionsInjected());
    }

    @Test
    void interAiBlurbKeepsStrongWordingForOtherTypes() {
        String preamble = providerFor(AiTypeEnum.CLAUDE).buildPreamble("hi", null);

        assertTrue(preamble.contains("first action"));
        assertTrue(preamble.contains("without hedging"));
    }

    @Test
    void identityBlockIsAvailableSeparatelyFromThePrompt() {
        ContextProvider p = providerFor(AiTypeEnum.OLLAMA_LOCAL);
        String identity = p.buildIdentityBlock();

        assertTrue(identity.contains("## Your session identity"));
        assertFalse(identity.contains("AI Coder NetBeans Plugin v"),
                "the project baseline is a separate block");
    }

    @Test
    void projectBaselineIsAvailableSeparatelyFromThePrompt() {
        ContextProvider p = providerFor(AiTypeEnum.OLLAMA_LOCAL);
        String baseline = p.buildProjectBaseline();

        assertTrue(baseline.contains("AI Coder NetBeans Plugin v"));
        assertFalse(baseline.contains("## Your session identity"));
    }
}
