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
public class DeleteDirectoryTool extends AbstractFileTool {

    private final McpHookServer server;
    long confirmTimeoutMillis = TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis();

    public DeleteDirectoryTool(McpHookServer server) {
        super(McpSectionEnum.SYSTEM,
                McpToolEnum.DELETE_DIRECTORY.toolName(),
                "Permanently delete a directory whose whole tree holds no files — only empty subdirectories, at "
                + "any depth. Refuses otherwise, naming up to 5 of the files it found and their total count. "
                + "Refuses an open project's root directory.",
                McpToolEnum.DELETE_DIRECTORY.toolName() + " -> permanently removes an empty directory tree; "
                + "refuses if it contains any file or is an open project's root");
        this.server = server;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(DeleteDirectoryParamEnum.FILE_PATH.key());
        if (fp == null || fp.isBlank()) {
            return DeleteDirectoryParamEnum.FILE_PATH.key() + " is required — this tool does not fall back to the "
                   + "focused editor. Call " + McpToolEnum.GET_CURRENT_FILE.toolName()
                   + " if you want the file the user is looking at.";
        }
        String sessionId = session.getId();
        if (!McpHookServer.isFileWritable(server, sessionId, fp)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
        }
        if (!new java.io.File(fp).exists()) {
            return RefactoringProvider.deleteDirectory(fp);
        }
        AiProcessEventListener listener = session.getAiProcessEventListener();
        if (listener == null) {
            return RefactoringProvider.deleteDirectory(fp);
        }
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        listener.onAiProcessEvent(new ConfirmEvent("Delete",
                "Permanently delete the directory " + ProjectPathUtil.shortPath(fp) + "?",
                fp, null, future));
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
        return RefactoringProvider.deleteDirectory(fp);
    }
}
