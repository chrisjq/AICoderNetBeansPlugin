package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;

/**
 * Turns one of the plugin's own MCP tool definitions into something a wider range of OpenAI-compatible servers will
 * accept: it strips the keywords only some servers understand, collapses schemas that nest deeper than a server can
 * handle, and shortens descriptions so a verbose tool cannot dominate the prompt (spec §4 item 7, §5).
 *
 * <p>
 * Pure: it returns a new object and never modifies or throws on the input.
 */
public final class ToolSchemaSanitiser {

    /**
     * Default nesting depth a schema may keep before deeper nodes are collapsed.
     */
    public static final int DEFAULT_DEPTH_LIMIT = 2;
    /**
     * Default cap for the tool's own description.
     */
    public static final int DEFAULT_DESCRIPTION_CAP = 1024;
    /**
     * Default cap for a parameter's description.
     */
    public static final int DEFAULT_PARAMETER_DESCRIPTION_CAP = 256;

    private static final String ELLIPSIS = "\u2026";
    private static final String KEY_STRICT = "strict";
    private static final String KEY_SCHEMA = "$schema";
    private static final String KEY_ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_PARAMETERS = "parameters";
    private static final String KEY_INPUT_SCHEMA = "inputSchema";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_ANY_OF = "anyOf";
    private static final String KEY_ONE_OF = "oneOf";
    private static final String KEY_TYPE = "type";
    private static final String TYPE_OBJECT = "object";

    private ToolSchemaSanitiser() {
    }

    /**
     * Sanitises {@code function} with the default depth and description caps.
     */
    public static JsonObject sanitise(JsonObject function) {
        return sanitise(function, DEFAULT_DEPTH_LIMIT, DEFAULT_DESCRIPTION_CAP, DEFAULT_PARAMETER_DESCRIPTION_CAP);
    }

    /**
     * The three limits a sanitise call applies. {@link #fromPluginSettings()} reads them from the plugin's settings.
     *
     * @param depthLimit deepest schema level kept before deeper nodes are collapsed
     * @param descriptionCap longest the tool's own description may be
     * @param parameterDescriptionCap longest a parameter description may be
     */
    public record Limits(int depthLimit, int descriptionCap, int parameterDescriptionCap) {

        /**
         * The schema depth and description caps currently configured for OpenAI Compatible sessions.
         */
        public static Limits fromPluginSettings() {
            return new Limits(OpenAiCompatiblePluginSettings.getSchemaDepth(),
                              OpenAiCompatiblePluginSettings.getToolDescriptionCapChars(),
                              OpenAiCompatiblePluginSettings.getParameterDescriptionCapChars());
        }
    }

    /**
     * Sanitises {@code function} with the given limits; null limits use the defaults.
     */
    public static JsonObject sanitise(JsonObject function, Limits limits) {
        Limits applied = limits == null
                         ? new Limits(DEFAULT_DEPTH_LIMIT, DEFAULT_DESCRIPTION_CAP, DEFAULT_PARAMETER_DESCRIPTION_CAP)
                         : limits;
        return sanitise(function, applied.depthLimit(), applied.descriptionCap(), applied.parameterDescriptionCap());
    }

    /**
     * @param function a tool definition as {@code {name, description, parameters:{…}}}
     * @param depthLimit deepest schema level kept before deeper nodes are collapsed
     * @param descriptionCap longest the tool's own description may be
     * @param parameterDescriptionCap longest a parameter description may be
     *
     * @return a new sanitised function; the input is never modified
     */
    public static JsonObject sanitise(JsonObject function, int depthLimit, int descriptionCap,
                                      int parameterDescriptionCap) {
        if (function == null) {
            return new JsonObject();
        }
        try {
            JsonObject result = function.deepCopy();
            result.remove(KEY_STRICT);
            result.remove(KEY_SCHEMA);
            capDescription(result, descriptionCap);

            String schemaKey = result.has(KEY_PARAMETERS) ? KEY_PARAMETERS
                               : result.has(KEY_INPUT_SCHEMA) ? KEY_INPUT_SCHEMA : null;
            if (schemaKey != null && result.get(schemaKey).isJsonObject()) {
                result.add(schemaKey, clean(result.getAsJsonObject(schemaKey), 0, depthLimit, parameterDescriptionCap));
            }
            return result;
        }
        catch (RuntimeException ex) {
            return function;
        }
    }

    private static JsonObject clean(JsonObject node, int depth, int depthLimit, int parameterDescriptionCap) {
        JsonObject obj = node.deepCopy();
        obj.remove(KEY_STRICT);
        obj.remove(KEY_SCHEMA);
        removeAdditionalPropertiesFalse(obj);
        capDescription(obj, parameterDescriptionCap);

        if (obj.has(KEY_PROPERTIES) && obj.get(KEY_PROPERTIES).isJsonObject()) {
            JsonObject properties = obj.getAsJsonObject(KEY_PROPERTIES);
            JsonObject cleaned = new JsonObject();
            for (String name : properties.keySet()) {
                JsonElement child = properties.get(name);
                cleaned.add(name, depth + 1 > depthLimit ? objectPlaceholder()
                                  : cleanChild(child, depth + 1, depthLimit, parameterDescriptionCap));
            }
            obj.add(KEY_PROPERTIES, cleaned);
        }

        if (obj.has(KEY_ITEMS)) {
            JsonElement items = obj.get(KEY_ITEMS);
            obj.add(KEY_ITEMS, depth + 1 > depthLimit ? objectPlaceholder()
                               : cleanChild(items, depth + 1, depthLimit, parameterDescriptionCap));
        }

        collapseChoice(obj, KEY_ANY_OF, depth, depthLimit, parameterDescriptionCap);
        collapseChoice(obj, KEY_ONE_OF, depth, depthLimit, parameterDescriptionCap);
        return obj;
    }

    private static JsonElement cleanChild(JsonElement child, int depth, int depthLimit, int parameterDescriptionCap) {
        if (child != null && child.isJsonObject()) {
            return clean(child.getAsJsonObject(), depth, depthLimit, parameterDescriptionCap);
        }
        return child == null ? null : child.deepCopy();
    }

    private static void collapseChoice(JsonObject obj, String key, int depth, int depthLimit,
                                       int parameterDescriptionCap) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return;
        }
        JsonArray branches = obj.getAsJsonArray(key);
        JsonArray kept = new JsonArray();
        if (depth + 1 > depthLimit) {
            if (branches.size() > 0) {
                kept.add(cleanChild(branches.get(0), depth, depthLimit, parameterDescriptionCap));
            }
        }
        else {
            for (JsonElement branch : branches) {
                kept.add(cleanChild(branch, depth + 1, depthLimit, parameterDescriptionCap));
            }
        }
        obj.add(key, kept);
    }

    private static void removeAdditionalPropertiesFalse(JsonObject obj) {
        if (!obj.has(KEY_ADDITIONAL_PROPERTIES)) {
            return;
        }
        JsonElement additional = obj.get(KEY_ADDITIONAL_PROPERTIES);
        if (additional.isJsonPrimitive() && additional.getAsJsonPrimitive().isBoolean()
                && !additional.getAsBoolean()) {
            obj.remove(KEY_ADDITIONAL_PROPERTIES);
        }
    }

    private static void capDescription(JsonObject obj, int cap) {
        if (cap <= 0 || !obj.has(KEY_DESCRIPTION)) {
            return;
        }
        JsonElement description = obj.get(KEY_DESCRIPTION);
        if (description.isJsonPrimitive() && description.getAsJsonPrimitive().isString()) {
            obj.addProperty(KEY_DESCRIPTION, capText(description.getAsString(), cap));
        }
    }

    static String capText(String text, int cap) {
        if (text == null || cap <= 0 || text.length() <= cap) {
            return text;
        }
        String window = text.substring(0, cap);
        int sentenceEnd = window.lastIndexOf(". ");
        if (sentenceEnd >= 0) {
            return window.substring(0, sentenceEnd + 1);
        }
        int lastSpace = window.lastIndexOf(' ');
        if (lastSpace > 0) {
            return window.substring(0, lastSpace) + ELLIPSIS;
        }
        return window + ELLIPSIS;
    }

    private static JsonObject objectPlaceholder() {
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty(KEY_TYPE, TYPE_OBJECT);
        return placeholder;
    }
}
