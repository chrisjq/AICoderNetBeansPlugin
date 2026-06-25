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
public class CopyFileTool implements McpToolInterface {

    private final McpHookServer server;

    public CopyFileTool(McpHookServer server) {
        this.server = server;
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SYSTEM;
    }

    @Override
    public String instruction() {
        return "CopyFile -> copies a file to a target directory using FileUtil.copyFile(); "
                + "optionally rename via newName (base name, no extension)";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.COPY_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Copy a file to a target directory. Optionally supply newName (base name without extension) "
                + "to rename the copy. Refreshes VCS status after the operation.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject src = new JsonObject();
        src.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        src.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the source file.");
        props.add(CopyFileParamEnum.SOURCE_PATH.key(), src);
        JsonObject dir = new JsonObject();
        dir.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        dir.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the destination directory (must exist).");
        props.add(CopyFileParamEnum.TARGET_DIRECTORY.key(), dir);
        JsonObject name = new JsonObject();
        name.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        name.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Base name for the copy without extension. Omit to keep the original name.");
        props.add(CopyFileParamEnum.NEW_NAME.key(), name);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(CopyFileParamEnum.SOURCE_PATH.key());
        required.add(CopyFileParamEnum.TARGET_DIRECTORY.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String sourcePath = args.require(CopyFileParamEnum.SOURCE_PATH.key());
        String targetDir = args.require(CopyFileParamEnum.TARGET_DIRECTORY.key());
        String sessionId = session.getId();
        if (sessionId == null || !server.isFileAllowed(sessionId, sourcePath)) {
            return "Access denied: " + sourcePath + " is outside the allowed project scope for this session.";
        }
        if (sessionId == null || !server.isFileAllowed(sessionId, targetDir)) {
            return "Access denied: " + targetDir + " is outside the allowed project scope for this session.";
        }
        return RefactoringProvider.copyFile(
                sourcePath,
                targetDir,
                args.str(CopyFileParamEnum.NEW_NAME.key()));
    }
}
