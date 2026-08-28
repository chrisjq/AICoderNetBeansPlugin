package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.diag;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

public class RunInspectTool extends AbstractActionTool {

    public RunInspectTool() {
        super(McpSectionEnum.UI_DIALOG,
                McpToolEnum.RUN_INSPECT.toolName(),
                "For interactive work with the user, opens NetBeans Source > Inspect for static analysis of all open projects (fire-and-forget; follow with " + McpToolEnum.GET_DIAGNOSTICS.toolName() + ").",
                "" + McpToolEnum.RUN_INSPECT.toolName() + " -> INSTEAD OF manual code review - opens NetBeans static analysis for all open projects",
                "" + McpToolEnum.RUN_INSPECT.toolName() + " - opens NetBeans static analysis for all open projects");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return RefactoringProvider.runInspect();
    }
}
