package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestGradleProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class RunGradleTestsTool extends AbstractTestsTool {

    public RunGradleTestsTool() {
        super(McpSectionEnum.DEVOPS_TEST,
                McpToolEnum.RUN_GRADLE_TESTS.toolName(),
                "Runs the open Gradle project's test suite (./gradlew test). "
                + "Gradle projects only - do not use for Maven or Ant projects. "
                + "Optionally restrict to a single test class (simple or fully qualified name).",
                "RunGradleTests -> INSTEAD OF Bash gradlew test - runs Gradle tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestGradleProvider.runTests(args.str(RunGradleTestsParamEnum.TEST_CLASS.key()), args.str(RunGradleTestsParamEnum.PROJECT_PATH.key()));
    }
}
