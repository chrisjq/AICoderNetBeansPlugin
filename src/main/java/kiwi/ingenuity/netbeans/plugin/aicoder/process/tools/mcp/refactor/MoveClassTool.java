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
public class MoveClassTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.REFACTORING;
    }

    @Override
    public String instruction() {
        return "MoveClass -> INSTEAD OF WriteFile+DeleteFile for Java classes — use this first to move the class and update ALL import references project-wide, then use ApplyEdit for any content changes to the moved file";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.MOVE_CLASS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Moves a Java class to a different package, updating all import references. "
                + "Provide filePath to target a specific file; omit to use current editor.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject tp = new JsonObject();
        tp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Target package (e.g. com.example.ui).");
        props.add(MoveClassParamEnum.TARGET_PACKAGE.key(), tp);
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the source file. Omit to use current editor.");
        props.add(MoveClassParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based line of the class declaration. Omit to use line 1.");
        props.add(MoveClassParamEnum.LINE.key(), ln);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(MoveClassParamEnum.TARGET_PACKAGE.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String fp = args.str(MoveClassParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (server == null || sessionId == null || !server.isFileAllowed(sessionId, fp)) {
                return "Access denied: " + fp + " is outside the allowed project scope for this session.";
            }
        }
        return RefactoringProvider.moveClass(fp, args.intOr(MoveClassParamEnum.LINE.key(), 0), args.require(MoveClassParamEnum.TARGET_PACKAGE.key()));
    }
}
