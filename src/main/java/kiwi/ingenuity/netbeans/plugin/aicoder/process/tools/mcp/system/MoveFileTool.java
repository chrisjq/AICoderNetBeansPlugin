package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class MoveFileTool implements McpToolInterface {

    private final McpHookServer server;

    public MoveFileTool(McpHookServer server) {
        this.server = server;
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.REFACTORING;
    }

    @Override
    public String instruction() {
        return "MoveFile -> moves a file; Java files use MoveRefactoring (updates package declaration "
                + "and all import references); other files use FileUtil.moveFile()";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.MOVE_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Move a file to a target directory. Java files are moved via MoveRefactoring so the "
                + "package declaration and all import references are updated automatically. "
                + "Other file types are moved with FileUtil.moveFile(). "
                + "Refreshes VCS status in both source and target directories after the operation.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject src = new JsonObject();
        src.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        src.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the source file.");
        props.add(MoveFileParamEnum.SOURCE_PATH.key(), src);
        JsonObject dir = new JsonObject();
        dir.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        dir.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the destination directory (must exist).");
        props.add(MoveFileParamEnum.TARGET_DIRECTORY.key(), dir);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(MoveFileParamEnum.SOURCE_PATH.key());
        required.add(MoveFileParamEnum.TARGET_DIRECTORY.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String sourcePath = args.require(MoveFileParamEnum.SOURCE_PATH.key());
        String targetDir = args.require(MoveFileParamEnum.TARGET_DIRECTORY.key());
        String sessionId = session.getId();
        if (sessionId == null || !server.isFileAllowed(sessionId, sourcePath)) {
            return "Access denied: " + sourcePath + " is outside the allowed project scope for this session.";
        }
        if (sessionId == null || !server.isFileAllowed(sessionId, targetDir)) {
            return "Access denied: " + targetDir + " is outside the allowed project scope for this session.";
        }
        return RefactoringProvider.moveFile(sourcePath, targetDir);
    }
}
