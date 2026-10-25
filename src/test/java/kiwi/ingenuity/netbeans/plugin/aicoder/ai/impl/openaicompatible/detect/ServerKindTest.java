package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ServerKindTest {

    @Test
    @Timeout(5)
    void declarationOrderIsTheSpecPrecedenceWithGenericLast() {
        assertEquals(List.of(ServerKind.OLLAMA, ServerKind.LMSTUDIO, ServerKind.LLAMACPP, ServerKind.VLLM,
                             ServerKind.GENERIC),
                     List.of(ServerKind.values()));
    }

    @Test
    @Timeout(5)
    void autoDetectOmitsGeneric() {
        assertEquals(List.of(ServerKind.OLLAMA, ServerKind.LMSTUDIO, ServerKind.LLAMACPP, ServerKind.VLLM),
                     ServerKind.autoDetectOrder());
    }

    @Test
    @Timeout(5)
    void displayNamesMatchTheServerTypeLabels() {
        assertEquals("Ollama", ServerKind.OLLAMA.displayName());
        assertEquals("LM Studio", ServerKind.LMSTUDIO.displayName());
        assertEquals("llama.cpp", ServerKind.LLAMACPP.displayName());
        assertEquals("vLLM", ServerKind.VLLM.displayName());
        assertEquals("OpenAI-compatible", ServerKind.GENERIC.displayName());
    }
}
