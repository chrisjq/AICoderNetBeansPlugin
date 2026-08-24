package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider;

@RequiresLock(LockTypeEnum.BUILD_LOCK)
public class RunAntTestsTool extends AbstractTestsTool {

    public RunAntTestsTool() {
        super(McpSectionEnum.DEVOPS_TEST,
                McpToolEnum.RUN_ANT_TESTS.toolName(),
                "Runs the open Ant project's test suite (ant test). "
                + "Ant projects only - do not use for Maven or Gradle projects. "
                + "Optionally restrict to a single test class via -Dtest.includes. "
                + "On success returns the result line; on failure the complete "
                + "failure detail, never truncated. The complete log is "
                + "always written to a file whose path is included in the response. ",
                McpToolEnum.RUN_ANT_TESTS.toolName() + " -> INSTEAD OF Bash ant test - runs Ant tests with optional class filter",
                McpToolEnum.RUN_ANT_TESTS.toolName() + " - runs Ant tests with optional class filter");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return BuildAndTestAntProvider.runTests(session.getId(), args.str(RunAntTestsParamEnum.TEST_CLASS.key()), args.str(RunAntTestsParamEnum.PROJECT_PATH.key()));
    }
}
