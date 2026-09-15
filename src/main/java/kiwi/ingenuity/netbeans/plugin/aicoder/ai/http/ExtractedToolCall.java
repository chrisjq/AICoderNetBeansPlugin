package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.util.Map;

public record ExtractedToolCall(String name, String argumentsJson, Map<String, Integer> duplicateCounts) {

    public ExtractedToolCall(String name, String argumentsJson) {
        this(name, argumentsJson, Map.of());
    }

    public ExtractedToolCall {
        if (duplicateCounts == null) {
            duplicateCounts = Map.of();
        }
    }
}
