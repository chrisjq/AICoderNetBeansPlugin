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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

/**
 * Replaces an exact string in a file, routed through the NetBeans Accept/Reject diff panel (PermissionEvent) before
 * applying. Used so GitHub Copilot edits go through the same review UX.
 *
 * <p>
 * Locks the target file (not a global lock — see usesOwnFileLocking()) from before the diff is shown through the user's
 * decision and the write, so the file can't change underneath a pending decision. A different file being edited
 * concurrently is unaffected.
 */
public class ApplyEditTool extends AbstractActionTool {

    public ApplyEditTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.APPLY_EDIT.toolName(),
                "Replace an exact string in a file. " + McpToolPropertyEnum.OLD_STRING.key() + " must match the source byte-for-byte including indentation; strip the line-number gutter if text was copied from " + McpToolEnum.GET_FILE_CONTENT.toolName() + ". The user approves the change in the NetBeans Accept/Reject diff panel.",
                McpToolEnum.APPLY_EDIT.toolName() + " -> replace " + McpToolPropertyEnum.OLD_STRING.key() + " with " + McpToolPropertyEnum.NEW_STRING.key() + " in a file; " + McpToolPropertyEnum.OLD_STRING.key() + " must be byte-for-byte exact (strip " + McpToolEnum.GET_FILE_CONTENT.toolName() + " gutter if copying from there); user approves via the NetBeans diff panel");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.APPLY_EDIT.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Replace an exact string in a file, routing edits through the user's Accept/Reject diff panel. " + McpToolPropertyEnum.OLD_STRING.key() + " must match the source byte-for-byte including indentation; strip the line-number gutter if text was copied from " + McpToolEnum.GET_FILE_CONTENT.toolName() + ". Any unsaved editor changes are saved first, so " + McpToolPropertyEnum.OLD_STRING.key() + " is matched against what the user has on screen. Replaces only the first occurrence — make " + McpToolPropertyEnum.OLD_STRING.key() + " unique enough to identify the intended site.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path of the file to edit.");
        props.add(McpToolPropertyEnum.FILE_PATH.key(), fp);
        JsonObject os = new JsonObject();
        os.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        os.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The exact text to replace, matched byte-for-byte. Strip the line-number gutter if copied from " + McpToolEnum.GET_FILE_CONTENT.toolName() + ".");
        props.add(McpToolPropertyEnum.OLD_STRING.key(), os);
        JsonObject ns = new JsonObject();
        ns.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        ns.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The replacement text.");
        props.add(McpToolPropertyEnum.NEW_STRING.key(), ns);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray req = new JsonArray();
        req.add(McpToolPropertyEnum.FILE_PATH.key());
        req.add(McpToolPropertyEnum.OLD_STRING.key());
        req.add(McpToolPropertyEnum.NEW_STRING.key());
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
        String oldString = args.str(McpToolPropertyEnum.OLD_STRING.key());
        String newString = args.str(McpToolPropertyEnum.NEW_STRING.key());
        if (filePath == null || filePath.isBlank()) {
            return "Error: " + McpToolPropertyEnum.FILE_PATH.key() + " is required";
        }
        var server = McpServerRegistry.getServer();
        if (server == null) {
            return "Error: file path is not within the allowed project directories";
        }
        // The session's own config dir (memory, logs) is its own working data, so it is
        // auto-accepted: written directly with no diff panel and no PermissionEvent — not
        // because a panel could not be rendered for it (the panel builds from content
        // strings, not a project-anchored FileObject), but because this is a deliberate
        // policy choice. Consistent with the built-in Edit hook. Checked before the
        // project-scope gate so it works even for a restrict-to-project session.
        if (server.isOwnSessionConfigFile(session.getId(), filePath)) {
            return RefactoringProvider.applyEdit(filePath, oldString, newString);
        }
        if (!McpHookServer.isProjectFileAllowed(server, session.getId(), filePath)) {
            return McpHookServer.fileAccessDeniedMessage(server, session.getId(), filePath);
        }
        LockManager lockManager = LockManager.getInstance();
        if (!lockManager.acquireFileLock(session.getId(), filePath)) {
            return LockManager.fileLockedMessage(lockManager.getFileLockHolder(filePath));
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
                decision = future.get(TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
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
