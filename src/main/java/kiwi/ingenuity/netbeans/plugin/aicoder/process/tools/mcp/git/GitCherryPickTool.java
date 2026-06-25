package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
public class GitCherryPickTool implements McpToolInterface {

    private static final Set<String> VALID_OPERATIONS = Set.of("BEGIN", "CONTINUE", "QUIT", "ABORT");

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitCherryPick -> INSTEAD OF Bash git cherry-pick - applies commits onto current branch; supports BEGIN/CONTINUE/QUIT/ABORT";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_CHERRY_PICK.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Cherry-picks one or more commits onto the current branch. "
                + "operation=BEGIN applies the given revisions. CONTINUE/QUIT/ABORT manage conflicts. "
                + "Equivalent to: git cherry-pick <revisions>");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject operation = new JsonObject();
        operation.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        operation.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Operation: BEGIN (default), CONTINUE, QUIT, ABORT.");
        props.add(GitCherryPickParamEnum.OPERATION.key(), operation);
        JsonObject revisions = new JsonObject();
        revisions.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        revisions.add(ToolSchemaKeyEnum.ITEMS.key(), items);
        revisions.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Commit hashes to cherry-pick. Required for BEGIN.");
        props.add(GitCherryPickParamEnum.REVISIONS.key(), revisions);
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
        String operation = args.str(GitCherryPickParamEnum.OPERATION.key());
        com.google.gson.JsonArray arr = args.array(GitCherryPickParamEnum.REVISIONS.key());
        List<String> revisions = null;
        if (arr != null) {
            revisions = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                revisions.add(arr.get(i).getAsString());
            }
        }

        if (operation != null && !VALID_OPERATIONS.contains(operation)) {
            return "Error: unsupported operation '" + operation + "'. Valid values: BEGIN, CONTINUE, QUIT, ABORT";
        }
        if ("BEGIN".equals(operation) && (revisions == null || revisions.isEmpty())) {
            return "Error: revisions are required for operation=BEGIN";
        }

        return GitProvider.gitCherryPick(operation, revisions);
    }
}
