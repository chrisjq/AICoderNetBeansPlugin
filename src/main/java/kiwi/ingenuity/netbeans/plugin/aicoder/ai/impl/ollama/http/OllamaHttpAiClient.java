package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http;

import java.io.IOException;
import java.util.function.Consumer;

public interface OllamaHttpAiClient {

    OllamaChatResult chat(OllamaChatRequest request, Consumer<String> onTextDelta) throws IOException;
}
