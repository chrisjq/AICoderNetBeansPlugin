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
                "Runs the Maven project's tests at " + RunMavenTestsParamEnum.PROJECT_PATH.key() + " (mvn test); optionally filter by test class. "
                + "Maven projects only - do not use for Ant or Gradle projects. "
                + "Returns a summary; the full log is written to a file.",
                McpToolEnum.RUN_MAVEN_TESTS.toolName() + " -> INSTEAD OF Bash mvn test - requires " + RunMavenTestsParamEnum.PROJECT_PATH.key() + "; runs Maven tests with optional class filter",
                McpToolEnum.RUN_MAVEN_TESTS.toolName() + " - requires " + RunMavenTestsParamEnum.PROJECT_PATH.key() + "; runs Maven tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.runTests(session.getId(), args.str(RunMavenTestsParamEnum.TEST_CLASS.key()), args.str(RunMavenTestsParamEnum.PROJECT_PATH.key()));
    }
}
