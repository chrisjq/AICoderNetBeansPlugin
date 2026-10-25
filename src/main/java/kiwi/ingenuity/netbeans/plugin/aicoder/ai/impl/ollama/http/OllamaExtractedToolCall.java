package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http;

import java.util.Map;

public record OllamaExtractedToolCall(String name, String argumentsJson, Map<String, Integer> duplicateCounts) {

    public OllamaExtractedToolCall(String name, String argumentsJson) {
        this(name, argumentsJson, Map.of());
    }

    public OllamaExtractedToolCall {
        if (duplicateCounts == null) {
            duplicateCounts = Map.of();
        }
    }
}
