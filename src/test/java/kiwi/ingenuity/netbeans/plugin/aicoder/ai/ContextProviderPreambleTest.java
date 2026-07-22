package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextProviderPreambleTest {
    private static final String CREDENTIAL_IMPORTANT =
            "IMPORTANT: When a tool takes sessionId/secretKey, pass the sessionId and secretKey shown above verbatim";

    @Test
    void preambleCredentialGatingMatchesFlag() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);

        ContextProvider withoutCreds = new ContextProvider(fo -> {
        });
        withoutCreds.setSession(session);
        String noCreds = withoutCreds.buildPreamble("prompt", null, false);
        assertFalse(noCreds.contains("secretKey:"));
        assertFalse(noCreds.contains(CREDENTIAL_IMPORTANT));

        ContextProvider withCreds = new ContextProvider(fo -> {
        });
        withCreds.setSession(session);
        String yesCreds = withCreds.buildPreamble("prompt", null, true);
        assertTrue(yesCreds.contains("secretKey:"));
        assertTrue(yesCreds.contains(CREDENTIAL_IMPORTANT));
    }
}
