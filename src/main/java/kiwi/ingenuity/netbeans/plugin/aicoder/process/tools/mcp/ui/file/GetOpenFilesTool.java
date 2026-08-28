package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;

public class GetOpenFilesTool extends AbstractActionTool {

    public GetOpenFilesTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.GET_OPEN_FILES.toolName(),
                "Returns a list of all files currently open in the IDE.",
                McpToolEnum.GET_OPEN_FILES.toolName() + " -> INSTEAD OF asking the user - lists all open editor tabs",
                McpToolEnum.GET_OPEN_FILES.toolName() + " - lists all open editor tabs");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return EditorContextProvider.getOpenFiles();
    }
}
