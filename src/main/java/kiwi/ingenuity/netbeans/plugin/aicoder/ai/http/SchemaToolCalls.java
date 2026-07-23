package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Tool calling for backends that misuse the request's {@code tools} array.
 *
 * <p>qwen2.5-coder calls a tool on every turn once {@code tools} is populated —
 * given one irrelevant tool and the message "hi" it invented a city and called
 * it — and returns the call as text rather than in {@code tool_calls}. Listing
 * the same tools in the prompt carries the information without triggering the
 * template, and a {@code response_format} JSON schema makes the reply shape
 * guaranteed instead of best-effort. Verified against qwen2.5-coder:14b: "hi"
 * answers with an empty {@code tool_name}, a real request fills it in.
 */
public final class SchemaToolCalls {

    private static final Gson GSON = new Gson();

    /** Parsed schema reply: at most one of message/call is meaningful. */
    public record Reply(String message, List<ExtractedToolCall> calls) {

    }

    /**
     * The {@code response_format} value constraining the model to a reply that
     * is either prose or a tool call.
     */
    public static JsonObject responseFormat() {
        JsonObject props = new JsonObject();
        props.add("message", stringField(
                "Your reply to the user. Use this whenever no tool is needed."));
        props.add("tool_name", stringField(
                "Exact name of one tool to call, or \"\" when answering directly."));
        JsonObject args = new JsonObject();
        args.addProperty("type", "object");
        args.addProperty("description", "Arguments for tool_name, or {} when not calling a tool.");
        props.add("tool_arguments", args);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", GSON.toJsonTree(List.of("message", "tool_name", "tool_arguments")));

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "tool_or_answer");
        jsonSchema.add("schema", schema);

        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.add("json_schema", jsonSchema);
        return format;
    }

    private static JsonObject stringField(String description) {
        JsonObject field = new JsonObject();
        field.addProperty("type", "string");
        field.addProperty("description", description);
        return field;
    }

    /**
     * Renders tool schemas as prompt text, since the model no longer receives
     * the tools array. Parameter names must appear here or the model has no way
     * to know them — the per-tool instruction lines alone do not carry them.
     */
    public static String renderToolList(Collection<JsonObject> toolSchemas) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject tool : toolSchemas) {
            if (tool == null || !tool.has("name")) {
                continue;
            }
            sb.append("- ").append(tool.get("name").getAsString()).append('(');
            sb.append(String.join(", ", parameterNames(tool))).append(')');
            if (tool.has("description")) {
                sb.append(" - ").append(firstSentence(tool.get("description").getAsString()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * First sentence of a description, to bound prompt size across 80+ tools.
     * A full stop only ends a sentence when an upper-case word follows, so
     * abbreviations survive — splitting naively on ". " cut "(e.g. /path)" to
     * "(e.g" mid-word.
     */
    private static String firstSentence(String description) {
        for (int i = 0; i + 2 < description.length(); i++) {
            if (description.charAt(i) == '.' && description.charAt(i + 1) == ' '
                    && Character.isUpperCase(description.charAt(i + 2))) {
                return description.substring(0, i);
            }
        }
        return description;
    }

    /**
     * Parameter names, required first, optional ones in square brackets.
     *
     * <p>Required names must appear exactly as-is: marking them instead with a
     * trailing {@code !} put the marker inside the identifier, and the model
     * sent {@code "!filePath"} and {@code "description!"} as argument keys.
     * Brackets decorate only the optional ones, which matter less if mangled.
     */
    private static List<String> parameterNames(JsonObject tool) {
        List<String> names = new ArrayList<>();
        JsonElement inputSchema = tool.get("inputSchema");
        if (inputSchema == null || !inputSchema.isJsonObject()) {
            return names;
        }
        JsonObject input = inputSchema.getAsJsonObject();
        JsonElement properties = input.get("properties");
        if (properties == null || !properties.isJsonObject()) {
            return names;
        }
        List<String> required = new ArrayList<>();
        JsonElement requiredEl = input.get("required");
        if (requiredEl != null && requiredEl.isJsonArray()) {
            requiredEl.getAsJsonArray().forEach(e -> {
                if (e.isJsonPrimitive()) {
                    required.add(e.getAsString());
                }
            });
        }
        names.addAll(required);
        properties.getAsJsonObject().keySet().stream()
                .filter(name -> !required.contains(name))
                .forEach(name -> names.add("[" + name + "]"));
        return names;
    }

    /**
     * Reads a schema-shaped reply. Falls back to {@link ToolCallExtractor} when
     * the model ignores the schema, which it still does occasionally.
     *
     * @param knownToolNames names that may be invoked; anything else is dropped
     */
    public static Reply parse(ChatResult result, Set<String> knownToolNames) {
        if (result == null) {
            return new Reply(null, List.of());
        }
        if (result.toolCalls() != null && !result.toolCalls().isEmpty()) {
            return new Reply(result.assistantText(),
                    ToolCallExtractor.extract(result, knownToolNames));
        }
        String text = result.assistantText();
        if (text == null || text.isBlank()) {
            return new Reply(null, List.of());
        }
        try {
            JsonElement parsed = JsonParser.parseString(text.strip());
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has("tool_name") || obj.has("message")) {
                    return fromSchemaObject(obj, knownToolNames);
                }
            }
        }
        catch (RuntimeException ex) {
            // Not schema-shaped; fall through to the text extractor.
        }
        return new Reply(text, ToolCallExtractor.extract(result, knownToolNames));
    }

    private static Reply fromSchemaObject(JsonObject obj, Set<String> knownToolNames) {
        String message = obj.has("message") && obj.get("message").isJsonPrimitive()
                ? obj.get("message").getAsString() : null;
        String name = obj.has("tool_name") && obj.get("tool_name").isJsonPrimitive()
                ? obj.get("tool_name").getAsString() : null;
        if (name == null || name.isBlank() || !knownToolNames.contains(name)) {
            return new Reply(message, List.of());
        }
        JsonObject arguments = obj.has("tool_arguments") && obj.get("tool_arguments").isJsonObject()
                ? cleanArgumentNames(obj.getAsJsonObject("tool_arguments")) : new JsonObject();
        return new Reply(message, List.of(new ExtractedToolCall(name, GSON.toJson(arguments))));
    }

    /**
     * Strips decoration the model copies out of the rendered tool list into
     * argument names — it has produced both {@code "!filePath"} and
     * {@code "description!"}, each of which reads as a missing argument.
     */
    private static JsonObject cleanArgumentNames(JsonObject arguments) {
        JsonObject cleaned = new JsonObject();
        for (String key : arguments.keySet()) {
            String name = key.strip();
            while (!name.isEmpty() && "![]".indexOf(name.charAt(0)) >= 0) {
                name = name.substring(1);
            }
            while (!name.isEmpty() && "![]".indexOf(name.charAt(name.length() - 1)) >= 0) {
                name = name.substring(0, name.length() - 1);
            }
            if (!name.isEmpty()) {
                cleaned.add(name, arguments.get(key));
            }
        }
        return cleaned;
    }

    private SchemaToolCalls() {
    }
}
