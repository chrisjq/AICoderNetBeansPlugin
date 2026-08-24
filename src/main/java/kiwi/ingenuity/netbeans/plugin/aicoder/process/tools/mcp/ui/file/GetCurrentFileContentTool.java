package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;

public class GetCurrentFileContentTool extends AbstractActionTool {

    public GetCurrentFileContentTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName(),
                "Returns the full text content of the file currently open in the active editor, "
                + "prefixed with its absolute path. Content beyond 200,000 characters is truncated, "
                + "with a '[truncated at 200000 chars]' marker appended.",
                McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName() + " -> INSTEAD OF Read tool when you need the active editor's full text",
                "" + McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName() + " - get the active editor's full text");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return EditorContextProvider.getCurrentFileContent();
    }
}
