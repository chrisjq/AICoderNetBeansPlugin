package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

@RequiresLock(LockTypeEnum.REFACTOR_LOCK)
public class RenameSymbolTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.REFACTORING;
    }

    @Override
    public String instruction() {
        return "RenameSymbol -> INSTEAD OF find+replace - renames any identifier and updates all references";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.RENAME_SYMBOL.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Renames the Java symbol at the specified position across all files in the project. "
                + "Provide filePath + line to target a specific symbol; omit both to use cursor position.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject newName = new JsonObject();
        newName.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        newName.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The new identifier name.");
        props.add(RenameSymbolParamEnum.NEW_NAME.key(), newName);
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file. Omit to use current editor.");
        props.add(RenameSymbolParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based line number of the symbol. Omit to use cursor.");
        props.add(RenameSymbolParamEnum.LINE.key(), ln);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(RenameSymbolParamEnum.NEW_NAME.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String fp = args.str(RenameSymbolParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (server == null || sessionId == null || !server.isFileAllowed(sessionId, fp)) {
                return "Access denied: " + fp + " is outside the allowed project scope for this session.";
            }
        }
        return RefactoringProvider.renameSymbol(fp, args.intOr(RenameSymbolParamEnum.LINE.key(), 0), args.require(RenameSymbolParamEnum.NEW_NAME.key()));
    }
}
