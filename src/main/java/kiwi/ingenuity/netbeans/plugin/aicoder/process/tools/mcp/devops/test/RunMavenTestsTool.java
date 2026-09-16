package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.BuildSubmitter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.MavenToolSchema;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider.MavenBuildOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOptionValidator;

public class RunMavenTestsTool extends AbstractTestsTool {

    public RunMavenTestsTool() {
        super(McpSectionEnum.DEVOPS_TEST,
              McpToolEnum.RUN_MAVEN_TESTS.toolName(),
              "Runs the Maven project's tests at " + RunMavenTestsParamEnum.PROJECT_PATH.key() + " (default: mvn test; optionally filter by test class; "
              + RunMavenTestsParamEnum.GOALS.key() + " and the other options below override the default goal). "
              + "Maven projects only - do not use for Ant or Gradle projects. "
              + "Returns a summary; the full log is written to a file.",
              McpToolEnum.RUN_MAVEN_TESTS.toolName() + " -> INSTEAD OF Bash mvn test - requires " + RunMavenTestsParamEnum.PROJECT_PATH.key() + "; runs Maven tests (default goal: test, overridable) with optional class filter" + BuildSubmitter.QUEUE_INSTRUCTION,
              McpToolEnum.RUN_MAVEN_TESTS.toolName() + " - requires " + RunMavenTestsParamEnum.PROJECT_PATH.key() + "; runs Maven tests (default goal: test, overridable) with optional class filter" + BuildSubmitter.QUEUE_INSTRUCTION);
    }

    @Override
    protected void addOptionProperties(JsonObject props, JsonArray required) {
        MavenToolSchema.addProperties(props, "[\"test\"]", false);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        List<String> goals = args.has(RunMavenTestsParamEnum.GOALS.key())
                             ? BuildOptionValidator.toStringList(args.array(RunMavenTestsParamEnum.GOALS.key()))
                             : List.of("test");
        // Never defaults to true: skipping tests on the tool whose entire purpose is running them would be a
        // confusing default, and today's "mvn test" has never passed -DskipTests.
        boolean skipTests = args.bool(RunMavenTestsParamEnum.SKIP_TESTS.key());
        MavenBuildOptions opts = MavenToolSchema.optionsFrom(args, goals, skipTests);
        String projectPath = args.str(RunMavenTestsParamEnum.PROJECT_PATH.key());
        return BuildSubmitter.submit(McpToolEnum.RUN_MAVEN_TESTS.toolName(), args, projectPath,
                                     BuildAndTestMavenProvider.prepareRunTests(session.getId(),
                                                                               args.str(RunMavenTestsParamEnum.TEST_CLASS.key()), projectPath, opts),
                                     session);
    }
}
