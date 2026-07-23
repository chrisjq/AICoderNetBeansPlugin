package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitMergeTool implements McpToolInterface {

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
            return "GitMerge - merges a branch into the current branch. "
                    + "Requires projectPath to select the target git repository or project root.";
        }
        return "GitMerge -> INSTEAD OF Bash git merge - merges a branch into the current branch. "
                + "Requires projectPath to select the target git repository or project root.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_MERGE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Merges the given branch into the current branch. Equivalent to: git merge <branch>");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject branch = new JsonObject();
        branch.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        branch.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Branch name or revision to merge.");
        props.add(GitMergeParamEnum.BRANCH.key(), branch);
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Path to the target git repository or project root (relative paths are resolved "
                + "against the default project). Required — selects which project/repo to operate "
                + "on when multiple are open, or when the repo lives outside any open project.");
        props.add(GitCommonParamEnum.PROJECT_PATH.key(), projectPath);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray req = new JsonArray();
        req.add(GitMergeParamEnum.BRANCH.key());
        req.add(GitCommonParamEnum.PROJECT_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), req);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        return GitProvider.gitMerge(args.require(GitCommonParamEnum.PROJECT_PATH.key()), args.require(GitMergeParamEnum.BRANCH.key()));
    }
}
