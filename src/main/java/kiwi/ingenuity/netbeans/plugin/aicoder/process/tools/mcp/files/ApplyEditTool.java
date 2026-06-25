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
 * Replaces an exact string in a file, routed through the NetBeans Accept/Reject
 * diff panel (PermissionEvent) before applying. Used so GitHub Copilot edits go
 * through the same review UX.
 */
@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class ApplyEditTool extends AbstractActionTool {

    public ApplyEditTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.APPLY_EDIT.toolName(),
                "Replace an exact string in a file. The user approves the change in the NetBeans Accept/Reject diff panel before it is applied.",
                "ApplyEdit -> replace old_string with new_string in a file; user approves via the NetBeans diff panel");
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.APPLY_EDIT.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Replace an exact string in a file. The user approves the change in the NetBeans Accept/Reject diff panel before it is applied.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path of the file to edit.");
        props.add("file_path", fp);
        JsonObject os = new JsonObject();
        os.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        os.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The exact text to replace.");
        props.add("old_string", os);
        JsonObject ns = new JsonObject();
        ns.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        ns.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The replacement text.");
        props.add("new_string", ns);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray req = new JsonArray();
        req.add("file_path");
        req.add("old_string");
        req.add("new_string");
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
        String oldString = args.str("old_string");
        String newString = args.str("new_string");
        if (filePath == null || filePath.isBlank()) {
            return "Error: file_path is required";
        }
        var server = McpServerRegistry.getServer();
        if (server == null || !server.isFileAllowed(session.getId(), filePath)) {
            return "Error: file path is not within the allowed project directories";
        }
        AiProcessEventListener listener = session.getAiProcessEventListener();
        if (listener == null) {
            return RefactoringProvider.applyEdit(filePath, oldString, newString);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        listener.onAiProcessEvent(new PermissionEvent("Edit", filePath, oldString, newString, null, future));
        boolean allowed;
        try {
            allowed = future.get(120, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            allowed = false;
        }
        if (!allowed) {
            return "User rejected the edit — do not retry this change";
        }
        return RefactoringProvider.applyEdit(filePath, oldString, newString);
    }
}
