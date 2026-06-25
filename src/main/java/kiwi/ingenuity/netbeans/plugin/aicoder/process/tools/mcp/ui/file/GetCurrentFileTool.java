package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetCurrentFileTool extends AbstractActionTool {

    public GetCurrentFileTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.GET_CURRENT_FILE.toolName(),
                "Returns the path, line, and column of the cursor in the active editor (e.g. /path/File.java:42:5).",
                "GetCurrentFile -> INSTEAD OF asking the user - call first to know the active file and cursor position");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return EditorContextProvider.getCurrentFile();
    }
}
