package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
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
                + "Optionally restrict to a single test class (simple or fully qualified name). "
                + "On success returns the result line; on failure the complete "
                + "failure detail, never truncated. The complete log is "
                + "always written to a file whose path is included in the response. ",
                McpToolEnum.RUN_GRADLE_TESTS.toolName() + " -> INSTEAD OF Bash gradlew test - runs Gradle tests with optional class filter",
                McpToolEnum.RUN_GRADLE_TESTS.toolName() + " - runs Gradle tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestGradleProvider.runTests(session.getId(), args.str(RunGradleTestsParamEnum.TEST_CLASS.key()), args.str(RunGradleTestsParamEnum.PROJECT_PATH.key()));
    }
}
