package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiTopComponentRestartStashTest {

    @Test
    void backendExitClearsAnOrdinaryPendingSubmit() {
        assertTrue(AiTopComponent.shouldClearPendingSubmitAfterBackendExit(false));
    }

    @Test
    void backendExitDuringToolRestartKeepsThePendingSubmitForFailureHandling() {
        assertFalse(AiTopComponent.shouldClearPendingSubmitAfterBackendExit(true));
    }
}
