package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

public abstract class AbstractBuildTool implements McpToolInterface {

    private final McpSectionEnum section;
    private final String toolName;
    private final String description;
    private final String instruction;
    private final String instructionMcpOnly;

    protected AbstractBuildTool(McpSectionEnum section, String toolName, String description, String instruction) {
        this(section, toolName, description, instruction, instruction);
    }

    protected AbstractBuildTool(McpSectionEnum section, String toolName, String description, String instruction, String instructionMcpOnly) {
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
        JsonObject pp = new JsonObject();
        pp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        pp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Absolute path to the project root directory. "
                + "Omit to auto-detect from the main (bold) project or first matching open project.");
        props.add(McpToolPropertyEnum.PROJECT_PATH.key(), pp);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }
}
