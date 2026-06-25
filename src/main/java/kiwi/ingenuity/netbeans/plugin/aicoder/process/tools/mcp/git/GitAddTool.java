package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitAddTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitAdd -> INSTEAD OF Bash git add - stages files for commit; pass [\".\" ] to stage all";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_ADD.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Stages one or more files for the next commit (git add). "
                + "Pass [\".\" ] in files to stage all changes.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject files = new JsonObject();
        files.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        files.add(ToolSchemaKeyEnum.ITEMS.key(), items);
        files.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "File paths to stage. Use [\".\" ] to stage all changes.");
        props.add(GitAddParamEnum.FILES.key(), files);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GitAddParamEnum.FILES.key());
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
        JsonArray arr = args.array(GitAddParamEnum.FILES.key());
        if (arr == null || arr.isEmpty()) {
            throw new McpArgumentException(-32602, "files is required and must be non-empty");
        }
        List<String> files = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el.isJsonPrimitive()) {
                files.add(el.getAsString());
            }
        }
        if (files.isEmpty()) {
            throw new McpArgumentException(-32602, "files must contain at least one non-null string path");
        }
        return GitProvider.gitAdd(files);
    }
}
