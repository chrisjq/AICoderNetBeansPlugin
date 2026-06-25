package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin;

import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetPluginVersionTool extends AbstractActionTool {

    public GetPluginVersionTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.GET_PLUGIN_VERSION.toolName(),
                "Returns the currently running version of the NetBeans CC plugin.",
                "GetPluginVersion -> call before using important/replyImportant on SendAiMessage to confirm peer version supports graceful interrupt");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return Installer.VERSION;
    }
}
