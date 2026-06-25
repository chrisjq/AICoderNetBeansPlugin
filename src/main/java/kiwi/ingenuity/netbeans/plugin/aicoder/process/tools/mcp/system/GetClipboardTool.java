package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetClipboardTool extends AbstractActionTool {

    public GetClipboardTool() {
        super(McpSectionEnum.SYSTEM,
                McpToolEnum.GET_CLIPBOARD.toolName(),
                "Returns the current text content of the system clipboard.",
                "GetClipboard -> INSTEAD OF asking the user to paste - reads current clipboard content");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return EditorContextProvider.getClipboard();
    }
}
