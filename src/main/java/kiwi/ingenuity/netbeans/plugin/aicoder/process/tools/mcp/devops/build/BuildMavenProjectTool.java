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
                "Builds the Maven project at " + BuildMavenProjectParamEnum.PROJECT_PATH.key() + " (mvn package -DskipTests). "
                + "Maven projects only - do not use for Ant or Gradle projects. "
                + "Returns a summary; the full log is written to a file.",
                McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " -> INSTEAD OF Bash mvn package - requires " + BuildMavenProjectParamEnum.PROJECT_PATH.key() + "; builds Maven project and returns a result summary (complete log written to a file)",
                McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " - requires " + BuildMavenProjectParamEnum.PROJECT_PATH.key() + "; builds Maven project and returns a result summary (complete log written to a file)");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.buildProject(session.getId(), args.str(BuildMavenProjectParamEnum.PROJECT_PATH.key()));
    }
}
