package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

/**
 * Builds credential-free MCP tool documentation from the live tool registry.
 */
public final class McpToolsDocumentation {

    private static final EnumSet<McpInstructionOptionEnum> DOCUMENTATION_OPTIONS
            = EnumSet.of(McpInstructionOptionEnum.TOOL_INSTRUCTION);

    public static String buildHtml() {
        Map<McpToolEnum, McpToolInterface> handlers = ToolHandlerFactory.getToolHandlers(null);
        Map<McpSectionEnum, StringBuilder> sections = new LinkedHashMap<>();
        for (McpSectionEnum section : McpSectionEnum.values()) {
            sections.put(section, new StringBuilder());
        }
        for (McpToolEnum tool : McpToolEnum.values()) {
            McpToolInterface handler = handlers.get(tool);
            if (handler == null || handler.section() == null) {
                continue;
            }
            JsonObject schema = handler.schema(DOCUMENTATION_OPTIONS);
            removeCredentials(schema);
            String description = schema.has(ToolSchemaKeyEnum.DESCRIPTION.key())
                    ? schema.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString() : "";
            StringBuilder section = sections.get(handler.section());
            section.append("<h3>").append(escape(tool.toolName())).append("</h3>");
            if (!description.isBlank()) {
                section.append("<p>").append(escape(redactCredentialReferences(description))).append("</p>");
            }
            appendProperties(section, schema);
        }

        StringBuilder html = new StringBuilder("<html><body>")
                .append("<h1>MCP Tools</h1><p>Tool documentation is generated from the current MCP handlers. Session credentials are intentionally omitted.</p>");
        for (Map.Entry<McpSectionEnum, StringBuilder> entry : sections.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                html.append("<h2>").append(escape(entry.getKey().title())).append("</h2>")
                        .append(entry.getValue());
            }
        }
        return html.append("</body></html>").toString();
    }

    private static void appendProperties(StringBuilder html, JsonObject schema) {
        JsonObject input = schema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject properties = input == null ? null : input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        if (properties == null || properties.isEmpty()) {
            html.append("<p>No parameters.</p>");
            return;
        }
        JsonArray required = input.has(ToolSchemaKeyEnum.REQUIRED.key())
                ? input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key()) : null;
        html.append("<ul>");
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            JsonObject property = entry.getValue().getAsJsonObject();
            html.append("<li><b>").append(escape(entry.getKey())).append("</b>");
            appendPropertyDetails(html, property, isRequired(required, entry.getKey()));
            html.append("</li>");
        }
        html.append("</ul>");
    }

    private static void appendPropertyDetails(StringBuilder html, JsonObject property, boolean required) {
        if (property.has(ToolSchemaKeyEnum.DESCRIPTION.key())) {
            html.append(" - ").append(escape(redactCredentialReferences(
                    property.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString())));
        }
        if (property.has(ToolSchemaKeyEnum.TYPE.key())) {
            html.append(" (").append(escape(property.get(ToolSchemaKeyEnum.TYPE.key()).getAsString()));
            if (required) {
                html.append(", required");
            }
            if (property.has(ToolSchemaKeyEnum.DEFAULT.key())) {
                html.append(", default: ").append(escape(property.get(ToolSchemaKeyEnum.DEFAULT.key()).toString()));
            }
            html.append(")");
        }
        else if (required) {
            html.append(" (required)");
        }
    }

    private static boolean isRequired(JsonArray required, String name) {
        if (required == null) {
            return false;
        }
        for (JsonElement element : required) {
            if (name.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static void removeCredentials(JsonObject schema) {
        if (schema == null || !schema.has(ToolSchemaKeyEnum.INPUT_SCHEMA.key())) {
            return;
        }
        JsonObject input = schema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        if (input == null) {
            return;
        }
        if (input.has(ToolSchemaKeyEnum.PROPERTIES.key())) {
            input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key()).remove(McpToolPropertyEnum.SESSION_ID.key());
            input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key()).remove(McpToolPropertyEnum.SECRET_KEY.key());
        }
        if (input.has(ToolSchemaKeyEnum.REQUIRED.key()) && input.get(ToolSchemaKeyEnum.REQUIRED.key()).isJsonArray()) {
            JsonArray required = input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
            for (int index = required.size() - 1; index >= 0; index--) {
                String name = required.get(index).getAsString();
                if (McpToolPropertyEnum.SESSION_ID.key().equals(name)
                        || McpToolPropertyEnum.SECRET_KEY.key().equals(name)) {
                    required.remove(index);
                }
            }
        }
    }

    private static String redactCredentialReferences(String value) {
        return value.replaceAll("(?i)session\\s*id", "current session")
                .replaceAll("(?i)secret\\s*key", "current session credential");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private McpToolsDocumentation() {
    }
}
