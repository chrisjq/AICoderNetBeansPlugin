package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.diag;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class RunInspectTool extends AbstractActionTool {

    public RunInspectTool() {
        super(McpSectionEnum.UI_DIALOG,
                McpToolEnum.RUN_INSPECT.toolName(),
                "Opens the NetBeans Inspect dialog (Source > Inspect). "
                + "Select 'All Analysers' configuration and 'All Open Projects' scope, "
                + "then click Inspect to run static analysis across the entire codebase.",
                "RunInspect -> INSTEAD OF manual code review - opens NetBeans static analysis for all open projects");
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return RefactoringProvider.runInspect();
    }
}
