package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

/**
 * Writes full file content (creating or overwriting), routed through the
 * NetBeans Accept/Reject diff panel (PermissionEvent) before applying. Used so
 * GitHub Copilot file creation goes through the review UX (Copilot's native
 * `create` tool is denied).
 */
@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class WriteFileTool extends AbstractActionTool {

    public WriteFileTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.WRITE_FILE.toolName(),
                "Create or overwrite a file with the given content. The user approves the change in the NetBeans Accept/Reject diff panel before it is applied.",
                "WriteFile -> create/overwrite a file with content; user approves via the NetBeans diff panel");
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.WRITE_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Create or overwrite a file with the given content. The user approves the change in the NetBeans Accept/Reject diff panel before it is applied.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path of the file to write.");
        props.add("file_path", fp);
        JsonObject content = new JsonObject();
        content.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        content.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The full content to write to the file.");
        props.add("content", content);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray req = new JsonArray();
        req.add("file_path");
        req.add("content");
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), req);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String filePath = args.str("file_path");
        String content = args.str("content");
        if (filePath == null || filePath.isBlank()) {
            return "Error: file_path is required";
        }
        var server = McpServerRegistry.getServer();
        if (server == null || !server.isFileAllowed(session.getId(), filePath)) {
            return "Error: file path is not within the allowed project directories";
        }
        AiProcessEventListener listener = session.getAiProcessEventListener();
        if (listener == null) {
            return RefactoringProvider.writeFileContent(filePath, content);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        listener.onAiProcessEvent(new PermissionEvent("Write", filePath, null, null, content, future));
        boolean allowed;
        try {
            allowed = future.get(120, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            allowed = false;
        }
        if (!allowed) {
            return "User rejected the write — do not retry this change";
        }
        return RefactoringProvider.writeFileContent(filePath, content);
    }
}
