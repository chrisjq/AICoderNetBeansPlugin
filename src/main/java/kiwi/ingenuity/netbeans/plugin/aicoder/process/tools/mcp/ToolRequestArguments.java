package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;

public class ToolRequestArguments {

    private final JsonObject raw;

    public ToolRequestArguments(JsonObject raw) {
        this.raw = raw != null ? raw : new JsonObject();
    }

    /**
     * A copy of the arguments without {@code keys}, e.g. to show a tool call without the caller's credentials.
     */
    public JsonObject withoutKeys(String... keys) {
        JsonObject copy = raw.deepCopy();
        for (String key : keys) {
            copy.remove(key);
        }
        return copy;
    }

    public String str(String key) {
        if (!raw.has(key) || raw.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = raw.get(key);
        if (!el.isJsonPrimitive()) {
            return null;
        }
        return el.getAsString();
    }

    public boolean bool(String key) {
        if (!raw.has(key) || raw.get(key).isJsonNull()) {
            return false;
        }
        JsonElement el = raw.get(key);
        return el.isJsonPrimitive() && el.getAsBoolean();
    }

    public int intOr(String key, int def) throws McpArgumentException {
        if (!raw.has(key) || raw.get(key).isJsonNull()) {
            return def;
        }
        JsonElement el = raw.get(key);
        if (!el.isJsonPrimitive()) {
            return def;
        }
        try {
            return el.getAsInt();
        }
        catch (NumberFormatException e) {
            throw new McpArgumentException(-32602, "Invalid integer for parameter '" + key + "': " + el.getAsString());
        }
    }

    public int intOr(String key, int def, int min, int max) throws McpArgumentException {
        int val = intOr(key, def);
        if (val < min) {
            return min;
        }
        if (val > max) {
            return max;
        }
        return val;
    }

    public boolean has(String key) {
        return raw.has(key) && !raw.get(key).isJsonNull();
    }

    public JsonArray array(String key) {
        if (!raw.has(key) || !raw.get(key).isJsonArray()) {
            return null;
        }
        return raw.getAsJsonArray(key);
    }

    public JsonObject object(String key) {
        if (!raw.has(key) || !raw.get(key).isJsonObject()) {
            return null;
        }
        return raw.getAsJsonObject(key);
    }

    /**
     * Returns a validation error when a present, non-null argument is not a JSON string.
     */
    public String requireStringIfPresent(String key) {
        return typeError(key, "string", element -> element.isJsonPrimitive()
                         && element.getAsJsonPrimitive().isString());
    }

    /**
     * Returns a validation error when a present, non-null argument is not a JSON boolean.
     */
    public String requireBooleanIfPresent(String key) {
        return typeError(key, "boolean", element -> element.isJsonPrimitive()
                         && element.getAsJsonPrimitive().isBoolean());
    }

    /**
     * Returns a validation error when a present, non-null argument is not a JSON object.
     */
    public String requireObjectIfPresent(String key, String expectedDescription) {
        if (!has(key) || raw.get(key).isJsonObject()) {
            return null;
        }
        return key + " must be a " + expectedDescription + ", not a " + jsonType(raw.get(key));
    }

    /**
     * Returns a validation error when a present, non-null argument is not an array whose every entry is a string.
     */
    public String requireStringArrayIfPresent(String key) {
        if (!has(key)) {
            return null;
        }
        JsonElement element = raw.get(key);
        if (!element.isJsonArray()) {
            return key + " must be an array of strings, not a " + jsonType(element);
        }
        int index = 0;
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                return key + " must be an array of strings (entry " + index + " is a " + jsonType(item) + ")";
            }
            index++;
        }
        return null;
    }

    private String typeError(String key, String expectedType, java.util.function.Predicate<JsonElement> expected) {
        if (!has(key) || expected.test(raw.get(key))) {
            return null;
        }
        return key + " must be a " + expectedType + ", not a " + jsonType(raw.get(key));
    }

    private static String jsonType(JsonElement element) {
        if (element.isJsonObject()) {
            return "object";
        }
        if (element.isJsonArray()) {
            return "array";
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                return "string";
            }
            if (element.getAsJsonPrimitive().isBoolean()) {
                return "boolean";
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return "number";
            }
        }
        return "null";
    }

    public String require(String key) throws McpArgumentException {
        String value = str(key);
        if (value == null || value.isBlank()) {
            throw new McpArgumentException(-32602, key + " is required");
        }
        return value;
    }
}
