package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class JsonUtils {

    public static String getString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    public static long getLong(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return 0L;
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() ? el.getAsLong() : 0L;
    }

    private JsonUtils() {
    }
}
