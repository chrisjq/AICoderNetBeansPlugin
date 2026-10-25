package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.HiddenToolTextFilter;

/**
 * Turns a chat transcript into plain text for a summary request: {@code [User]} and {@code [Assistant]} text, one
 * {@code [Assistant tool calls]} line per call as {@code name(k=v, …)}, and {@code [Tool result]} text cut to
 * {@link #TOOL_RESULT_MAX_CHARS}. System messages and reasoning are left out. Sentences that name a hidden tool are
 * removed, the same way the Ollama summariser filters its text.
 */
public final class TranscriptSerialiser {

    /**
     * Longest tool result kept in the transcript; anything beyond it is replaced by a truncation marker.
     */
    public static final int TOOL_RESULT_MAX_CHARS = 2_000;

    private final Set<McpToolEnum> hiddenTools;
    private final int toolResultMaxChars;

    public TranscriptSerialiser(Set<McpToolEnum> hiddenTools) {
        this(hiddenTools, TOOL_RESULT_MAX_CHARS);
    }

    /**
     * @param toolResultMaxChars longest tool result or argument value kept; zero or less means the default
     */
    public TranscriptSerialiser(Set<McpToolEnum> hiddenTools, int toolResultMaxChars) {
        this.hiddenTools = hiddenTools == null ? Set.of() : Set.copyOf(hiddenTools);
        this.toolResultMaxChars = toolResultMaxChars <= 0 ? TOOL_RESULT_MAX_CHARS : toolResultMaxChars;
    }

    /**
     * The whole transcript as one text, one entry per line-block, in message order.
     */
    public String render(List<ChatMessage> messages) {
        return String.join("\n", entries(messages));
    }

    /**
     * The transcript split into chunks of at most {@code charBudget} characters, cut only at message boundaries. An
     * entry longer than the budget is never split: it forms a chunk of its own.
     */
    public List<String> chunks(List<ChatMessage> messages, int charBudget) {
        if (charBudget <= 0) {
            throw new IllegalArgumentException("charBudget must be positive: " + charBudget);
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String entry : entries(messages)) {
            if (current.length() > 0 && current.length() + 1 + entry.length() > charBudget) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(entry);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private List<String> entries(List<ChatMessage> messages) {
        List<String> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (ChatMessage message : messages) {
            String entry = entry(message);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    private String entry(ChatMessage message) {
        String rendered = switch (message.role()) {
            case USER ->
                prefixed("[User] ", content(message));
            case ASSISTANT ->
                assistantBody(message);
            case TOOL ->
                prefixed("[Tool result] ", truncate(content(message)));
            case SYSTEM ->
                "";
        };
        String kept = HiddenToolTextFilter.omitSentencesReferencing(rendered, hiddenTools);
        return kept == null || kept.isBlank() ? null : kept;
    }

    private String assistantBody(ChatMessage message) {
        List<String> lines = new ArrayList<>();
        String text = content(message);
        if (!text.isBlank()) {
            lines.add("[Assistant] " + text);
        }
        for (ChatToolCall call : message.toolCalls()) {
            lines.add("[Assistant tool calls] " + callText(call));
        }
        return String.join("\n", lines);
    }

    private String callText(ChatToolCall call) {
        StringBuilder sb = new StringBuilder(call.name()).append('(');
        JsonElement args = parseArguments(call.argumentsJson());
        if (args != null && args.isJsonObject()) {
            boolean first = true;
            for (Map.Entry<String, JsonElement> arg : args.getAsJsonObject().entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(arg.getKey()).append('=').append(truncate(valueText(arg.getValue())));
                first = false;
            }
        }
        else {
            sb.append(truncate(call.argumentsJson() == null ? "" : call.argumentsJson()));
        }
        return sb.append(')').toString();
    }

    private static JsonElement parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(json);
        }
        catch (JsonParseException ex) {
            return null;
        }
    }

    private static String valueText(JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static String prefixed(String prefix, String text) {
        return text.isBlank() ? "" : prefix + text;
    }

    private static String content(ChatMessage message) {
        return message.content() == null ? "" : message.content();
    }

    private String truncate(String text) {
        if (text.length() <= toolResultMaxChars) {
            return text;
        }
        return text.substring(0, toolResultMaxChars)
                + " …[truncated " + (text.length() - toolResultMaxChars) + " chars]";
    }
}
