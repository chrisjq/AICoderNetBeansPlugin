package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileDirEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileSpooler;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ProjectPathParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;

public class GetGitDiffTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return McpToolEnum.GET_GIT_DIFF.toolName() + " - shows unstaged or staged changes. "
                   + "Requires " + ProjectPathParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root.";
        }
        return McpToolEnum.GET_GIT_DIFF.toolName() + " -> INSTEAD OF Bash git diff - shows unstaged or staged changes. "
               + "Requires " + ProjectPathParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_GIT_DIFF.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Shows unstaged or staged changes (set " + GetGitDiffParamEnum.STAGED.key() + "=true for staged diff).");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject staged = new JsonObject();
        staged.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        staged.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Show staged diff instead of unstaged. Default: false.");
        props.add(GetGitDiffParamEnum.STAGED.key(), staged);
        JsonObject filePaths = new JsonObject();
        filePaths.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        filePaths.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Optional paths to include (absolute or project-relative). Omit or pass an empty array for the whole repository.");
        JsonObject filePathItems = new JsonObject();
        filePathItems.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        filePaths.add(ToolSchemaKeyEnum.ITEMS.key(), filePathItems);
        props.add(GetGitDiffParamEnum.FILE_PATHS.key(), filePaths);
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Target git repository or project root; relative paths resolve against the default project.");
        props.add(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(ProjectPathParamEnum.PROJECT_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String output = GitProvider.getGitDiff(args.require(ProjectPathParamEnum.PROJECT_PATH.key()),
                args.bool(GetGitDiffParamEnum.STAGED.key()),
                GitReadFilePaths.optional(args, GetGitDiffParamEnum.FILE_PATHS.key()), session.getId());
        return TempFileSpooler.spoolIfLarge(session.getId(), TempFileDirEnum.TOOL_RESULTS, "git-diff", ".log", output,
                TempFileSpooler.DEFAULT_RESULT_SPOOL_THRESHOLD_CHARS);
    }
}
