package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitStashTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitStash -> INSTEAD OF Bash git stash - stash, list, pop, apply, or drop stashed changes";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_STASH.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Manages git stash. Actions: push (default), list, pop, apply, drop. "
                + "Equivalent to: git stash [push|list|pop|apply|drop]");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject action = new JsonObject();
        action.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        action.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Action: push (default), list, pop, apply, drop.");
        props.add(GitStashParamEnum.ACTION.key(), action);
        JsonObject index = new JsonObject();
        index.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        index.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Stash index for pop/apply/drop. Default: 0.");
        props.add(GitStashParamEnum.INDEX.key(), index);
        JsonObject message = new JsonObject();
        message.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        message.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Message for stash push. Default: WIP.");
        props.add(GitStashParamEnum.MESSAGE.key(), message);
        JsonObject includeUntracked = new JsonObject();
        includeUntracked.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        includeUntracked.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Include untracked files in stash push. Default: false.");
        props.add(GitStashParamEnum.INCLUDE_UNTRACKED.key(), includeUntracked);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String action = args.str(GitStashParamEnum.ACTION.key());
        if (action == null) {
            action = "push";
        }
        if (!List.of("push", "list", "pop", "apply", "drop").contains(action)) {
            return "Invalid action '" + action + "'. Must be one of: push, list, pop, apply, drop";
        }
        int index = args.intOr(GitStashParamEnum.INDEX.key(), 0, 0, Integer.MAX_VALUE);
        String message = args.str(GitStashParamEnum.MESSAGE.key());
        boolean includeUntracked = args.bool(GitStashParamEnum.INCLUDE_UNTRACKED.key());
        return GitProvider.gitStash(action, index, message, includeUntracked);
    }
}
