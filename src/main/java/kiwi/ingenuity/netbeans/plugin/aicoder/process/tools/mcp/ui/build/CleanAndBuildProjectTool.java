package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ProjectActionProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class CleanAndBuildProjectTool extends AbstractActionTool {

    public CleanAndBuildProjectTool() {
        super(McpSectionEnum.UI_BUILD,
                McpToolEnum.CLEAN_AND_BUILD_PROJECT.toolName(),
                "Triggers the user's IDE Clean and Build action for the required " + GitCommonParamEnum.PROJECT_PATH.key()
                + " and shows results in the Output window. Fire-and-forget; use devops build/test tools for an AI-readable result summary and log.",
                McpToolEnum.CLEAN_AND_BUILD_PROJECT.toolName() + " -> INSTEAD OF Bash clean then build - requires "
                + GitCommonParamEnum.PROJECT_PATH.key() + "; triggers the user's IDE Clean and Build action",
                McpToolEnum.CLEAN_AND_BUILD_PROJECT.toolName() + " - requires " + GitCommonParamEnum.PROJECT_PATH.key()
                + "; triggers the user's IDE Clean and Build action");
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
        JsonArray required = new JsonArray();
        required.add(GitCommonParamEnum.PROJECT_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return ProjectActionProvider.cleanAndBuildProject(session.getId(), args.str(GitCommonParamEnum.PROJECT_PATH.key()));
    }
}
