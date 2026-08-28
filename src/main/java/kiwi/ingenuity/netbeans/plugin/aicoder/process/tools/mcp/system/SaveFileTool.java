package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.ProjectPathUtil;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class SaveFileTool extends AbstractFileTool {

    private final McpHookServer server;

    public SaveFileTool(McpHookServer server) {
        super(McpSectionEnum.SYSTEM,
                McpToolEnum.SAVE_FILE.toolName(),
                "Save a file. When " + SaveFileParamEnum.CONTENT.key() + " is provided, replaces the entire file content and saves "
                + "(works for open or closed files; creates new files). Without " + SaveFileParamEnum.CONTENT.key() + ", saves "
                + "existing unsaved editor changes to disk.",
                McpToolEnum.SAVE_FILE.toolName() + " -> with " + SaveFileParamEnum.CONTENT.key() + ": write+save in one step INSTEAD OF Read+Edit (no built-in "
                + "tools needed); refresh is automatic for both new and existing files; "
                + "without " + SaveFileParamEnum.CONTENT.key() + ": flush unsaved NetBeans changes before Read+Edit",
                McpToolEnum.SAVE_FILE.toolName() + " - with " + SaveFileParamEnum.CONTENT.key() + ": write+save in one step; refresh is automatic for both new and existing files; without " + SaveFileParamEnum.CONTENT.key() + ": flush unsaved NetBeans changes");
        this.server = server;
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.SAVE_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Save a file. When " + SaveFileParamEnum.CONTENT.key() + " is provided, replaces the entire file content and saves "
                + "(works for open or closed files; creates new files). Without " + SaveFileParamEnum.CONTENT.key() + ", saves "
                + "existing unsaved editor changes to disk.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Absolute path to the file. Required — no fallback to the "
                + "focused editor. Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " for the file the user is looking at.");
        props.add(SaveFileParamEnum.FILE_PATH.key(), fp);
        JsonObject ct = new JsonObject();
        ct.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        ct.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "New file content. When provided, replaces the entire file and saves in one operation.");
        props.add(SaveFileParamEnum.CONTENT.key(), ct);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(SaveFileParamEnum.FILE_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(SaveFileParamEnum.FILE_PATH.key());
        if (fp == null || fp.isBlank()) {
            // No fallback to the focused editor. Without content this flushes
            // unsaved editor changes to disk, so omitting the path committed
            // whatever the user happened to be part-way through editing —
            // chosen by where they last clicked, not by anything the caller
            // decided. The content path already required it; this makes the
            // no-content path agree.
            return SaveFileParamEnum.FILE_PATH.key() + " is required — this tool does not fall back to the focused editor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " if you want the file the user is looking at.";
        }
        String sessionId = session.getId();
        boolean ownConfigFile = server.isOwnSessionConfigFile(sessionId, fp);
        if (!ownConfigFile && !McpHookServer.isProjectFileAllowed(server, sessionId, fp)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
        }
        String content = args.str(SaveFileParamEnum.CONTENT.key());
        if (content != null) {
            if (ownConfigFile) {
                // The session's own config dir (memory, logs) is its own working data, so
                // it is auto-accepted: written directly with no diff panel and no
                // PermissionEvent — not because a panel could not be rendered for it (the
                // panel builds from content strings, not a project-anchored FileObject),
                // but because this is a deliberate policy choice, consistent with the
                // built-in Write hook and ApplyEdit/WriteFile's own-config branch.
                return RefactoringProvider.writeFileContent(fp, content);
            }
            AiProcessEventListener listener = session.getAiProcessEventListener();
            if (listener == null) {
                return RefactoringProvider.writeFileContent(fp, content);
            }
            CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
            listener.onAiProcessEvent(new PermissionEvent("Write", fp, null, null, content, future));
            PermissionDecision decision;
            try {
                decision = future.get(TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e) {
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
            return RefactoringProvider.writeFileContent(fp, content);
        }
        String result = RefactoringProvider.saveFile(fp);
        // No notification for the session's own config dir either — flushing its own
        // working data to disk is not something the user needs to be told about, same
        // as the write branch above never surfaces a PermissionEvent for it.
        if ("File saved".equals(result) && !ownConfigFile) {
            AiProcessEventListener listener = session.getAiProcessEventListener();
            if (listener != null) {
                listener.onAiProcessEvent(new SystemNotificationEvent(
                        McpToolEnum.SAVE_FILE.toolName() + ": " + ProjectPathUtil.shortPath(fp)));
            }
        }
        return result;
    }
}
