package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.GradleToolSchema;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestGradleProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestGradleProvider.GradleBuildOptions;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class RunGradleTestsTool extends AbstractTestsTool {

    public RunGradleTestsTool() {
        super(McpSectionEnum.DEVOPS_TEST,
              McpToolEnum.RUN_GRADLE_TESTS.toolName(),
              "Runs the Gradle project's tests at " + RunGradleTestsParamEnum.PROJECT_PATH.key() + " (default: ./gradlew test; optionally filter by test class; "
              + RunGradleTestsParamEnum.TASKS.key() + " and the other options below override the default task). "
              + "Gradle projects only - do not use for Maven or Ant projects. "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.RUN_GRADLE_TESTS.toolName() + " -> INSTEAD OF Bash gradlew test - requires " + RunGradleTestsParamEnum.PROJECT_PATH.key() + "; runs Gradle tests (default task: test, overridable) with optional class filter",
              McpToolEnum.RUN_GRADLE_TESTS.toolName() + " - requires " + RunGradleTestsParamEnum.PROJECT_PATH.key() + "; runs Gradle tests (default task: test, overridable) with optional class filter");
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        GradleToolSchema.addProperties(props, "[\"test\"]", false);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> tasks = GradleToolSchema.tasksOrDefault(args, RunGradleTestsParamEnum.TASKS.key(), List.of("test"));
        // Never defaults to true — skipping tests on the tool whose entire purpose is running them would be a
        // confusing default, and today's "gradlew test" has never passed -x test.
        boolean skipTests = args.bool(RunGradleTestsParamEnum.SKIP_TESTS.key());
        GradleBuildOptions opts = GradleToolSchema.optionsFrom(args, tasks, skipTests);
        return BuildAndTestGradleProvider.runTests(session.getId(),
                                                   args.str(RunGradleTestsParamEnum.TEST_CLASS.key()), args.str(RunGradleTestsParamEnum.PROJECT_PATH.key()), opts);
    }
}
