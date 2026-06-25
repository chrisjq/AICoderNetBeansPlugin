package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class SaveFileTool extends AbstractFileTool {

    private final McpHookServer server;

    public SaveFileTool(McpHookServer server) {
        super(McpSectionEnum.SYSTEM,
                McpToolEnum.SAVE_FILE.toolName(),
                "Save a file. When 'content' is provided, replaces the entire file content and saves "
                + "(works for open or closed files; creates new files). Without 'content', saves "
                + "existing unsaved editor changes to disk.",
                "SaveFile -> with 'content': write+save in one step INSTEAD OF Read+Edit (no built-in "
                + "tools needed); refresh is automatic for both new and existing files; "
                + "without 'content': flush unsaved NetBeans changes before Read+Edit");
        this.server = server;
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.SAVE_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Save a file. When 'content' is provided, replaces the entire file content and saves "
                + "(works for open or closed files; creates new files). Without 'content', saves "
                + "existing unsaved editor changes to disk.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file. Omit to use the current editor.");
        props.add(SaveFileParamEnum.FILE_PATH.key(), fp);
        JsonObject ct = new JsonObject();
        ct.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        ct.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "New file content. When provided, replaces the entire file and saves in one operation.");
        props.add(SaveFileParamEnum.CONTENT.key(), ct);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(SaveFileParamEnum.FILE_PATH.key());
        String effectivePath = fp != null ? fp : EditorContextProvider.getCurrentFilePath();
        if (effectivePath != null) {
            String sessionId = session.getId();
            if (sessionId == null || !server.isFileAllowed(sessionId, effectivePath)) {
                return "Access denied: " + effectivePath + " is outside the allowed project scope for this session.";
            }
        }
        String content = args.str(SaveFileParamEnum.CONTENT.key());
        if (content != null) {
            if (fp == null) {
                return "file_path is required when content is provided";
            }
            return RefactoringProvider.writeFileContent(fp, content);
        }
        return RefactoringProvider.saveFile(effectivePath);
    }
}
