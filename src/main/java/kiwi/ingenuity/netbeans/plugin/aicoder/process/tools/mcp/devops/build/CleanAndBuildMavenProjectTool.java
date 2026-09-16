package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractBuildTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.BuildSubmitter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider.MavenBuildOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOptionValidator;

public class CleanAndBuildMavenProjectTool extends AbstractBuildTool {

    public CleanAndBuildMavenProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
              McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName(),
              "Cleans and builds the Maven project at " + CleanAndBuildMavenProjectParamEnum.PROJECT_PATH.key() + " (default: mvn clean package -DskipTests; "
              + CleanAndBuildMavenProjectParamEnum.GOALS.key() + " and the other options below override this — "
              + CleanAndBuildMavenProjectParamEnum.GOALS.key() + " REPLACES the default entirely, so include \"clean\" yourself if you still want it with custom goals). "
              + "Maven projects only - do not use for Ant or Gradle projects. "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName() + " -> INSTEAD OF Bash mvn clean package - requires " + CleanAndBuildMavenProjectParamEnum.PROJECT_PATH.key() + "; cleans and builds Maven project (default: clean package -DskipTests, overridable) and returns a result summary (complete log written to a file)" + BuildSubmitter.QUEUE_INSTRUCTION,
              McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName() + " - requires " + CleanAndBuildMavenProjectParamEnum.PROJECT_PATH.key() + "; cleans and builds Maven project (default: clean package -DskipTests, overridable) and returns a result summary (complete log written to a file)" + BuildSubmitter.QUEUE_INSTRUCTION);
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        MavenToolSchema.addProperties(props, "[\"clean\", \"package\"]", true);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> goals = args.has(CleanAndBuildMavenProjectParamEnum.GOALS.key())
                             ? BuildOptionValidator.toStringList(args.array(CleanAndBuildMavenProjectParamEnum.GOALS.key()))
                             : List.of("clean", "package");
        boolean skipTests = args.has(CleanAndBuildMavenProjectParamEnum.SKIP_TESTS.key())
                            ? args.bool(CleanAndBuildMavenProjectParamEnum.SKIP_TESTS.key()) : true;
        MavenBuildOptions opts = MavenToolSchema.optionsFrom(args, goals, skipTests);
        String projectPath = args.str(CleanAndBuildMavenProjectParamEnum.PROJECT_PATH.key());
        return BuildSubmitter.submit(McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT.toolName(), args, projectPath,
                                     BuildAndTestMavenProvider.prepareCleanAndBuildProject(session.getId(), projectPath, opts),
                                     session);
    }
}
