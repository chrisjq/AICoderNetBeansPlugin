package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;

public abstract class AbstractFileTool implements McpToolInterface {

    private final McpSectionEnum section;
    private final String toolName;
    private final String description;
    private final String instruction;

    protected AbstractFileTool(McpSectionEnum section, String toolName, String description, String instruction) {
        this.section = section;
        this.toolName = toolName;
        this.description = description;
        this.instruction = instruction;
    }

    @Override
    public McpSectionEnum section() {
        return section;
    }

    @Override
    public String instruction() {
        return instruction;
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), toolName);
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file. Omit to use the current editor.");
        props.add("filePath", fp);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }
}
