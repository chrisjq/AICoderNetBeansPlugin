package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
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

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitCommitTool implements McpToolInterface {

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
            return "GitCommit - commits staged changes with a message; "
                    + "optionally stages files first via the files parameter. "
                    + "Requires projectPath to select the target git repository or project root.";
        }
        return "GitCommit -> INSTEAD OF Bash git commit - commits staged changes with a message; "
                + "optionally stages files first via the files parameter. "
                + "Requires projectPath to select the target git repository or project root.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_COMMIT.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Commits staged changes with the given message (git commit -m). "
                + "If files is provided, stages those files first (git add) before committing. "
                + "Pass files=[\".\"] to stage all changes then commit in one step.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        message.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Commit message.");
        props.add(GitCommitParamEnum.MESSAGE.key(), message);
        JsonObject files = new JsonObject();
        files.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        files.add(ToolSchemaKeyEnum.ITEMS.key(), items);
        files.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Optional file paths to stage before committing. Omit to commit already-staged changes.");
        props.add(GitCommitParamEnum.FILES.key(), files);
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Path to the target git repository or project root (relative paths are resolved "
                + "against the default project). Required — selects which project/repo to operate "
                + "on when multiple are open, or when the repo lives outside any open project.");
        props.add(GitCommonParamEnum.PROJECT_PATH.key(), projectPath);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GitCommitParamEnum.MESSAGE.key());
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
        String message = args.require(GitCommitParamEnum.MESSAGE.key());
        JsonArray arr = args.array(GitCommitParamEnum.FILES.key());
        List<String> files = null;
        if (arr != null && !arr.isEmpty()) {
            files = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (el.isJsonPrimitive()) {
                    files.add(el.getAsString());
                }
            }
        }
        return GitProvider.gitCommit(
                args.require(GitCommonParamEnum.PROJECT_PATH.key()),
                message,
                files,
                session.getId());
    }
}
