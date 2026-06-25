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
public class CleanAndBuildMavenProjectTool extends AbstractBuildTool {

    public CleanAndBuildMavenProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
                McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName(),
                "Cleans then builds the open Maven project (mvn clean package -DskipTests). "
                + "Maven projects only - do not use for Ant or Gradle projects. "
                + "Returns the full build output including any compile errors.",
                "CleanAndBuildMavenProject -> INSTEAD OF Bash mvn clean package - cleans and builds Maven project and returns full output");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.cleanAndBuildProject(args.str(CleanAndBuildMavenProjectParamEnum.PROJECT_PATH.key()));
    }
}
