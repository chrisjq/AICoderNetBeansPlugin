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
 * Replaces an exact string in a file, routed through the NetBeans Accept/Reject
 * diff panel (PermissionEvent) before applying. Used so GitHub Copilot edits go
 * through the same review UX.
 *
 * <p>
 * Locks the target file (not a global lock — see usesOwnFileLocking()) from
 * before the diff is shown through the user's decision and the write, so the
 * file can't change underneath a pending decision. A different file being
 * edited concurrently is unaffected.
 */
public class ApplyEditTool extends AbstractActionTool {

    public ApplyEditTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.APPLY_EDIT.toolName(),
                "Replace an exact string in a file. old_string must match the source byte-for-byte including indentation; strip the line-number gutter if text was copied from GetFileContent. The user approves the change in the NetBeans Accept/Reject diff panel.",
                "ApplyEdit -> replace old_string with new_string in a file; old_string must be byte-for-byte exact (strip GetFileContent gutter if copying from there); user approves via the NetBeans diff panel");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.APPLY_EDIT.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Replace an exact string in a file. old_string must match the source byte-for-byte including indentation; strip the line-number gutter if text was copied from GetFileContent. The user approves the change in the NetBeans Accept/Reject diff panel.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path of the file to edit.");
        props.add("file_path", fp);
        JsonObject os = new JsonObject();
        os.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        os.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The exact text to replace, matched byte-for-byte. Strip the line-number gutter if copied from GetFileContent.");
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
        String filePath = args.str("file_path");
        String oldString = args.str("old_string");
        String newString = args.str("new_string");
        if (filePath == null || filePath.isBlank()) {
            return "Error: file_path is required";
        }
        var server = McpServerRegistry.getServer();
        if (server == null) {
            return "Error: file path is not within the allowed project directories";
        }
        // The session's own config dir (memory, logs) is written directly, never shown in
        // a review panel — consistent with the built-in Edit hook. Checked before the
        // project-scope gate so it works even for a restrict-to-project session.
        if (server.isOwnSessionConfigFile(session.getId(), filePath)) {
            return RefactoringProvider.applyEdit(filePath, oldString, newString);
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
                return RefactoringProvider.applyEdit(filePath, oldString, newString);
            }
            CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
            listener.onAiProcessEvent(new PermissionEvent("Edit", filePath, oldString, newString, null, future));
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
                        ? "User rejected the edit: " + decision.message().trim() + " — do not retry this change"
                        : "User rejected the edit — do not retry this change";
            }
            return RefactoringProvider.applyEdit(filePath, oldString, newString);
        }
        finally {
            lockManager.releaseFileLock(session.getId(), filePath);
        }
    }
}
