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
public class CleanAndBuildMavenProjectTool extends AbstractBuildTool {

    public CleanAndBuildMavenProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
                McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName(),
                "Cleans then builds the open Maven project (mvn clean package -DskipTests). "
                + "Maven projects only - do not use for Ant or Gradle projects. "
                + "On success returns the results summary and result line; on failure the complete "
                + "failure detail including compile errors, never truncated. The complete log is "
                + "always written to a file whose path is included in the response. ",
                McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName() + " -> INSTEAD OF Bash mvn clean package - cleans and builds Maven project and returns a result summary (complete log written to a file)",
                McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName() + " - cleans and builds Maven project and returns a result summary (complete log written to a file)");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.cleanAndBuildProject(session.getId(), args.str(CleanAndBuildMavenProjectParamEnum.PROJECT_PATH.key()));
    }
}
