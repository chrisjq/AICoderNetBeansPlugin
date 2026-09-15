package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider.MavenBuildOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOptionValidator;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class BuildMavenProjectTool extends AbstractBuildTool {

    public BuildMavenProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
              McpToolEnum.BUILD_MAVEN_PROJECT.toolName(),
              "Builds the Maven project at " + BuildMavenProjectParamEnum.PROJECT_PATH.key() + " (default: mvn package -DskipTests; "
              + BuildMavenProjectParamEnum.GOALS.key() + " and the other options below override this). "
              + "Maven projects only - do not use for Ant or Gradle projects. "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " -> INSTEAD OF Bash mvn package - requires " + BuildMavenProjectParamEnum.PROJECT_PATH.key() + "; builds Maven project (default: package -DskipTests, overridable) and returns a result summary (complete log written to a file)",
              McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " - requires " + BuildMavenProjectParamEnum.PROJECT_PATH.key() + "; builds Maven project (default: package -DskipTests, overridable) and returns a result summary (complete log written to a file)");
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        MavenToolSchema.addProperties(props, "package", true);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> goals = args.has(BuildMavenProjectParamEnum.GOALS.key())
                             ? BuildOptionValidator.toStringList(args.array(BuildMavenProjectParamEnum.GOALS.key()))
                             : List.of("package");
        boolean skipTests = args.has(BuildMavenProjectParamEnum.SKIP_TESTS.key())
                            ? args.bool(BuildMavenProjectParamEnum.SKIP_TESTS.key()) : true;
        MavenBuildOptions opts = MavenToolSchema.optionsFrom(args, goals, skipTests);
        return BuildAndTestMavenProvider.buildProject(session.getId(),
                                                      args.str(BuildMavenProjectParamEnum.PROJECT_PATH.key()), opts);
    }
}
