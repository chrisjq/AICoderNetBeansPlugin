package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleMismatchTest {

    @Test
    @Timeout(5)
    void comparesConfiguredAndDetectedKinds() {
        assertFalse(OpenAiCompatibleAiProcessManager.isTypeMismatch(ServerTypeEnum.LM_STUDIO, ServerKind.LMSTUDIO));
        assertFalse(OpenAiCompatibleAiProcessManager.isTypeMismatch(ServerTypeEnum.LLAMA_CPP, ServerKind.LLAMACPP));
        assertTrue(OpenAiCompatibleAiProcessManager.isTypeMismatch(ServerTypeEnum.OLLAMA, ServerKind.LMSTUDIO));
        assertFalse(OpenAiCompatibleAiProcessManager.isTypeMismatch(ServerTypeEnum.AUTO, ServerKind.LMSTUDIO));
        assertFalse(OpenAiCompatibleAiProcessManager.isTypeMismatch(ServerTypeEnum.OTHER, ServerKind.LMSTUDIO));
        assertFalse(OpenAiCompatibleAiProcessManager.isTypeMismatch(ServerTypeEnum.OLLAMA, null));
    }

    @Test
    @Timeout(5)
    void formatsHintsUsingConfiguredAndDetectedFriendlyNames() {
        assertEquals("Server looks like LM Studio rather than Ollama — try Auto-detect",
                     OpenAiCompatibleAiProcessManager.mismatchHintText(ServerTypeEnum.OLLAMA, ServerKind.LMSTUDIO));
        assertEquals("Server doesn't look like Ollama — try Auto-detect",
                     OpenAiCompatibleAiProcessManager.mismatchHintText(ServerTypeEnum.OLLAMA, ServerKind.GENERIC));
    }
}
