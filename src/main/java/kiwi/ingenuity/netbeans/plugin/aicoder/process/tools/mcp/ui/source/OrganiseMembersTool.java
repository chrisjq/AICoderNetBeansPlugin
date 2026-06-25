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
public class OrganiseMembersTool extends AbstractFileTool {

    public OrganiseMembersTool() {
        super(McpSectionEnum.UI_SOURCE,
                McpToolEnum.ORGANISE_MEMBERS.toolName(),
                "Organise members: sorts class members (fields, constructors, methods) "
                + "according to the configured member order in Tools > Options > Editor > Java. "
                + "Omit filePath to use the current editor.",
                "OrganiseMembers -> INSTEAD OF manual member reordering - sorts class members by configured order");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(OrganiseMembersParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (server == null || sessionId == null || !server.isFileAllowed(sessionId, fp)) {
                return "Access denied: " + fp + " is outside the allowed project scope for this session.";
            }
        }
        return RefactoringProvider.organiseMembers(fp);
    }
}
