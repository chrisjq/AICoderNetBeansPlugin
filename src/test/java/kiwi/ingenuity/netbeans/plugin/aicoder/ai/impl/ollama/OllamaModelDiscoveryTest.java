package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class OllamaModelDiscoveryTest {

    @Test
    void assembleModelListDropsBlanksAndDedupes() {
        String[] out = OllamaModelDiscovery.assembleModelList(
                List.of("qwen2.5-coder:7b", " qwen2.5-coder:7b ", "", "qwen2.5-coder:14b"));
        assertArrayEquals(new String[]{"qwen2.5-coder:7b", "qwen2.5-coder:14b"}, out);
    }

    @Test
    void parseModelIdsExtractsIdsInOrder() {
        String body = "{\"data\":[{\"id\":\"qwen2.5-coder:7b\"},{\"id\":\"qwen2.5-coder:14b\"}]}";
        assertEquals(List.of("qwen2.5-coder:7b", "qwen2.5-coder:14b"),
                OllamaModelDiscovery.parseModelIds(body));
    }

    @Test
    void parseModelIdsReturnsEmptyWhenMissing() {
        assertEquals(List.of(), OllamaModelDiscovery.parseModelIds("{}"));
        assertEquals(List.of(), OllamaModelDiscovery.parseModelIds("{\"data\":[]}"));
    }

    @Test
    void extractCapabilityHintWarnsWhenToolsCapabilityMissing() {
        String body = "{\"capabilities\":[\"completion\"]}";
        assertEquals("Selected model may not support structured tool calls in Ollama; JSON-in-content fallback will be used.",
                OllamaModelDiscovery.extractCapabilityHint(body));
    }

    @Test
    void extractCapabilityHintReturnsNullWhenToolsCapabilityPresent() {
        String body = "{\"capabilities\":[\"completion\",\"tools\"]}";
        assertNull(OllamaModelDiscovery.extractCapabilityHint(body));
    }
}
