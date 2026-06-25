package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
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
                + "Optionally restrict to a single test class (simple or fully qualified name).",
                "RunMavenTests -> INSTEAD OF Bash mvn test - runs Maven tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestMavenProvider.runTests(args.str(RunMavenTestsParamEnum.TEST_CLASS.key()), args.str(RunMavenTestsParamEnum.PROJECT_PATH.key()));
    }
}
