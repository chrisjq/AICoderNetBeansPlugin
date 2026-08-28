package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.ProjectPathUtil;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class DeleteFileTool extends AbstractFileTool {

    private final McpHookServer server;
    long confirmTimeoutMillis = TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis();

    public DeleteFileTool(McpHookServer server) {
        super(McpSectionEnum.SYSTEM,
                McpToolEnum.DELETE_FILE.toolName(),
                "Permanently delete a file. Closes any open editor tab, removes the file from disk, "
                + "and refreshes the project tree and VCS status.",
                McpToolEnum.DELETE_FILE.toolName() + " -> permanently removes a file; closes open tab and refreshes VCS automatically");
        this.server = server;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(DeleteFileParamEnum.FILE_PATH.key());
        if (fp == null || fp.isBlank()) {
            // No fallback to the focused editor. This tool deletes: omitting the
            // path used to destroy whatever file the user happened to have open,
            // chosen by where they last clicked rather than by anything the
            // caller decided. Nothing about that is recoverable from the caller's
            // side, so it must name its target.
            return DeleteFileParamEnum.FILE_PATH.key() + " is required — this tool does not fall back to the focused editor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " if you want the file the user is looking at.";
        }
        String effectivePath = fp;
        if (effectivePath != null) {
            String sessionId = session.getId();
            // isFileWritable, not isFileAccessible: a delete is a write, so it must not
            // inherit the read exemption the persistence base's index/template files carry.
            if (!McpHookServer.isFileWritable(server, sessionId, effectivePath)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, effectivePath);
            }
        }
        if (effectivePath == null || !new java.io.File(effectivePath).exists()) {
            return RefactoringProvider.deleteFile(effectivePath);
        }
        AiProcessEventListener listener = session.getAiProcessEventListener();
        if (listener == null) {
            return RefactoringProvider.deleteFile(effectivePath);
        }
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        listener.onAiProcessEvent(new ConfirmEvent("Delete",
                "Permanently delete " + ProjectPathUtil.shortPath(effectivePath) + "?",
                effectivePath, null, future));
        PermissionDecision decision;
        try {
            decision = future.get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            future.complete(PermissionDecision.denied("timed out"));
            return "Timed out waiting for the user to confirm this operation — "
                    + "the user did not respond in time. You may retry.";
        }
        catch (Exception e) {
            future.complete(PermissionDecision.denied(null));
            decision = PermissionDecision.denied(null);
        }
        if (decision == null || !decision.allow()) {
            return "User declined the delete — do not retry without asking.";
        }
        return RefactoringProvider.deleteFile(effectivePath);
    }
}
