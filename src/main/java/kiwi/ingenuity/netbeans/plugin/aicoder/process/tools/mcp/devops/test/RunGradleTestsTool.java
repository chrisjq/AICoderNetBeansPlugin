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
                "Runs the Gradle project's tests at " + RunGradleTestsParamEnum.PROJECT_PATH.key() + " (./gradlew test); optionally filter by test class. "
                + "Gradle projects only - do not use for Maven or Ant projects. "
                + "Returns a summary; the full log is written to a file.",
                McpToolEnum.RUN_GRADLE_TESTS.toolName() + " -> INSTEAD OF Bash gradlew test - requires " + RunGradleTestsParamEnum.PROJECT_PATH.key() + "; runs Gradle tests with optional class filter",
                McpToolEnum.RUN_GRADLE_TESTS.toolName() + " - requires " + RunGradleTestsParamEnum.PROJECT_PATH.key() + "; runs Gradle tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestGradleProvider.runTests(session.getId(), args.str(RunGradleTestsParamEnum.TEST_CLASS.key()), args.str(RunGradleTestsParamEnum.PROJECT_PATH.key()));
    }
}
