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
import java.util.ArrayList;
import java.util.List;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitResetTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitReset -> INSTEAD OF Bash git reset - unstage files or reset HEAD to a revision (SOFT/MIXED/HARD)";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_RESET.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Resets files or HEAD. With files: unstages those files. "
                + "Without files: resets HEAD using type (SOFT/MIXED/HARD). "
                + "Equivalent to: git reset [--soft|--mixed|--hard] [revision] [files]");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject files = new JsonObject();
        files.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        files.add(ToolSchemaKeyEnum.ITEMS.key(), items);
        files.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Specific files to unstage. Omit to do a full reset.");
        props.add(GitResetParamEnum.FILES.key(), files);
        JsonObject revision = new JsonObject();
        revision.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        revision.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Revision to reset to. Default: HEAD.");
        props.add(GitResetParamEnum.REVISION.key(), revision);
        JsonObject type = new JsonObject();
        type.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        type.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Reset type for full reset: SOFT, MIXED (default), or HARD.");
        props.add(GitResetParamEnum.TYPE.key(), type);
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
        com.google.gson.JsonArray arr = args.array(GitResetParamEnum.FILES.key());
        List<String> files = null;
        if (arr != null) {
            files = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                files.add(arr.get(i).getAsString());
            }
        }
        return GitProvider.gitReset(files, args.str(GitResetParamEnum.REVISION.key()), args.str(GitResetParamEnum.TYPE.key()));
    }
}
