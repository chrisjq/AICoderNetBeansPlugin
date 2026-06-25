package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.DiagnosticsProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetDiagnosticsTool extends AbstractActionTool {

    public GetDiagnosticsTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.GET_DIAGNOSTICS.toolName(),
                "Returns compiler errors and warnings for all open Java files in the IDE.",
                "GetDiagnostics -> INSTEAD OF Bash compiler invocation - check for compiler errors BEFORE proposing fixes or building");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return DiagnosticsProvider.getDiagnostics();
    }
}
