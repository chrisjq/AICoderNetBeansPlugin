package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

/**
 * Writes full file content (creating or overwriting), routed through the NetBeans Accept/Reject diff panel
 * (PermissionEvent) before applying. Used so GitHub Copilot file creation goes through the review UX (Copilot's native
 * `create` tool is denied).
 *
 * <p>
 * Locks the target file (not a global lock — see usesOwnFileLocking()) from before the diff is shown through the user's
 * decision and the write, so the file can't change underneath a pending decision. A different file being edited
 * concurrently is unaffected.
 */
public class WriteFileTool extends AbstractActionTool {

    public WriteFileTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.WRITE_FILE.toolName(),
                "Create or overwrite a file with the given content. The user approves the change in the NetBeans Accept/Reject diff panel before it is applied.",
                McpToolEnum.WRITE_FILE.toolName() + " -> create/overwrite a file with content; user approves via the NetBeans diff panel");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.WRITE_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Create or overwrite a file with the given content. Any unsaved editor changes are saved first so the diff reflects what the user actually has. The user approves the change in the NetBeans Accept/Reject diff panel before it is applied.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path of the file to write.");
        props.add(McpToolPropertyEnum.FILE_PATH.key(), fp);
        JsonObject content = new JsonObject();
        content.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        content.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The full content to write to the file.");
        props.add(McpToolPropertyEnum.CONTENT.key(), content);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray req = new JsonArray();
        req.add(McpToolPropertyEnum.FILE_PATH.key());
        req.add(McpToolPropertyEnum.CONTENT.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), req);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public boolean usesOwnFileLocking() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String filePath = args.str(McpToolPropertyEnum.FILE_PATH.key());
        String content = args.str(McpToolPropertyEnum.CONTENT.key());
        if (filePath == null || filePath.isBlank()) {
            return "Error: " + McpToolPropertyEnum.FILE_PATH.key() + " is required";
        }
        var server = McpServerRegistry.getServer();
        if (server == null) {
            return "Error: file path is not within the allowed project directories";
        }
        // The session's own config dir (memory, logs) is written directly, never shown in
        // a review panel — consistent with the built-in Write hook. Checked before the
        // project-scope gate so it works even for a restrict-to-project session.
        if (server.isOwnSessionConfigFile(session.getId(), filePath)) {
            return RefactoringProvider.writeFileContent(filePath, content);
        }
        if (!server.isFileAllowed(session.getId(), filePath)) {
            return "Error: file path is not within the allowed project directories";
        }
        LockManager lockManager = LockManager.getInstance();
        if (!lockManager.acquireFileLock(session.getId(), filePath)) {
            String holder = lockManager.getFileLockHolder(filePath);
            return "File is locked by " + (holder != null ? "session " + holder : "another in-progress edit")
                    + " — try again shortly";
        }
        try {
            AiProcessEventListener listener = session.getAiProcessEventListener();
            if (listener == null) {
                return RefactoringProvider.writeFileContent(filePath, content);
            }
            CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
            listener.onAiProcessEvent(new PermissionEvent("Write", filePath, null, null, content, future));
            PermissionDecision decision;
            try {
                decision = future.get(120, TimeUnit.SECONDS);
            }
            catch (TimeoutException e) {
                // A timeout is not a rejection — the user simply never acted on the diff
                // panel. Return a distinct, retryable message (a real rejection below ends
                // with "do not retry this change").
                return "Timed out waiting for the user to review this change in the diff panel — "
                        + "the user did not respond in time. You may retry.";
            }
            catch (Exception e) {
                decision = PermissionDecision.denied(null);
            }
            if (decision == null || !decision.allow()) {
                return decision != null && decision.message() != null && !decision.message().isBlank()
                        ? "User rejected the write: " + decision.message().trim() + " — do not retry this change"
                        : "User rejected the write — do not retry this change";
            }
            return RefactoringProvider.writeFileContent(filePath, content);
        }
        finally {
            lockManager.releaseFileLock(session.getId(), filePath);
        }
    }
}
