package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestGradleProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class BuildGradleProjectTool extends AbstractBuildTool {

    public BuildGradleProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
                McpToolEnum.BUILD_GRADLE_PROJECT.toolName(),
                "Builds the Gradle project at " + BuildGradleProjectParamEnum.PROJECT_PATH.key() + " (./gradlew build -x test). "
                + "Gradle projects only - do not use for Maven or Ant projects. "
                + "Returns a summary; the full log is written to a file.",
                McpToolEnum.BUILD_GRADLE_PROJECT.toolName() + " -> INSTEAD OF Bash gradlew build - requires " + BuildGradleProjectParamEnum.PROJECT_PATH.key() + "; builds Gradle project and returns a result summary (complete log written to a file)",
                McpToolEnum.BUILD_GRADLE_PROJECT.toolName() + " - requires " + BuildGradleProjectParamEnum.PROJECT_PATH.key() + "; builds Gradle project and returns a result summary (complete log written to a file)");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestGradleProvider.buildProject(session.getId(), args.str(BuildGradleProjectParamEnum.PROJECT_PATH.key()));
    }
}
