package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
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
                + "Returns the full build output including any compile errors.",
                "BuildMavenProject -> INSTEAD OF Bash mvn package - builds Maven project and returns full output");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.buildProject(args.str(BuildMavenProjectParamEnum.PROJECT_PATH.key()));
    }
}
