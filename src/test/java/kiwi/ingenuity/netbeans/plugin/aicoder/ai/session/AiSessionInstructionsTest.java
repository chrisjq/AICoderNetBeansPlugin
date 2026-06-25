package kiwi.ingenuity.netbeans.plugin.aicoder.ai.session;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiSessionInstructionsTest {

    @Test
    void instructionsLoadedDefaultsFalseAndSets() {
        AiSession s = AiSession.create(null, AiTypeEnum.CLAUDE);
        assertFalse(s.isInstructionsLoaded());
        s.setInstructionsLoaded(true);
        assertTrue(s.isInstructionsLoaded());
    }
}
