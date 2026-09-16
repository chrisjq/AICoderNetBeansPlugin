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

public class BuildGradleProjectTool extends AbstractBuildTool {

    public BuildGradleProjectTool() {
        super(McpSectionEnum.DEVOPS_BUILD,
              McpToolEnum.BUILD_GRADLE_PROJECT.toolName(),
              "Builds the Gradle project at " + BuildGradleProjectParamEnum.PROJECT_PATH.key() + " (default: ./gradlew build -x test; "
              + BuildGradleProjectParamEnum.TASKS.key() + " and the other options below override this). "
              + "Gradle projects only - do not use for Maven or Ant projects. "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.BUILD_GRADLE_PROJECT.toolName() + " -> INSTEAD OF Bash gradlew build - requires " + BuildGradleProjectParamEnum.PROJECT_PATH.key() + "; builds Gradle project (default: build, tests skipped, overridable) and returns a result summary (complete log written to a file)" + BuildSubmitter.QUEUE_INSTRUCTION,
              McpToolEnum.BUILD_GRADLE_PROJECT.toolName() + " - requires " + BuildGradleProjectParamEnum.PROJECT_PATH.key() + "; builds Gradle project (default: build, tests skipped, overridable) and returns a result summary (complete log written to a file)" + BuildSubmitter.QUEUE_INSTRUCTION);
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        GradleToolSchema.addProperties(props, "[\"build\"]", true);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> tasks = GradleToolSchema.tasksOrDefault(args, BuildGradleProjectParamEnum.TASKS.key(), List.of("build"));
        boolean skipTests = args.has(BuildGradleProjectParamEnum.SKIP_TESTS.key())
                            ? args.bool(BuildGradleProjectParamEnum.SKIP_TESTS.key()) : true;
        GradleBuildOptions opts = GradleToolSchema.optionsFrom(args, tasks, skipTests);
        String projectPath = args.str(BuildGradleProjectParamEnum.PROJECT_PATH.key());
        return BuildSubmitter.submit(McpToolEnum.BUILD_GRADLE_PROJECT.toolName(), args, projectPath,
                                     BuildAndTestGradleProvider.prepareBuildProject(session.getId(), projectPath, opts), session);
    }
}
