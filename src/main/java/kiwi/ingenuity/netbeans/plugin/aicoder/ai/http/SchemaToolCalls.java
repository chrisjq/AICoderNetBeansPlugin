package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

/**
 * Tool calling for backends that misuse the request's
 * {@link OpenAiJsonKeyEnum#TOOLS} array.
 *
 * <p>
 * qwen2.5-coder calls a tool on every turn once {@link OpenAiJsonKeyEnum#TOOLS}
 * is populated — given one irrelevant tool and the message "hi" it invented a
 * city and called it — and returns the call as text rather than in
 * {@link OpenAiJsonKeyEnum#TOOL_CALLS}. Listing the same tools in the prompt
 * carries the information without triggering the template, and a
 * {@link OpenAiJsonKeyEnum#RESPONSE_FORMAT} JSON schema makes the reply shape
 * guaranteed instead of best-effort. Verified against qwen2.5-coder:14b: "hi"
 * answers with an empty {@link OpenAiJsonKeyEnum#TOOL_NAME}, a real request
 * fills it in.
 */
public final class SchemaToolCalls {

    private static final Gson GSON = new Gson();

    /**
     * The {@code response_format} value constraining the model to a reply that
     * is either prose or a tool call.
     */
    public static JsonObject responseFormat() {
        JsonObject props = new JsonObject();
        props.add(OpenAiJsonKeyEnum.MESSAGE.key(), stringField(
                "Your reply to the user. Use this whenever no tool is needed."));
        props.add(OpenAiJsonKeyEnum.TOOL_NAME.key(), stringField(
                "Exact name of one tool to call, or \"\" when answering directly."));
        JsonObject args = new JsonObject();
        args.addProperty(OpenAiJsonKeyEnum.TYPE.key(), "object");
        args.addProperty(OpenAiJsonKeyEnum.DESCRIPTION.key(),
                "Arguments for " + OpenAiJsonKeyEnum.TOOL_NAME.key() + ", or {} when not calling a tool.");
        props.add(OpenAiJsonKeyEnum.TOOL_ARGUMENTS.key(), args);

        JsonObject schema = new JsonObject();
        schema.addProperty(OpenAiJsonKeyEnum.TYPE.key(), "object");
        schema.add(OpenAiJsonKeyEnum.PROPERTIES.key(), props);
        // JSON Schema matches "required" entries against property names exactly,
        // so these must be the same constants used to add the properties above.
        // As literals they would still compile and still produce valid JSON
        // after a rename — the schema would just quietly stop requiring the
        // field it no longer names.
        schema.add(OpenAiJsonKeyEnum.REQUIRED.key(), GSON.toJsonTree(List.of(
                OpenAiJsonKeyEnum.MESSAGE.key(),
                OpenAiJsonKeyEnum.TOOL_NAME.key(),
                OpenAiJsonKeyEnum.TOOL_ARGUMENTS.key())));

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty(OpenAiJsonKeyEnum.NAME.key(), "tool_or_answer");
        jsonSchema.add(OpenAiJsonKeyEnum.SCHEMA.key(), schema);

        JsonObject format = new JsonObject();
        format.addProperty(OpenAiJsonKeyEnum.TYPE.key(), "json_schema");
        format.add(OpenAiJsonKeyEnum.JSON_SCHEMA.key(), jsonSchema);
        return format;
    }

    private static JsonObject stringField(String description) {
        JsonObject field = new JsonObject();
        field.addProperty(OpenAiJsonKeyEnum.TYPE.key(), "string");
        field.addProperty(OpenAiJsonKeyEnum.DESCRIPTION.key(), description);
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
            // These are the plugin's own MCP tool definitions — read with
            // ToolSchemaKeyEnum, not the OpenAI wire enum.
            if (tool == null || !tool.has(ToolSchemaKeyEnum.NAME.key())) {
                continue;
            }
            sb.append("- ").append(tool.get(ToolSchemaKeyEnum.NAME.key()).getAsString()).append('(');
            sb.append(String.join(", ", parameterNames(tool))).append(')');
            if (tool.has(ToolSchemaKeyEnum.DESCRIPTION.key())) {
                sb.append(" - ").append(firstSentence(tool.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * First sentence of a description, to bound prompt size across 80+ tools. A
     * full stop only ends a sentence when an upper-case word follows, so
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
     * <p>
     * Required names must appear exactly as-is: marking them instead with a
     * trailing {@code !} put the marker inside the identifier, and the model
     * sent {@code "!filePath"} and {@code "description!"} as argument keys.
     * Brackets decorate only the optional ones, which matter less if mangled.
     */
    private static List<String> parameterNames(JsonObject tool) {
        List<String> names = new ArrayList<>();
        JsonElement inputSchema = tool.get(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        if (inputSchema == null || !inputSchema.isJsonObject()) {
            return names;
        }
        JsonObject input = inputSchema.getAsJsonObject();
        JsonElement properties = input.get(ToolSchemaKeyEnum.PROPERTIES.key());
        if (properties == null || !properties.isJsonObject()) {
            return names;
        }
        List<String> required = new ArrayList<>();
        JsonElement requiredEl = input.get(ToolSchemaKeyEnum.REQUIRED.key());
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
                if (obj.has(OpenAiJsonKeyEnum.TOOL_NAME.key()) || obj.has(OpenAiJsonKeyEnum.MESSAGE.key())) {
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
        String message = obj.has(OpenAiJsonKeyEnum.MESSAGE.key()) && obj.get(OpenAiJsonKeyEnum.MESSAGE.key()).isJsonPrimitive()
                ? obj.get(OpenAiJsonKeyEnum.MESSAGE.key()).getAsString() : null;
        String name = obj.has(OpenAiJsonKeyEnum.TOOL_NAME.key()) && obj.get(OpenAiJsonKeyEnum.TOOL_NAME.key()).isJsonPrimitive()
                ? obj.get(OpenAiJsonKeyEnum.TOOL_NAME.key()).getAsString() : null;
        if (name == null || name.isBlank() || !knownToolNames.contains(name)) {
            return new Reply(message, List.of());
        }
        JsonObject arguments = obj.has(OpenAiJsonKeyEnum.TOOL_ARGUMENTS.key()) && obj.get(OpenAiJsonKeyEnum.TOOL_ARGUMENTS.key()).isJsonObject()
                ? cleanArgumentNames(obj.getAsJsonObject(OpenAiJsonKeyEnum.TOOL_ARGUMENTS.key())) : new JsonObject();
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

    /**
     * Parsed schema reply: at most one of message/call is meaningful.
     */
    public record Reply(String message, List<ExtractedToolCall> calls) {

    }
}
