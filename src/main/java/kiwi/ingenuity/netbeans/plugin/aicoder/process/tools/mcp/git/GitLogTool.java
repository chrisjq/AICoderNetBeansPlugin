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

public class GitLogTool implements McpToolInterface {

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
            return McpToolEnum.GIT_LOG.toolName() + " - shows recent commit history (short hash + message). "
                   + "Requires " + ProjectPathParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root. "
                   + "Optionally pass " + GitLogParamEnum.FILE.key() + " or " + GitLogParamEnum.FILE_PATHS.key()
                   + " to scope history to specific path(s) (" + GitLogParamEnum.FOLLOW.key() + "=true tracks a single path across renames).";
        }
        return McpToolEnum.GIT_LOG.toolName() + " -> INSTEAD OF Bash git log - shows recent commit history (short hash + message). "
               + "Requires " + ProjectPathParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root. "
               + "Optionally pass " + GitLogParamEnum.FILE.key() + " or " + GitLogParamEnum.FILE_PATHS.key()
               + " to scope history to specific path(s) (" + GitLogParamEnum.FOLLOW.key() + "=true tracks a single path across renames).";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_LOG.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Returns recent commit history (short hash + subject, newest first).");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject limit = new JsonObject();
        limit.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        limit.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Maximum number of commits to return. Default 20, max 1000.");
        props.add(GitLogParamEnum.LIMIT.key(), limit);
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Target git repository or project root; relative paths resolve against the default project.");
        props.add(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath);
        JsonObject file = new JsonObject();
        file.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        file.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Single path to log (legacy; don't combine with " + GitLogParamEnum.FILE_PATHS.key() + ").");
        props.add(GitLogParamEnum.FILE.key(), file);
        JsonObject filePaths = new JsonObject();
        filePaths.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        filePaths.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Paths to limit the log to; don't combine with " + GitLogParamEnum.FILE.key() + ". Omit both for the whole repository.");
        JsonObject filePathItems = new JsonObject();
        filePathItems.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        filePaths.add(ToolSchemaKeyEnum.ITEMS.key(), filePathItems);
        props.add(GitLogParamEnum.FILE_PATHS.key(), filePaths);
        JsonObject follow = new JsonObject();
        follow.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        follow.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Follow a single path across renames (git log --" + GitLogParamEnum.FOLLOW.key()
                + "). Default: false. Ignored without paths; rejected for multiple paths.");
        props.add(GitLogParamEnum.FOLLOW.key(), follow);
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
        String output = GitProvider.gitLog(
                args.require(ProjectPathParamEnum.PROJECT_PATH.key()),
                args.intOr(GitLogParamEnum.LIMIT.key(), 20, 1, 1000),
                args.str(GitLogParamEnum.FILE.key()),
                GitReadFilePaths.optional(args, GitLogParamEnum.FILE_PATHS.key()),
                args.bool(GitLogParamEnum.FOLLOW.key()), session.getId());
        return TempFileSpooler.spoolIfLarge(session.getId(), TempFileDirEnum.TOOL_RESULTS, "git-log", ".log", output,
                TempFileSpooler.DEFAULT_RESULT_SPOOL_THRESHOLD_CHARS);
    }
}
