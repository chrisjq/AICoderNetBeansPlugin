package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ServerRemedyEnumTest {

    @Test
    @Timeout(5)
    void eachKindHasItsRemedyText() {
        assertEquals("OLLAMA_CONTEXT_LENGTH or the model's num_ctx", ServerRemedyEnum.OLLAMA.remedy());
        assertEquals("set Context Length when loading the model", ServerRemedyEnum.LMSTUDIO.remedy());
        assertEquals("start it with -c", ServerRemedyEnum.LLAMACPP.remedy());
        assertEquals("--max-model-len", ServerRemedyEnum.VLLM.remedy());
        assertEquals("raise it on the server", ServerRemedyEnum.GENERIC.remedy());
    }

    @Test
    @Timeout(5)
    void aKindNameMapsToItsRemedy() {
        assertEquals(ServerRemedyEnum.OLLAMA, ServerRemedyEnum.forKind("OLLAMA"));
        assertEquals(ServerRemedyEnum.LMSTUDIO, ServerRemedyEnum.forKind("LMSTUDIO"));
        assertEquals(ServerRemedyEnum.LMSTUDIO, ServerRemedyEnum.forKind("LM_STUDIO"));
        assertEquals(ServerRemedyEnum.LLAMACPP, ServerRemedyEnum.forKind("llamacpp"));
        assertEquals(ServerRemedyEnum.VLLM, ServerRemedyEnum.forKind(" VLLM "));
        assertEquals(ServerRemedyEnum.GENERIC, ServerRemedyEnum.forKind("GENERIC"));
    }

    @Test
    @Timeout(5)
    void unknownBlankAndNullFallBackToGeneric() {
        assertEquals(ServerRemedyEnum.GENERIC, ServerRemedyEnum.forKind("SOMETHING"));
        assertEquals(ServerRemedyEnum.GENERIC, ServerRemedyEnum.forKind(""));
        assertEquals(ServerRemedyEnum.GENERIC, ServerRemedyEnum.forKind(null));
    }
}
