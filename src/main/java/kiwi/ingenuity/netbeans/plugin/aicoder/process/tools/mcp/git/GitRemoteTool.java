package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitRemoteTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitRemote -> INSTEAD OF Bash git remote - list, add, or remove git remotes";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_REMOTE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Manages git remotes. action=list (default) lists all remotes with URLs; "
                + "add adds a new remote; remove deletes one. "
                + "Equivalent to: git remote [-v|add|remove] [name] [url]");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject action = new JsonObject();
        action.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        action.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Action: list (default), add, remove.");
        props.add(GitRemoteParamEnum.ACTION.key(), action);
        JsonObject name = new JsonObject();
        name.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        name.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Remote name. Required for add and remove.");
        props.add(GitRemoteParamEnum.NAME.key(), name);
        JsonObject url = new JsonObject();
        url.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        url.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Remote URL. Required for add.");
        props.add(GitRemoteParamEnum.URL.key(), url);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String action = args.str(GitRemoteParamEnum.ACTION.key());
        String name = args.str(GitRemoteParamEnum.NAME.key());
        String url = args.str(GitRemoteParamEnum.URL.key());

        if ("add".equals(action) && (name == null || name.isBlank())) {
            return "Error: name is required for action=add";
        }
        if ("add".equals(action) && (url == null || url.isBlank())) {
            return "Error: url is required for action=add";
        }
        if ("remove".equals(action) && (name == null || name.isBlank())) {
            return "Error: name is required for action=remove";
        }

        return GitProvider.gitRemote(action, name, url);
    }
}
