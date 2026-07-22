package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.JsonObject;
import java.util.List;

public record ChatRequest(String baseUrl, String apiKey, String model,
        List<ChatMessage> messages, List<JsonObject> toolSchemas) {

}
