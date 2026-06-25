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

    public String require(String key) throws McpArgumentException {
        String value = str(key);
        if (value == null || value.isBlank()) {
            throw new McpArgumentException(-32602, key + " is required");
        }
        return value;
    }
}
