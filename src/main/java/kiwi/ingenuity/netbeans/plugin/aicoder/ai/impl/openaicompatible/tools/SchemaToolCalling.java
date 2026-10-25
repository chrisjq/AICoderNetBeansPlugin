package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.RawJsonArgumentScanner;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

/**
 * Schema-mode tool calling: a JSON schema reply, the tools rendered into the prompt, and a parse of that reply. There
 * is no EndTurn tool and no EndTurn name in the schema.
 */
public final class SchemaToolCalling {

    private static final Gson GSON = new Gson();
    private static final String TYPE = "type";
    private static final String DESCRIPTION = "description";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String ENUM = "enum";
    private static final String NAME = "name";
    private static final String SCHEMA = "schema";
    private static final String JSON_SCHEMA = "json_schema";
    private static final String MESSAGE = "message";
    private static final String TOOL_NAME = "tool_name";
    private static final String TOOL_ARGUMENTS = "tool_arguments";
    private static final String END_TURN = "EndTurn";

    private SchemaToolCalling() {
    }

    /**
     * The {@code response_format} value constraining the model to a reply that is either prose or one tool call.
     * <p>
     * {@code knownToolNames} restricts {@code tool_name} to an enum rather than any string. The empty string stays in
     * the enum because it is how the model declines to call anything. EndTurn is never offered.
     */
    public static JsonObject responseFormat(Collection<String> knownToolNames) {
        JsonObject props = new JsonObject();
        props.add(MESSAGE, stringField("Your reply to the user. Use this whenever no tool is needed."));
        JsonObject toolName = stringField("Exact name of one tool to call, or \"\" when answering directly.");
        List<String> allowed = allowedToolNames(knownToolNames);
        if (!allowed.isEmpty()) {
            toolName.add(ENUM, GSON.toJsonTree(allowed));
        }
        props.add(TOOL_NAME, toolName);
        JsonObject args = new JsonObject();
        args.addProperty(TYPE, "object");
        args.addProperty(DESCRIPTION, "Arguments for " + TOOL_NAME + ", or {} when not calling a tool.");
        props.add(TOOL_ARGUMENTS, args);

        JsonObject schema = new JsonObject();
        schema.addProperty(TYPE, "object");
        schema.add(PROPERTIES, props);
        schema.add(REQUIRED, GSON.toJsonTree(List.of(MESSAGE, TOOL_NAME, TOOL_ARGUMENTS)));

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty(NAME, "tool_or_answer");
        jsonSchema.add(SCHEMA, schema);

        JsonObject format = new JsonObject();
        format.addProperty(TYPE, "json_schema");
        format.add(JSON_SCHEMA, jsonSchema);
        return format;
    }

    /**
     * Renders tool schemas as prompt text. EndTurn is skipped. The caller owns the section heading.
     */
    public static String renderToolList(Collection<JsonObject> toolSchemas) {
        StringBuilder sb = new StringBuilder();
        if (toolSchemas == null) {
            return "";
        }
        for (JsonObject tool : toolSchemas) {
            if (tool == null || !tool.has(ToolSchemaKeyEnum.NAME.key())) {
                continue;
            }
            String name = tool.get(ToolSchemaKeyEnum.NAME.key()).getAsString();
            if (END_TURN.equals(name)) {
                continue;
            }
            sb.append("- ").append(name).append('(');
            sb.append(String.join(", ", parameterNames(tool))).append(')').append('\n');
            if (tool.has(ToolSchemaKeyEnum.DESCRIPTION.key())) {
                sb.append("  ").append(tool.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString()).append('\n');
            }
            appendParameterDetails(sb, tool);
        }
        return sb.toString();
    }

    /**
     * Reads a schema-shaped reply. Falls back to {@link ToolJsonRescue} when the model ignores the schema.
     *
     * @param knownToolNames names that may be invoked; anything else, including EndTurn, is not called
     */
    public static Reply parse(String assistantText, List<ChatToolCall> structuredCalls, Set<String> knownToolNames) {
        Set<String> known = knownToolNames == null ? Set.of() : knownToolNames;
        if (structuredCalls != null && !structuredCalls.isEmpty()) {
            return new Reply(assistantText, ToolJsonRescue.fromStructured(structuredCalls), null, false);
        }
        if (assistantText == null || assistantText.isBlank()) {
            return new Reply(null, List.of(), null, false);
        }
        String text = assistantText.strip();
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has(TOOL_NAME) || obj.has(MESSAGE)) {
                    return fromSchemaObject(obj, known, RawJsonArgumentScanner.duplicateKeys(text, TOOL_ARGUMENTS));
                }
            }
        }
        catch (RuntimeException ex) {
            // Not schema-shaped; fall through to the text rescue.
        }
        return new Reply(assistantText, ToolJsonRescue.rescue(assistantText, known), null, false);
    }

    private static List<String> allowedToolNames(Collection<String> knownToolNames) {
        if (knownToolNames == null || knownToolNames.isEmpty()) {
            return List.of();
        }
        List<String> allowed = new ArrayList<>();
        allowed.add("");
        for (String name : knownToolNames) {
            if (name != null && !name.isBlank() && !END_TURN.equals(name) && !allowed.contains(name)) {
                allowed.add(name);
            }
        }
        return allowed;
    }

    private static JsonObject stringField(String description) {
        JsonObject field = new JsonObject();
        field.addProperty(TYPE, "string");
        field.addProperty(DESCRIPTION, description);
        return field;
    }

    private static void appendParameterDetails(StringBuilder sb, JsonObject tool) {
        JsonElement inputSchema = tool.get(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        if (inputSchema == null || !inputSchema.isJsonObject()) {
            return;
        }
        JsonObject input = inputSchema.getAsJsonObject();
        JsonElement properties = input.get(ToolSchemaKeyEnum.PROPERTIES.key());
        if (properties == null || !properties.isJsonObject()) {
            return;
        }
        List<String> required = requiredParameterNames(input);
        for (Map.Entry<String, JsonElement> entry : properties.getAsJsonObject().entrySet()) {
            JsonElement property = entry.getValue();
            if (!property.isJsonObject()) {
                continue;
            }
            JsonObject details = property.getAsJsonObject();
            String type = details.has(ToolSchemaKeyEnum.TYPE.key())
                          ? details.get(ToolSchemaKeyEnum.TYPE.key()).getAsString() : "unspecified";
            String requiredness = required.contains(entry.getKey()) ? "required" : "optional";
            sb.append("    ").append(entry.getKey()).append(" (").append(type).append(", ")
                    .append(requiredness).append(')');
            if (details.has(ToolSchemaKeyEnum.DESCRIPTION.key())) {
                sb.append(" - ").append(details.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString());
            }
            sb.append('\n');
        }
    }

    private static List<String> requiredParameterNames(JsonObject input) {
        List<String> required = new ArrayList<>();
        JsonElement requiredEl = input.get(ToolSchemaKeyEnum.REQUIRED.key());
        if (requiredEl != null && requiredEl.isJsonArray()) {
            requiredEl.getAsJsonArray().forEach(element -> {
                if (element.isJsonPrimitive()) {
                    required.add(element.getAsString());
                }
            });
        }
        return required;
    }

    /**
     * Parameter names, required first, optional ones in square brackets. Required names stay unmarked: a trailing
     * marker was copied into the argument key.
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
        List<String> required = requiredParameterNames(input);
        names.addAll(required);
        properties.getAsJsonObject().keySet().stream()
                .filter(name -> !required.contains(name))
                .forEach(name -> names.add("[" + name + "]"));
        return names;
    }

    private static boolean argumentsWereSupplied(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject().size() > 0;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().size() > 0;
        }
        return !element.getAsString().isBlank();
    }

    private static Reply fromSchemaObject(JsonObject obj, Set<String> knownToolNames,
                                          Map<String, Integer> duplicateCounts) {
        String message = text(obj, MESSAGE);
        String name = text(obj, TOOL_NAME);
        JsonElement rawArgumentsElement = obj.get(TOOL_ARGUMENTS);
        JsonObject rawArguments = rawArgumentsElement != null && rawArgumentsElement.isJsonObject()
                                  ? rawArgumentsElement.getAsJsonObject() : new JsonObject();
        if (name == null || name.isBlank()) {
            if (argumentsWereSupplied(rawArgumentsElement)) {
                String error = "Error: you supplied " + TOOL_ARGUMENTS
                        + " but left " + TOOL_NAME
                        + " empty, so no tool was called. Send the same arguments again with "
                        + TOOL_NAME + " set to the tool you want to run.";
                if (!rawArgumentsElement.isJsonObject()) {
                    error += " " + TOOL_ARGUMENTS + " must be a JSON object, not a string.";
                }
                return new Reply(message, List.of(), error, true);
            }
            return new Reply(message, List.of(), null, true);
        }
        if (END_TURN.equals(name) || !knownToolNames.contains(name)) {
            return new Reply(message, List.of(),
                             "Error: there is no tool named \"" + name + "\", so nothing was called. "
                             + "Use one of the tool names exactly as listed, or reply in plain text if no tool is needed.",
                             true);
        }
        JsonObject arguments = cleanArgumentNames(rawArguments);
        return new Reply(message,
                         List.of(new ToolJsonRescue.RescuedCall(name, GSON.toJson(arguments), duplicateCounts)),
                         null, true);
    }

    private static String text(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    /**
     * Strips decoration the model copies out of the rendered tool list into argument names.
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

    /**
     * Parsed schema reply. {@code toolCallError} is phrased for the model and fed back as a tool result.
     * {@code spokeProtocol} is true when the reply was the schema object rather than rescued text.
     */
    public record Reply(String message, List<ToolJsonRescue.RescuedCall> calls, String toolCallError,
                        boolean spokeProtocol) {

        public Reply {
            if (calls == null) {
                calls = List.of();
            }
        }
    }
}
