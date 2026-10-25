package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Fixes the common ways a model spells tool arguments before the tool validates them: numbers and booleans sent as
 * strings, an object or array sent as a JSON string, a single value where the schema wants an array, and argument names
 * that differ from the schema only by case or by {@code _}/{@code -}. The point is to accept a call the model clearly
 * meant rather than bounce it back (spec §4 item 2).
 *
 * <p>
 * Pure and defensive: it returns a new object and never throws — on any internal failure the caller gets its input
 * back, so a tool still sees the original arguments rather than nothing.
 */
public final class ArgumentCoercion {

    private static final String KEY_TYPE = "type";
    private static final String KEY_PROPERTIES = "properties";

    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_NUMBER = "number";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_OBJECT = "object";
    private static final String TYPE_ARRAY = "array";

    private ArgumentCoercion() {
    }

    /**
     * @return a new arguments object with the names matched to {@code inputSchema} and each value coerced to the
     * schema's declared type where that is unambiguous. Unknown properties are kept. The input is never modified; on
     * failure the input itself is returned.
     */
    public static JsonObject coerce(JsonObject args, JsonObject inputSchema) {
        if (args == null) {
            return new JsonObject();
        }
        try {
            JsonObject schemaProperties = propertiesOf(inputSchema);
            if (schemaProperties == null || schemaProperties.size() == 0) {
                return args.deepCopy();
            }

            Map<String, String> canonicalNames = new LinkedHashMap<>();
            for (String schemaName : schemaProperties.keySet()) {
                canonicalNames.put(normalise(schemaName), schemaName);
            }

            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
                String argName = entry.getKey();
                String canonical = canonicalNames.get(normalise(argName));
                if (canonical == null) {
                    out.add(argName, entry.getValue().deepCopy());
                    continue;
                }
                JsonObject propertySchema = asObject(schemaProperties.get(canonical));
                String declaredType = stringValue(propertySchema == null ? null : propertySchema.get(KEY_TYPE));
                JsonElement coerced = coerceValue(entry.getValue(), declaredType);
                out.add(canonical, coerced);
            }
            return out;
        }
        catch (RuntimeException ex) {
            return args;
        }
    }

    private static JsonElement coerceValue(JsonElement value, String type) {
        if (value == null || type == null || !value.isJsonPrimitive()) {
            if (value != null && TYPE_ARRAY.equals(type) && !value.isJsonArray()) {
                return wrapInArray(value);
            }
            return value;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        switch (type) {
            case TYPE_INTEGER -> {
                Long parsed = parseLongText(primitive);
                return parsed == null ? value : new JsonPrimitive(parsed);
            }
            case TYPE_NUMBER -> {
                Double parsed = parseDoubleText(primitive);
                return parsed == null ? value : new JsonPrimitive(parsed);
            }
            case TYPE_BOOLEAN -> {
                Boolean parsed = parseBooleanText(primitive);
                return parsed == null ? value : new JsonPrimitive(parsed);
            }
            case TYPE_OBJECT -> {
                JsonElement parsed = parseJsonText(primitive, JsonElement::isJsonObject);
                return parsed == null ? value : parsed;
            }
            case TYPE_ARRAY -> {
                JsonElement parsed = parseJsonText(primitive, JsonElement::isJsonArray);
                return parsed == null ? wrapInArray(value) : parsed;
            }
            default -> {
                return value;
            }
        }
    }

    private static Long parseLongText(JsonPrimitive primitive) {
        if (!primitive.isString()) {
            return null;
        }
        String text = primitive.getAsString().trim();
        try {
            return Long.valueOf(text);
        }
        catch (NumberFormatException ex) {
            Double number = parseDoubleText(new JsonPrimitive(text));
            if (number != null && number == Math.rint(number) && !number.isInfinite()) {
                return number.longValue();
            }
            return null;
        }
    }

    private static Double parseDoubleText(JsonPrimitive primitive) {
        if (!primitive.isString()) {
            return null;
        }
        String text = primitive.getAsString().trim();
        try {
            return Double.valueOf(text);
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean parseBooleanText(JsonPrimitive primitive) {
        if (!primitive.isString()) {
            return null;
        }
        String text = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static JsonElement parseJsonText(JsonPrimitive primitive, Predicate<JsonElement> accept) {
        if (!primitive.isString()) {
            return null;
        }
        String text = primitive.getAsString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return accept.test(parsed) ? parsed : null;
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    private static JsonArray wrapInArray(JsonElement value) {
        JsonArray array = new JsonArray();
        array.add(value.deepCopy());
        return array;
    }

    private static JsonObject propertiesOf(JsonObject inputSchema) {
        if (inputSchema == null) {
            return null;
        }
        return asObject(inputSchema.get(KEY_PROPERTIES));
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String stringValue(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
               ? element.getAsString() : null;
    }

    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
