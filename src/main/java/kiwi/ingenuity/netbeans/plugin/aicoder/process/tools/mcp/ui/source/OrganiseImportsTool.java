package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class OrganiseImportsTool extends AbstractFileTool {

    public OrganiseImportsTool() {
        super(McpSectionEnum.UI_SOURCE,
                McpToolEnum.ORGANISE_IMPORTS.toolName(),
                "Runs the IDE Organise Imports action in the user's editor, then saves the file.",
                McpToolEnum.ORGANISE_IMPORTS.toolName() + " -> INSTEAD OF manual import sorting - sorts and groups existing imports",
                McpToolEnum.ORGANISE_IMPORTS.toolName() + " - sorts and groups existing imports");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(OrganiseImportsParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
        }
        return RefactoringProvider.organiseImports(fp);
    }
}
