package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class BuildAntProjectTool extends AbstractBuildTool {

    public BuildAntProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
                McpToolEnum.BUILD_ANT_PROJECT.toolName(),
                "Builds the Ant project at " + BuildAntProjectParamEnum.PROJECT_PATH.key() + " (ant jar)."
                + "Returns a summary; the full log is written to a file.",
                McpToolEnum.BUILD_ANT_PROJECT.toolName() + " -> INSTEAD OF Bash ant jar - requires " + BuildAntProjectParamEnum.PROJECT_PATH.key() + "; builds Ant project and returns a result summary (complete log written to a file)",
                McpToolEnum.BUILD_ANT_PROJECT.toolName() + " - requires " + BuildAntProjectParamEnum.PROJECT_PATH.key() + "; builds Ant project and returns a result summary (complete log written to a file)");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestAntProvider.buildProject(session.getId(), args.str(BuildAntProjectParamEnum.PROJECT_PATH.key()));
    }
}
