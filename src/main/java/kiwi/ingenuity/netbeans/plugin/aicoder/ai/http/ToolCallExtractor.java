package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ToolCallExtractor {

    private static final Gson GSON = new Gson();

    public static List<ExtractedToolCall> extract(ChatResult result, Set<String> knownToolNames) {
        if (result == null) {
            return List.of();
        }
        if (result.toolCalls() != null && !result.toolCalls().isEmpty()) {
            return fromStructured(result.toolCalls());
        }
        if (knownToolNames == null || knownToolNames.isEmpty()) {
            return List.of();
        }
        String assistantText = result.assistantText();
        if (assistantText == null || assistantText.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(assistantText);
            List<ExtractedToolCall> out = new ArrayList<>();
            if (parsed.isJsonObject()) {
                addIfKnownTool(parsed.getAsJsonObject(), knownToolNames, out);
            }
            else if (parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (element != null && element.isJsonObject()) {
                        addIfKnownTool(element.getAsJsonObject(), knownToolNames, out);
                    }
                }
            }
            return out.isEmpty() ? List.of() : List.copyOf(out);
        }
        catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static List<ExtractedToolCall> fromStructured(List<ChatToolCall> toolCalls) {
        List<ExtractedToolCall> out = new ArrayList<>();
        for (ChatToolCall call : toolCalls) {
            if (call == null || call.name() == null || call.name().isBlank()) {
                continue;
            }
            out.add(new ExtractedToolCall(call.name(),
                    call.argumentsJson() == null ? "{}" : call.argumentsJson()));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static void addIfKnownTool(JsonObject obj, Set<String> knownToolNames,
            List<ExtractedToolCall> out) {
        JsonElement nameEl = obj.get("name");
        JsonElement argumentsEl = obj.get("arguments");
        if (nameEl == null || !nameEl.isJsonPrimitive() || !nameEl.getAsJsonPrimitive().isString()) {
            return;
        }
        String name = nameEl.getAsString();
        if (!knownToolNames.contains(name)) {
            return;
        }
        JsonObject argumentsObj;
        if (argumentsEl != null && argumentsEl.isJsonObject()) {
            argumentsObj = argumentsEl.getAsJsonObject();
        }
        else if (argumentsEl != null && argumentsEl.isJsonPrimitive() && argumentsEl.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsedArgs = JsonParser.parseString(argumentsEl.getAsString());
                argumentsObj = parsedArgs.isJsonObject() ? parsedArgs.getAsJsonObject() : new JsonObject();
            }
            catch (RuntimeException ex) {
                argumentsObj = new JsonObject();
            }
        }
        else {
            argumentsObj = new JsonObject();
        }
        out.add(new ExtractedToolCall(name, GSON.toJson(argumentsObj)));
    }

    private ToolCallExtractor() {
    }
}
