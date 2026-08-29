package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

@RequiresLock(LockTypeEnum.REFACTOR_LOCK)
public class InlineVariableTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.REFACTORING;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return McpToolEnum.INLINE_VARIABLE.toolName() + " - inlines a variable at all use sites";
        }
        return McpToolEnum.INLINE_VARIABLE.toolName() + " -> INSTEAD OF manual editing - inlines a variable at all use sites";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.INLINE_VARIABLE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Inlines a variable - replaces all usages with the variable's initializer expression "
                + "and removes the declaration. " + InlineVariableParamEnum.FILE_PATH.key() + " and " + InlineVariableParamEnum.LINE.key() + " are both required — this tool does not "
                + "act on the user's cursor position; call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " first if that is what you want.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file. Required — this tool does not fall back to the focused editor. Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " if you want the file the user is looking at.");
        props.add(InlineVariableParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "1-based line of the variable declaration or usage. Required — this tool does not follow the user's cursor.");
        props.add(InlineVariableParamEnum.LINE.key(), ln);
        JsonObject cw = new JsonObject();
        cw.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        cw.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "When a refactoring reports only non-fatal warnings, apply it anyway and report the warnings alongside the result instead of refusing. Fatal problems always refuse regardless of this flag — these tools apply changes immediately with no diff panel to review them in, so a fatal problem (the engine's own signal that the result would be broken) is never applied unreviewed. Default: false.");
        props.add(InlineVariableParamEnum.COMMIT_WITH_WARNING.key(), cw);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(InlineVariableParamEnum.FILE_PATH.key());
        required.add(InlineVariableParamEnum.LINE.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String fp = args.str(InlineVariableParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
        }
        return RefactoringProvider.inlineVariable(fp, args.intOr(InlineVariableParamEnum.LINE.key(), 0),
                args.bool(InlineVariableParamEnum.COMMIT_WITH_WARNING.key()));
    }
}
