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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.BuildSubmitter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ProjectActionProvider;
import org.netbeans.spi.project.ActionProvider;

public class BuildProjectTool extends AbstractActionTool {

    public BuildProjectTool() {
        super(McpSectionEnum.UI_BUILD,
                McpToolEnum.BUILD_PROJECT.toolName(),
                "Triggers the IDE's Build action for the project; output goes to the Output window. Waits for "
                + "completion when possible, otherwise reports that it was triggered. No options; use "
                + McpToolEnum.BUILD_MAVEN_PROJECT.toolName()
                + " / " + McpToolEnum.BUILD_GRADLE_PROJECT.toolName() + " / " + McpToolEnum.BUILD_ANT_PROJECT.toolName()
                + " for goals/tasks/targets, skip-tests, and profiles.",
                McpToolEnum.BUILD_PROJECT.toolName() + " -> INSTEAD OF Bash build commands - requires "
                + ProjectPathParamEnum.PROJECT_PATH.key() + "; triggers the user's IDE Build action"
                + BuildSubmitter.QUEUE_INSTRUCTION,
                McpToolEnum.BUILD_PROJECT.toolName() + " - requires " + ProjectPathParamEnum.PROJECT_PATH.key()
                + "; triggers the user's IDE Build action" + BuildSubmitter.QUEUE_INSTRUCTION);
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
        props.add(McpToolPropertyEnum.ASYNC.key(), IdeActionSchema.asyncProperty());
        JsonArray required = new JsonArray();
        required.add(ProjectPathParamEnum.PROJECT_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        return tool;
    }

    @Override
    public boolean requiresGlobalMutationLock() {
        // BuildQueue serialises builds, so they must not hold the global mutation lock while waiting or running.
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String projectPath = args.str(ProjectPathParamEnum.PROJECT_PATH.key());
        return BuildSubmitter.submitIdeAction(
                McpToolEnum.BUILD_PROJECT.toolName(), args, projectPath,
                ProjectActionProvider.prepareAction(session.getId(), projectPath, ActionProvider.COMMAND_BUILD),
                session, () -> ProjectActionProvider.buildProjectResult(session.getId(), projectPath));
    }
}
