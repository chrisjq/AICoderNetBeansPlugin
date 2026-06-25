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
public class BuildProjectTool extends AbstractActionTool {

    public BuildProjectTool() {
        super(McpSectionEnum.UI_BUILD,
                McpToolEnum.BUILD_PROJECT.toolName(),
                "Builds the open project using the IDE's built-in Build action. "
                + "Works for any project type (Maven, Ant, Gradle). "
                + "Fire-and-forget: result appears in the Output window. "
                + "For Maven projects where full build output is needed as text, use BuildMavenProject instead.",
                "BuildProject -> INSTEAD OF Bash build commands - IDE build action (works for any project type)");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return ProjectActionProvider.buildProject();
    }
}
