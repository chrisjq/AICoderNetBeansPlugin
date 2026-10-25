package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.util.Locale;

public enum ServerTypeEnum {
    AUTO("Auto-detect", ""),
    OLLAMA("Ollama", "http://localhost:11434"),
    LM_STUDIO("LM Studio", "http://localhost:1234"),
    LLAMA_CPP("llama.cpp", "http://localhost:8080"),
    VLLM("vLLM", "http://localhost:8000"),
    OTHER("OpenAI-compatible (other / hosted)", "");

    private final String displayName;
    private final String presetUrl;

    ServerTypeEnum(String displayName, String presetUrl) {
        this.displayName = displayName;
        this.presetUrl = presetUrl;
    }

    public String displayName() {
        return displayName;
    }

    public String presetUrl() {
        return presetUrl;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
