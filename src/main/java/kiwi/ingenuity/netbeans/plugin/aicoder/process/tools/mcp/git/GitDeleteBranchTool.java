package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitDeleteBranchTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitDeleteBranch -> INSTEAD OF Bash git branch -d/-D - deletes a local branch";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_DELETE_BRANCH.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Deletes a local git branch. Use force=true to delete unmerged branches.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject branch = new JsonObject();
        branch.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        branch.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Name of the branch to delete.");
        props.add(GitDeleteBranchParamEnum.BRANCH.key(), branch);
        JsonObject force = new JsonObject();
        force.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        force.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Force delete even if not merged. Default: false.");
        props.add(GitDeleteBranchParamEnum.FORCE.key(), force);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        com.google.gson.JsonArray req = new com.google.gson.JsonArray();
        req.add(GitDeleteBranchParamEnum.BRANCH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), req);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String branch = args.require(GitDeleteBranchParamEnum.BRANCH.key());
        boolean force = args.bool(GitDeleteBranchParamEnum.FORCE.key());
        return GitProvider.gitDeleteBranch(branch, force);
    }
}
