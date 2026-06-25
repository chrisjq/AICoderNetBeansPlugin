package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetGitStatusTool extends AbstractActionTool {

    public GetGitStatusTool() {
        super(McpSectionEnum.GIT,
                McpToolEnum.GET_GIT_STATUS.toolName(),
                "Returns the git status of the open project (branch name + short file status). "
                + "Equivalent to: git status --short --branch",
                "GetGitStatus -> INSTEAD OF Bash git status - returns branch name and file status");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return GitProvider.getGitStatus();
    }
}
