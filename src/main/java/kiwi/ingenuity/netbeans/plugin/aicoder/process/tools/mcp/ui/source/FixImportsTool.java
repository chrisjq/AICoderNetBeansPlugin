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
public class FixImportsTool extends AbstractFileTool {

    public FixImportsTool() {
        super(McpSectionEnum.UI_SOURCE,
                McpToolEnum.FIX_IMPORTS.toolName(),
                "Adds missing imports and removes unused ones in the user's IDE editor, then saves the file.",
                "" + McpToolEnum.FIX_IMPORTS.toolName() + " -> INSTEAD OF manual import editing - removes unused and adds missing imports",
                "" + McpToolEnum.FIX_IMPORTS.toolName() + " - removes unused and adds missing imports");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(FixImportsParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
        }
        return RefactoringProvider.fixImports(fp);
    }
}
