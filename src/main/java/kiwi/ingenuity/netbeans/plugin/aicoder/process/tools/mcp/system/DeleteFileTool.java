package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class DeleteFileTool extends AbstractFileTool {

    private final McpHookServer server;

    public DeleteFileTool(McpHookServer server) {
        super(McpSectionEnum.SYSTEM,
                McpToolEnum.DELETE_FILE.toolName(),
                "Permanently delete a file. Closes any open editor tab, removes the file from disk, "
                + "and refreshes the project tree and VCS status.",
                "DeleteFile -> permanently removes a file; closes open tab and refreshes VCS automatically");
        this.server = server;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(DeleteFileParamEnum.FILE_PATH.key());
        String effectivePath = fp != null ? fp : EditorContextProvider.getCurrentFilePath();
        if (effectivePath != null) {
            String sessionId = session.getId();
            if (sessionId == null || !server.isFileAllowed(sessionId, effectivePath)) {
                return "Access denied: " + effectivePath + " is outside the allowed project scope for this session.";
            }
        }
        return RefactoringProvider.deleteFile(effectivePath);
    }
}
