package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
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
public class GitResetTool implements McpToolInterface {

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
            return McpToolEnum.GIT_RESET.toolName() + " - unstage files or reset HEAD to a revision (SOFT/MIXED/HARD). "
                    + "Requires " + GitCommonParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root.";
        }
        return McpToolEnum.GIT_RESET.toolName() + " -> INSTEAD OF Bash git reset - unstage files or reset HEAD to a revision (SOFT/MIXED/HARD). "
                + "Requires " + GitCommonParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
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
        type.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Reset type: SOFT, MIXED (default), or HARD.");
        props.add(GitResetParamEnum.TYPE.key(), type);
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Target git repository or project root; relative paths resolve against the default project.");
        props.add(GitCommonParamEnum.PROJECT_PATH.key(), projectPath);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
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
        JsonArray arr = args.array(GitResetParamEnum.FILES.key());
        List<String> files = null;
        if (arr != null) {
            files = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                files.add(arr.get(i).getAsString());
            }
        }
        return GitProvider.gitReset(args.require(GitCommonParamEnum.PROJECT_PATH.key()), files,
                args.str(GitResetParamEnum.REVISION.key()), args.str(GitResetParamEnum.TYPE.key()));
    }
}
