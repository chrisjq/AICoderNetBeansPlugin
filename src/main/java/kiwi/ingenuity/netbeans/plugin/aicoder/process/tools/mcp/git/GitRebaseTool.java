package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
public class GitRebaseTool implements McpToolInterface {

    private static final Set<String> VALID_OPERATIONS = Set.of("BEGIN", "CONTINUE", "SKIP", "ABORT");

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
            return McpToolEnum.GIT_REBASE.toolName() + " - rebases current branch onto upstream; supports BEGIN/CONTINUE/SKIP/ABORT. "
                    + "Requires " + GitCommonParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root.";
        }
        return McpToolEnum.GIT_REBASE.toolName() + " -> INSTEAD OF Bash git rebase - rebases current branch onto upstream; supports BEGIN/CONTINUE/SKIP/ABORT. "
                + "Requires " + GitCommonParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_REBASE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Rebases the current branch. operation=BEGIN starts onto upstream; CONTINUE/SKIP/ABORT manage in-progress rebase. "
                + "Equivalent to: git rebase [upstream] / git rebase --continue|--skip|--abort");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject upstream = new JsonObject();
        upstream.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        upstream.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Branch or revision to rebase onto. Required for BEGIN.");
        props.add(GitRebaseParamEnum.UPSTREAM.key(), upstream);
        JsonObject operation = new JsonObject();
        operation.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        operation.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Operation: BEGIN, CONTINUE, SKIP, ABORT. Default: BEGIN.");
        props.add(GitRebaseParamEnum.OPERATION.key(), operation);
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
        String upstream = args.str(GitRebaseParamEnum.UPSTREAM.key());
        String operation = args.str(GitRebaseParamEnum.OPERATION.key());

        if (operation != null && !VALID_OPERATIONS.contains(operation)) {
            return "Error: unsupported operation '" + operation + "'. Valid values: BEGIN, CONTINUE, SKIP, ABORT";
        }
        if ("BEGIN".equals(operation) && (upstream == null || upstream.isBlank())) {
            return "Error: upstream is required for operation=BEGIN";
        }

        return GitProvider.gitRebase(args.require(GitCommonParamEnum.PROJECT_PATH.key()), upstream, operation);
    }
}
