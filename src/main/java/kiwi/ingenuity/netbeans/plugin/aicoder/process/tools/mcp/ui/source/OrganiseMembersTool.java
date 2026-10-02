package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

public class OrganiseMembersTool extends AbstractFileTool {

    public OrganiseMembersTool() {
        super(McpSectionEnum.UI_SOURCE,
                McpToolEnum.ORGANISE_MEMBERS.toolName(),
                "Organises class members by configured member order in the user's editor, then saves the file.",
                McpToolEnum.ORGANISE_MEMBERS.toolName() + " -> INSTEAD OF manual member reordering - sorts class members by configured order",
                McpToolEnum.ORGANISE_MEMBERS.toolName() + " - sorts class members by configured order");
    }

    @Override
    public boolean usesOwnFileLocking() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(OrganiseMembersParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
        }
        if (fp == null || fp.isBlank()) {
            return RefactoringProvider.organiseMembers(fp);
        }
        return McpToolInvoker.withFileMutation(session.getId(), Set.of(fp),
                () -> RefactoringProvider.organiseMembers(fp));
    }
}
