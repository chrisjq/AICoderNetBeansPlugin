package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class ReformatFileTool extends AbstractFileTool {

    public ReformatFileTool() {
        super(McpSectionEnum.UI_SOURCE,
                McpToolEnum.REFORMAT_FILE.toolName(),
                "Reformat the specified Java file using the project's code style settings. "
                + "Applies indentation, brace placement, and spacing rules. "
                + "Omit filePath to use the current editor.",
                "ReformatFile -> INSTEAD OF manual formatting - applies project code style to a file");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(ReformatFileParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (server == null || sessionId == null || !server.isFileAllowed(sessionId, fp)) {
                return "Access denied: " + fp + " is outside the allowed project scope for this session.";
            }
        }
        return RefactoringProvider.reformatFile(fp);
    }
}
