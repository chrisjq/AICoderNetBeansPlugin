package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
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
            return "GitLog - shows recent commit history (short hash + message). "
                    + "Requires projectPath to select the target git repository or project root. "
                    + "Optionally pass file to scope history to a single path (with follow=true to track it across renames).";
        }
        return "GitLog -> INSTEAD OF Bash git log - shows recent commit history (short hash + message). "
                + "Requires projectPath to select the target git repository or project root. "
                + "Optionally pass file to scope history to a single path (with follow=true to track it across renames).";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_LOG.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Returns recent commit history, newest first (short hash + subject). "
                + "Equivalent to: git log --oneline");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject limit = new JsonObject();
        limit.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        limit.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Maximum number of commits to return. Default: 20.");
        props.add(GitLogParamEnum.LIMIT.key(), limit);
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Path to the target git repository or project root (relative paths are resolved "
                + "against the default project). Required — selects which project/repo to operate "
                + "on when multiple are open, or when the repo lives outside any open project.");
        props.add(GitCommonParamEnum.PROJECT_PATH.key(), projectPath);
        JsonObject file = new JsonObject();
        file.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        file.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Optional. Scope history to a single file (like 'git log -- <file>'). Absolute path, "
                + "or relative to the project root. Omit to log the whole repository.");
        props.add(GitLogParamEnum.FILE.key(), file);
        JsonObject follow = new JsonObject();
        follow.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        follow.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Optional. When a file is given, follow it across renames (like 'git log --follow'). "
                + "Default: false. Ignored when no file is set.");
        props.add(GitLogParamEnum.FOLLOW.key(), follow);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GitCommonParamEnum.PROJECT_PATH.key());
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
        return GitProvider.gitLog(
                args.require(GitCommonParamEnum.PROJECT_PATH.key()),
                args.intOr(GitLogParamEnum.LIMIT.key(), 20, 1, 1000),
                args.str(GitLogParamEnum.FILE.key()),
                args.bool(GitLogParamEnum.FOLLOW.key()));
    }
}
