package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitTagTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitTag -> INSTEAD OF Bash git tag - list, create, or delete git tags";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_TAG.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Manages git tags. action=list (default) lists all tags; create makes a new tag; delete removes one. "
                + "name is required for create and delete actions. "
                + "Equivalent to: git tag [-a|-d] [name] [revision]");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject action = new JsonObject();
        action.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        action.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Action: list (default), create, delete.");
        props.add(GitTagParamEnum.ACTION.key(), action);
        JsonObject name = new JsonObject();
        name.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        name.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Tag name. Required for create and delete.");
        props.add(GitTagParamEnum.NAME.key(), name);
        JsonObject revision = new JsonObject();
        revision.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        revision.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Revision to tag. Default: HEAD.");
        props.add(GitTagParamEnum.REVISION.key(), revision);
        JsonObject message = new JsonObject();
        message.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        message.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Annotation message for annotated tags.");
        props.add(GitTagParamEnum.MESSAGE.key(), message);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String action = args.str(GitTagParamEnum.ACTION.key());
        String name = args.str(GitTagParamEnum.NAME.key());
        if (("create".equals(action) || "delete".equals(action)) && (name == null || name.isBlank())) {
            throw new McpArgumentException(-32602, "name is required for action=" + action);
        }
        return GitProvider.gitTag(action, name, args.str(GitTagParamEnum.REVISION.key()), args.str(GitTagParamEnum.MESSAGE.key()));
    }
}
