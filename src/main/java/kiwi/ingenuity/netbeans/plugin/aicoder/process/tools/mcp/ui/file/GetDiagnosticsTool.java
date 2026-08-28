package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.DiagnosticsProvider;

public class GetDiagnosticsTool extends AbstractActionTool {

    public GetDiagnosticsTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.GET_DIAGNOSTICS.toolName(),
                "Reports errors and warnings only for files currently open in the editor, reflecting the user's on-screen work. "
                + "Do not open files for diagnostics or treat it as build/test verification; use " + McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " and " + McpToolEnum.RUN_MAVEN_TESTS.toolName() + " (or equivalent tools) to verify changes.",
                McpToolEnum.GET_DIAGNOSTICS.toolName() + " -> reports errors/warnings only for files the user has open; do not open files or use it for build/test verification — use " + McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " and " + McpToolEnum.RUN_MAVEN_TESTS.toolName(),
                McpToolEnum.GET_DIAGNOSTICS.toolName() + " - reports errors/warnings only for files the user has open; do not open files or use it for build/test verification — use " + McpToolEnum.BUILD_MAVEN_PROJECT.toolName() + " and " + McpToolEnum.RUN_MAVEN_TESTS.toolName());
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
