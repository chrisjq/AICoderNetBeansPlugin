package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import java.util.List;

/**
 * Detected backend kind. Auto-detect tries {@link #autoDetectOrder()} in that precedence; none matching →
 * {@link #GENERIC}.
 */
public enum ServerKind {
    OLLAMA("Ollama"),
    LMSTUDIO("LM Studio"),
    LLAMACPP("llama.cpp"),
    VLLM("vLLM"),
    GENERIC("OpenAI-compatible");

    private final String displayName;

    ServerKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static List<ServerKind> autoDetectOrder() {
        return List.of(OLLAMA, LMSTUDIO, LLAMACPP, VLLM);
    }
}
