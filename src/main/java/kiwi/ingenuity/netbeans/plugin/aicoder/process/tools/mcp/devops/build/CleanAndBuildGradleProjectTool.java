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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestGradleProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestGradleProvider.GradleBuildOptions;

/**
 * New in #5 / F2: Gradle previously had no clean-then-build tool, unlike Maven (CleanAndBuildMavenProject) and Ant
 * (CleanAndBuildAntProject below).
 */
public class CleanAndBuildGradleProjectTool extends AbstractBuildTool {

    public CleanAndBuildGradleProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
              McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT.toolName(),
              "Cleans and builds the Gradle project at " + CleanAndBuildGradleProjectParamEnum.PROJECT_PATH.key() + " (default: ./gradlew clean build -x test; "
              + CleanAndBuildGradleProjectParamEnum.TASKS.key() + " and the other options below override this — "
              + CleanAndBuildGradleProjectParamEnum.TASKS.key() + " REPLACES the default entirely, so include \"clean\" yourself if you still want it with custom tasks). "
              + "Gradle projects only - do not use for Maven or Ant projects. "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT.toolName() + " -> INSTEAD OF Bash gradlew clean build - requires " + CleanAndBuildGradleProjectParamEnum.PROJECT_PATH.key() + "; cleans and builds Gradle project (default: clean build, tests skipped, overridable) and returns a result summary (complete log written to a file)" + BuildSubmitter.QUEUE_INSTRUCTION,
              McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT.toolName() + " - requires " + CleanAndBuildGradleProjectParamEnum.PROJECT_PATH.key() + "; cleans and builds Gradle project (default: clean build, tests skipped, overridable) and returns a result summary (complete log written to a file)" + BuildSubmitter.QUEUE_INSTRUCTION);
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        GradleToolSchema.addProperties(props, "[\"clean\", \"build\"]", true);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> tasks = GradleToolSchema.tasksOrDefault(args, CleanAndBuildGradleProjectParamEnum.TASKS.key(),
                                                             List.of("clean", "build"));
        boolean skipTests = args.has(CleanAndBuildGradleProjectParamEnum.SKIP_TESTS.key())
                            ? args.bool(CleanAndBuildGradleProjectParamEnum.SKIP_TESTS.key()) : true;
        GradleBuildOptions opts = GradleToolSchema.optionsFrom(args, tasks, skipTests);
        String projectPath = args.str(CleanAndBuildGradleProjectParamEnum.PROJECT_PATH.key());
        return BuildSubmitter.submit(McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT.toolName(), args, projectPath,
                                     BuildAndTestGradleProvider.prepareCleanAndBuildProject(session.getId(), projectPath, opts),
                                     session);
    }
}
