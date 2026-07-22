package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.io.IOException;
import java.util.function.Consumer;

public interface HttpAiClient {

    ChatResult chat(ChatRequest request, Consumer<String> onTextDelta) throws IOException;
}
