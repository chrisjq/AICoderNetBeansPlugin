package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.RawJsonArgumentScanner;

/**
 * Recovers tool calls the model wrote as text instead of structured {@code tool_calls}. No EndTurn handling.
 */
public final class ToolJsonRescue {

    private static final Gson GSON = new Gson();
    private static final String NAME = "name";
    private static final String ARGUMENTS = "arguments";

    private ToolJsonRescue() {
    }

    /**
     * A recovered call. {@code duplicateCounts} records argument names the raw JSON repeated.
     */
    public record RescuedCall(String name, String argumentsJson, Map<String, Integer> duplicateCounts) {

        public RescuedCall(String name, String argumentsJson) {
            this(name, argumentsJson, Map.of());
        }

        public RescuedCall {
            if (duplicateCounts == null) {
                duplicateCounts = Map.of();
            }
        }
    }

    /**
     * Structured calls win. Otherwise the assistant text is parsed, including a tool-call object embedded in prose.
     */
    public static List<RescuedCall> rescue(String assistantText, List<ChatToolCall> structuredCalls,
                                           Set<String> knownToolNames) {
        if (structuredCalls != null && !structuredCalls.isEmpty()) {
            return fromStructured(structuredCalls);
        }
        return rescue(assistantText, knownToolNames);
    }

    /**
     * @return calls whose {@code name} is in {@code knownToolNames}; empty when the text has none
     */
    public static List<RescuedCall> rescue(String assistantText, Set<String> knownToolNames) {
        if (knownToolNames == null || knownToolNames.isEmpty()) {
            return List.of();
        }
        if (assistantText == null || assistantText.isBlank()) {
            return List.of();
        }
        List<RescuedCall> whole = parseDocument(stripCodeFence(assistantText), knownToolNames);
        if (!whole.isEmpty()) {
            return whole;
        }
        List<RescuedCall> fromFences = new ArrayList<>();
        for (String block : fencedBlocks(assistantText)) {
            fromFences.addAll(parseDocument(block, knownToolNames));
        }
        if (!fromFences.isEmpty()) {
            return List.copyOf(fromFences);
        }
        return embeddedObjects(assistantText, knownToolNames);
    }

    public static List<RescuedCall> fromStructured(List<ChatToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<RescuedCall> out = new ArrayList<>();
        for (ChatToolCall call : toolCalls) {
            if (call == null || call.name() == null || call.name().isBlank()) {
                continue;
            }
            out.add(new RescuedCall(call.name(), call.argumentsJson() == null ? "{}" : call.argumentsJson()));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static List<RescuedCall> parseDocument(String text, Set<String> knownToolNames) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            List<RescuedCall> out = new ArrayList<>();
            Map<String, Integer> rawObjectDuplicates = RawJsonArgumentScanner.duplicateKeys(text, ARGUMENTS);
            if (parsed.isJsonObject()) {
                addIfKnownTool(parsed.getAsJsonObject(), knownToolNames, out, rawObjectDuplicates);
            }
            else if (parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (element != null && element.isJsonObject()) {
                        addIfKnownTool(element.getAsJsonObject(), knownToolNames, out, rawObjectDuplicates);
                    }
                }
            }
            return out.isEmpty() ? List.of() : List.copyOf(out);
        }
        catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static List<RescuedCall> embeddedObjects(String text, Set<String> knownToolNames) {
        List<RescuedCall> out = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int open = text.indexOf('{', from);
            if (open < 0) {
                break;
            }
            int end = matchingObjectEnd(text, open);
            if (end < 0) {
                break;
            }
            String slice = text.substring(open, end + 1);
            out.addAll(parseDocument(slice, knownToolNames));
            from = end + 1;
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static int matchingObjectEnd(String text, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                }
                else if (c == '\\') {
                    escape = true;
                }
                else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            }
            else if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> fencedBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int open = text.indexOf("```", from);
            if (open < 0) {
                break;
            }
            int close = text.indexOf("```", open + 3);
            if (close < 0) {
                break;
            }
            blocks.add(stripCodeFence(text.substring(open, close + 3)));
            from = close + 3;
        }
        return blocks;
    }

    /**
     * Unwraps a markdown code fence around a text-form tool call. The backticks are not JSON.
     *
     * @return the fenced content, or the input stripped if it is not fenced
     */
    private static String stripCodeFence(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int closing = trimmed.lastIndexOf("```");
        String inner = closing > 2 ? trimmed.substring(3, closing) : trimmed.substring(3);
        int firstNewline = inner.indexOf('\n');
        if (firstNewline >= 0) {
            String tag = inner.substring(0, firstNewline).trim();
            if (!tag.startsWith("{") && !tag.startsWith("[")) {
                inner = inner.substring(firstNewline + 1);
            }
        }
        return inner.strip();
    }

    private static void addIfKnownTool(JsonObject obj, Set<String> knownToolNames, List<RescuedCall> out,
                                       Map<String, Integer> rawObjectDuplicates) {
        JsonElement nameEl = obj.get(NAME);
        JsonElement argumentsEl = obj.get(ARGUMENTS);
        if (nameEl == null || !nameEl.isJsonPrimitive() || !nameEl.getAsJsonPrimitive().isString()) {
            return;
        }
        String name = nameEl.getAsString();
        if (!knownToolNames.contains(name)) {
            return;
        }
        JsonObject argumentsObj;
        Map<String, Integer> duplicates = Map.of();
        if (argumentsEl != null && argumentsEl.isJsonObject()) {
            argumentsObj = argumentsEl.getAsJsonObject();
            duplicates = rawObjectDuplicates == null ? Map.of() : rawObjectDuplicates;
        }
        else if (argumentsEl != null && argumentsEl.isJsonPrimitive() && argumentsEl.getAsJsonPrimitive().isString()) {
            String rawJson = argumentsEl.getAsString();
            duplicates = RawJsonArgumentScanner.duplicateTopLevelKeys(rawJson);
            try {
                JsonElement parsedArgs = JsonParser.parseString(rawJson);
                argumentsObj = parsedArgs.isJsonObject() ? parsedArgs.getAsJsonObject() : new JsonObject();
            }
            catch (RuntimeException ex) {
                argumentsObj = new JsonObject();
            }
        }
        else {
            argumentsObj = new JsonObject();
        }
        out.add(new RescuedCall(name, GSON.toJson(argumentsObj), duplicates));
    }
}
