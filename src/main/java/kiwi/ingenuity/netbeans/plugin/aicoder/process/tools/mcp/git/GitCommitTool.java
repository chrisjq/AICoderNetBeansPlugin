package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitCommitTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitCommit -> INSTEAD OF Bash git commit - commits staged changes with a message; "
                + "optionally stages files first via the files parameter";
    }

    @Override
    public JsonObject schema() {
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
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GitCommitParamEnum.MESSAGE.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
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
        return GitProvider.gitCommit(message, files);
    }
}
