package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ProjectPathParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ProjectActionProvider;

public class RunProjectTool extends AbstractActionTool {

    public RunProjectTool() {
        super(McpSectionEnum.UI_BUILD,
                McpToolEnum.RUN_PROJECT.toolName(),
                "Triggers the IDE's Run Project action (Debug Project when " + McpToolPropertyEnum.DEBUG.key() + "=true), the same as the "
                + "user's Run menu. Fire-and-forget: returns at once, no output capture, and the AI cannot stop it.",
                McpToolEnum.RUN_PROJECT.toolName() + " -> triggers the IDE's Run Project (or Debug Project) action; fire-and-forget,"
                + " cannot be stopped by the AI");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = super.schema(options);
        JsonObject schema = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Required absolute path to the open project root.");
        props.add(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath);
        JsonObject debug = new JsonObject();
        debug.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        debug.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "true runs the IDE's Debug Project action instead of Run Project. Default false.");
        props.add(McpToolPropertyEnum.DEBUG.key(), debug);
        JsonArray required = new JsonArray();
        required.add(ProjectPathParamEnum.PROJECT_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        return tool;
    }

    @Override
    public boolean requiresGlobalMutationLock() {
        // Fire-and-forget like the other IDE action tools, but with no queue behind it at all — a run may
        // never finish, so this must never wait on the global lock either; nothing here touches files.
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String typeError = args.requireBooleanIfPresent(McpToolPropertyEnum.DEBUG.key());
        if (typeError != null) {
            return "Error: " + typeError;
        }
        String projectPath = args.str(ProjectPathParamEnum.PROJECT_PATH.key());
        boolean debug = args.bool(McpToolPropertyEnum.DEBUG.key());
        return ProjectActionProvider.runProject(session.getId(), projectPath, debug);
    }
}
