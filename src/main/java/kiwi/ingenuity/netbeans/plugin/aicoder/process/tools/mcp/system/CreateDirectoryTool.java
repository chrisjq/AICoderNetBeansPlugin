package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class CreateDirectoryTool extends AbstractFileTool {

    private final McpHookServer server;

    public CreateDirectoryTool(McpHookServer server) {
        super(McpSectionEnum.SYSTEM,
                McpToolEnum.CREATE_DIRECTORY.toolName(),
                "Create a directory and any missing parent directories. " + McpToolEnum.WRITE_FILE.toolName()
                + " already creates parent folders for a new file, so use this only to create an empty folder. "
                + "Returns success if the directory already existed.",
                McpToolEnum.CREATE_DIRECTORY.toolName() + " -> creates an empty directory (and missing parents); "
                + "only needed for an empty folder, since " + McpToolEnum.WRITE_FILE.toolName()
                + " already creates parent folders for a new file");
        this.server = server;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(CreateDirectoryParamEnum.FILE_PATH.key());
        if (fp == null || fp.isBlank()) {
            return CreateDirectoryParamEnum.FILE_PATH.key() + " is required.";
        }
        String sessionId = session.getId();
        if (!McpHookServer.isFileWritable(server, sessionId, fp)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
        }
        return RefactoringProvider.createDirectory(fp);
    }
}
