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
                "Builds the open Gradle project (./gradlew build -x test). "
                + "Gradle projects only - do not use for Maven or Ant projects. "
                + "On success returns the result line; on failure the complete "
                + "failure detail, never truncated. The complete log is "
                + "always written to a file whose path is included in the response. ",
                McpToolEnum.BUILD_GRADLE_PROJECT.toolName() + " -> INSTEAD OF Bash gradlew build - builds Gradle project and returns a result summary (complete log written to a file)",
                McpToolEnum.BUILD_GRADLE_PROJECT.toolName() + " - builds Gradle project and returns a result summary (complete log written to a file)");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestGradleProvider.buildProject(session.getId(), args.str(BuildGradleProjectParamEnum.PROJECT_PATH.key()));
    }
}
