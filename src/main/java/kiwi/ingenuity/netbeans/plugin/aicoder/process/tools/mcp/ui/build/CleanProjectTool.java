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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.BuildSubmitter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ProjectActionProvider;
import org.netbeans.spi.project.ActionProvider;

public class CleanProjectTool extends AbstractActionTool {

    public CleanProjectTool() {
        super(McpSectionEnum.UI_BUILD,
              McpToolEnum.CLEAN_PROJECT.toolName(),
              "Triggers the user's IDE Clean action for the required " + GitCommonParamEnum.PROJECT_PATH.key()
              + " and shows results in the Output window. Waits for the clean to finish and returns its result where "
              + "the project reports progress; otherwise returns as soon as it is triggered, saying so. Takes NO "
              + "options — it runs the project's generic IDE action, which has no argument channel. Use "
              + McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName() + " / " + McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT.toolName()
              + " / " + McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT.toolName()
              + " instead for options and an AI-readable result summary and log.",
              McpToolEnum.CLEAN_PROJECT.toolName() + " -> INSTEAD OF Bash clean command - requires "
              + GitCommonParamEnum.PROJECT_PATH.key() + "; triggers the user's IDE Clean action"
              + BuildSubmitter.QUEUE_INSTRUCTION,
              McpToolEnum.CLEAN_PROJECT.toolName() + " - requires " + GitCommonParamEnum.PROJECT_PATH.key()
              + "; triggers the user's IDE Clean action" + BuildSubmitter.QUEUE_INSTRUCTION);
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
        props.add(GitCommonParamEnum.PROJECT_PATH.key(), projectPath);
        props.add(McpToolPropertyEnum.ASYNC.key(), IdeActionSchema.asyncProperty());
        JsonArray required = new JsonArray();
        required.add(GitCommonParamEnum.PROJECT_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String projectPath = args.str(GitCommonParamEnum.PROJECT_PATH.key());
        return BuildSubmitter.submitIdeAction(
                McpToolEnum.CLEAN_PROJECT.toolName(), args, projectPath,
                ProjectActionProvider.prepareAction(session.getId(), projectPath, ActionProvider.COMMAND_CLEAN),
                session, () -> ProjectActionProvider.cleanProjectResult(session.getId(), projectPath));
    }
}
