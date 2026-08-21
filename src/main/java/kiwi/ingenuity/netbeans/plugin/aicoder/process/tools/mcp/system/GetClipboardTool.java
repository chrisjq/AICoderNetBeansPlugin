package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;

public class GetClipboardTool extends AbstractActionTool {

    public GetClipboardTool() {
        super(McpSectionEnum.SYSTEM,
                McpToolEnum.GET_CLIPBOARD.toolName(),
                "Returns the current text content of the system clipboard.",
                McpToolEnum.GET_CLIPBOARD.toolName() + " -> INSTEAD OF asking the user to paste - reads current clipboard content",
                McpToolEnum.GET_CLIPBOARD.toolName() + " - reads current clipboard content");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        if (!session.getSettings().effectiveEnableClipboardAccess()) {
            throw new McpArgumentException(-32602, "Clipboard access is disabled. Enable it in session settings or global options.");
        }
        return EditorContextProvider.getClipboard();
    }
}
