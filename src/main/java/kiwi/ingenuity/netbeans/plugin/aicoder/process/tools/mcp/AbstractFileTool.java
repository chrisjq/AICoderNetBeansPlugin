package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

public abstract class AbstractFileTool implements McpToolInterface {

    private final McpSectionEnum section;
    private final String toolName;
    private final String description;
    private final String instruction;
    private final String instructionMcpOnly;

    protected AbstractFileTool(McpSectionEnum section, String toolName, String description, String instruction) {
        this(section, toolName, description, instruction, instruction);
    }

    protected AbstractFileTool(McpSectionEnum section, String toolName, String description, String instruction, String instructionMcpOnly) {
        this.section = section;
        this.toolName = toolName;
        this.description = description;
        this.instruction = instruction;
        this.instructionMcpOnly = instructionMcpOnly;
    }

    @Override
    public McpSectionEnum section() {
        return section;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS) ? instructionMcpOnly : instruction;
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), toolName);
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file. Required — this tool does not fall back to the focused editor. Call GetCurrentFile if you want the file the user is looking at.");
        props.add(McpToolPropertyEnum.FILE_PATH.key(), fp);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }
}
