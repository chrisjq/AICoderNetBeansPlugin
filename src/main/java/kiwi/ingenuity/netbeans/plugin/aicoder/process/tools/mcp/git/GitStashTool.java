package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitStashActionEnum;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitStashTool implements McpToolInterface {

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
            return "GitStash - stash, list, pop, apply, or drop stashed changes. "
                    + "Requires projectPath to select the target git repository or project root.";
        }
        return "GitStash -> INSTEAD OF Bash git stash - stash, list, pop, apply, or drop stashed changes. "
                + "Requires projectPath to select the target git repository or project root.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_STASH.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Manages git stash. Actions: " + GitStashActionEnum.actionList() + ". "
                + "Equivalent to: git stash [push|list|pop|apply|drop]");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject action = new JsonObject();
        action.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        action.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Action: " + GitStashActionEnum.actionList() + ".");
        props.add(GitStashParamEnum.ACTION.key(), action);
        JsonObject index = new JsonObject();
        index.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        index.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Stash index for pop/apply/drop. Default: 0.");
        props.add(GitStashParamEnum.INDEX.key(), index);
        JsonObject message = new JsonObject();
        message.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        message.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Message for stash push. Default: " + GitProvider.STASH_DEFAULT_MESSAGE + ".");
        props.add(GitStashParamEnum.MESSAGE.key(), message);
        JsonObject includeUntracked = new JsonObject();
        includeUntracked.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        includeUntracked.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Include untracked files in stash push. Default: false.");
        props.add(GitStashParamEnum.INCLUDE_UNTRACKED.key(), includeUntracked);
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Path to the target git repository or project root (relative paths are resolved "
                + "against the default project). Required — selects which project/repo to operate "
                + "on when multiple are open, or when the repo lives outside any open project.");
        props.add(GitCommonParamEnum.PROJECT_PATH.key(), projectPath);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GitCommonParamEnum.PROJECT_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String rawAction = args.str(GitStashParamEnum.ACTION.key());
        GitStashActionEnum action = GitStashActionEnum.from(rawAction);
        if (action == null) {
            return "Invalid action '" + rawAction + "'. Must be one of: "
                    + GitStashActionEnum.actionList();
        }
        int index = args.intOr(GitStashParamEnum.INDEX.key(), 0, 0, Integer.MAX_VALUE);
        String message = args.str(GitStashParamEnum.MESSAGE.key());
        boolean includeUntracked = args.bool(GitStashParamEnum.INCLUDE_UNTRACKED.key());
        // Passing the enum, not the raw string, so the provider cannot be
        // handed a value the tool never validated.
        return GitProvider.gitStash(args.require(GitCommonParamEnum.PROJECT_PATH.key()), action, index, message, includeUntracked);
    }
}
