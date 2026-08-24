package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class BuildMavenProjectTool extends AbstractBuildTool {

    public BuildMavenProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
                McpToolEnum.BUILD_MAVEN_PROJECT.toolName(),
                "Builds the open Maven project (mvn package -DskipTests). "
                + "Maven projects only - do not use for Ant or Gradle projects. "
                + "On success returns the results summary and result line; on failure the complete "
                + "failure detail including compile errors, never truncated. The complete log is "
                + "always written to a file whose path is included in the response. ",
                McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " -> INSTEAD OF Bash mvn package - builds Maven project and returns a result summary (complete log written to a file)",
                McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " - builds Maven project and returns a result summary (complete log written to a file)");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.buildProject(session.getId(), args.str(BuildMavenProjectParamEnum.PROJECT_PATH.key()));
    }
}
