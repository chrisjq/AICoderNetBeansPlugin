package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitBranchTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitBranch -> INSTEAD OF Bash git branch - lists branches or creates a new one";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_BRANCH.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Lists local branches (current branch marked with *), or creates a new branch from HEAD. "
                + "Set all=true to include remote-tracking branches.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject all = new JsonObject();
        all.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        all.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "If true, includes remote-tracking branches. Default: false.");
        props.add(GitBranchParamEnum.ALL.key(), all);
        JsonObject create = new JsonObject();
        create.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        create.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "If provided, creates a new branch with this name from HEAD instead of listing.");
        props.add(GitBranchParamEnum.CREATE.key(), create);
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
        boolean all = args.bool(GitBranchParamEnum.ALL.key());
        String create = args.str(GitBranchParamEnum.CREATE.key());
        return GitProvider.gitBranch(all, create);
    }
}
