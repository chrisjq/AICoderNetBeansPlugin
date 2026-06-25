package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitPushTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitPush -> INSTEAD OF Bash git push - pushes current branch to remote";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_PUSH.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Pushes the current branch (or specified branch) to a remote. "
                + "Defaults to pushing the active branch to 'origin'.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject remote = new JsonObject();
        remote.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        remote.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Remote name. Default: origin.");
        props.add(GitPushParamEnum.REMOTE.key(), remote);
        JsonObject branch = new JsonObject();
        branch.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        branch.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Branch to push. Default: current active branch.");
        props.add(GitPushParamEnum.BRANCH.key(), branch);
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
        String remote = args.str(GitPushParamEnum.REMOTE.key());
        if (remote == null) {
            remote = "origin";
        }
        return GitProvider.gitPush(remote, args.str(GitPushParamEnum.BRANCH.key()));
    }
}
