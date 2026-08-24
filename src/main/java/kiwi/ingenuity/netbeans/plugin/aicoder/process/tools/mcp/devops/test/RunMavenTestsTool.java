package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class RunMavenTestsTool extends AbstractTestsTool {

    public RunMavenTestsTool() {
        super(McpSectionEnum.DEVOPS_TEST,
                McpToolEnum.RUN_MAVEN_TESTS.toolName(),
                "Runs the open Maven project's test suite (mvn test). "
                + "Maven projects only - do not use for Ant or Gradle projects. "
                + "Optionally restrict to a single test class (simple or fully qualified name). "
                + "On success returns the results summary and result line; on failure the complete "
                + "failure detail with every failed test, never truncated. The complete log is "
                + "always written to a file whose path is included in the response. ",
                McpToolEnum.RUN_MAVEN_TESTS.toolName() + " -> INSTEAD OF Bash mvn test - runs Maven tests with optional class filter",
                McpToolEnum.RUN_MAVEN_TESTS.toolName() + " - runs Maven tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.runTests(session.getId(), args.str(RunMavenTestsParamEnum.TEST_CLASS.key()), args.str(RunMavenTestsParamEnum.PROJECT_PATH.key()));
    }
}
