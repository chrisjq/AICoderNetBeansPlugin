package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import com.google.gson.JsonArray;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

public record AskUserQuestionEvent(
        JsonArray questions,
        CompletableFuture<String> response
        ) implements AiProcessEvent {

}
