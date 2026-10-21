package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;

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
        String refusal = McpToolAccess.refusalMessage(session.getSettings(), McpToolGroupEnum.CLIPBOARD);
        if (refusal != null) {
            throw new McpArgumentException(-32602, refusal);
        }
        return EditorContextProvider.getClipboard();
    }
}
