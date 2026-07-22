package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaAiImplementation;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import org.junit.jupiter.api.Test;

class AiTypeRegistryTest {

    @Test
    void createsOllamaLocalImplementation() {
        AiTypeRegistry registry = new AiTypeRegistry();
        AiImplementation impl = registry.create(AiTypeEnum.OLLAMA_LOCAL, event -> {
        }, null);
        assertInstanceOf(OllamaAiImplementation.class, impl);
    }
}
