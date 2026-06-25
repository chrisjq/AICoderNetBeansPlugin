package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ProjectActionProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class CleanAndBuildProjectTool extends AbstractActionTool {

    public CleanAndBuildProjectTool() {
        super(McpSectionEnum.UI_BUILD,
                McpToolEnum.CLEAN_AND_BUILD_PROJECT.toolName(),
                "Cleans then rebuilds the open project using the IDE's built-in Clean and Build action. "
                + "Works for any project type (Maven, Ant, Gradle). "
                + "Fire-and-forget: result appears in the Output window.",
                "CleanAndBuildProject -> INSTEAD OF Bash clean then build - IDE clean+build action for any project type");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return ProjectActionProvider.cleanAndBuildProject();
    }
}
