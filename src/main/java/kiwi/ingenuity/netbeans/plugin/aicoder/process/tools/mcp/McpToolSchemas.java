package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;

/** Credential (sessionId/secretKey) schema injection for MCP tools. */
public final class McpToolSchemas {
    private McpToolSchemas() {
    }

    /** If options contains CREDENTIALS, inject sessionId/secretKey; else return unchanged. */
    public static JsonObject applyCredentialsIfRequested(JsonObject toolSchema, Set<McpInstructionOptionEnum> options) {
        Objects.requireNonNull(options, "options");
        return options.contains(McpInstructionOptionEnum.CREDENTIALS) ? injectCredentials(toolSchema) : toolSchema;
    }

    /** Unconditionally inject sessionId + secretKey into the tool's inputSchema (idempotent). */
    public static JsonObject injectCredentials(JsonObject toolSchema) {
        if (toolSchema == null || !toolSchema.has(ToolSchemaKeyEnum.INPUT_SCHEMA.key())) {
            return toolSchema;
        }
        JsonObject inputSchema = toolSchema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        if (inputSchema == null) {
            return toolSchema;
        }
        if (!inputSchema.has(ToolSchemaKeyEnum.PROPERTIES.key()) || !inputSchema.get(ToolSchemaKeyEnum.PROPERTIES.key()).isJsonObject()) {
            inputSchema.add(ToolSchemaKeyEnum.PROPERTIES.key(), new JsonObject());
        }
        JsonObject props = inputSchema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        if (!props.has("sessionId")) {
            JsonObject p = new JsonObject();
            p.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            p.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your session ID (from your session identity block).");
            props.add("sessionId", p);
        }
        if (!props.has("secretKey")) {
            JsonObject p = new JsonObject();
            p.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            p.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key (from your session identity block).");
            props.add("secretKey", p);
        }
        if (!inputSchema.has(ToolSchemaKeyEnum.REQUIRED.key()) || !inputSchema.get(ToolSchemaKeyEnum.REQUIRED.key()).isJsonArray()) {
            inputSchema.add(ToolSchemaKeyEnum.REQUIRED.key(), new JsonArray());
        }
        JsonArray required = inputSchema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        boolean hasSessionId = false;
        boolean hasSecretKey = false;
        for (JsonElement el : required) {
            if (el.isJsonPrimitive() && "sessionId".equals(el.getAsString())) {
                hasSessionId = true;
            }
            if (el.isJsonPrimitive() && "secretKey".equals(el.getAsString())) {
                hasSecretKey = true;
            }
        }
        if (!hasSessionId) {
            required.add("sessionId");
        }
        if (!hasSecretKey) {
            required.add("secretKey");
        }
        return toolSchema;
    }
}
