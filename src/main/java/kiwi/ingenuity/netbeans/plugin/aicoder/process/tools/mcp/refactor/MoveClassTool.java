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
public class MoveClassTool implements McpToolInterface {

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
            return McpToolEnum.MOVE_CLASS.toolName() + " - moves a Java class and updates ALL import references project-wide; use " + McpToolEnum.APPLY_EDIT.toolName() + " for any content changes to the moved file";
        }
        return McpToolEnum.MOVE_CLASS.toolName() + " -> INSTEAD OF " + McpToolEnum.WRITE_FILE.toolName() + "+" + McpToolEnum.DELETE_FILE.toolName() + " for Java classes — use this first to move the class and update ALL import references project-wide, then use " + McpToolEnum.APPLY_EDIT.toolName() + " for any content changes to the moved file";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.MOVE_CLASS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Moves a Java class to a different package, updating the package declaration and all import references; edits route through the Accept/Reject diff panel. "
                + "With " + MoveClassParamEnum.LINE.key() + " it moves just the class declared at that line; without it the whole file moves (refused for multi-type files). "
                + MoveClassParamEnum.FILE_PATH.key() + " is required — this tool does not fall back to the focused editor. "
                + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " if you want the file the user is looking at.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject tp = new JsonObject();
        tp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Target package (e.g. com.example.ui).");
        props.add(MoveClassParamEnum.TARGET_PACKAGE.key(), tp);
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the source file. Required — this tool does not fall back to the focused editor. Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " if you want the file the user is looking at.");
        props.add(MoveClassParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based line of the class declaration to move. Omit to move the whole file (single top-level type only). Default: move the whole file.");
        props.add(MoveClassParamEnum.LINE.key(), ln);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(MoveClassParamEnum.TARGET_PACKAGE.key());
        required.add(MoveClassParamEnum.FILE_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String fp = args.str(MoveClassParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
        }
        return RefactoringProvider.moveClass(fp, args.intOr(MoveClassParamEnum.LINE.key(), 0), args.require(MoveClassParamEnum.TARGET_PACKAGE.key()));
    }
}
