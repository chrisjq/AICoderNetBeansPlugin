package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.navigate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;

public class NavigateToLineTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.UI_NAVIGATION;
    }

    @Override
    public String instruction() {
        return "NavigateToLine -> INSTEAD OF asking user to open file - jumps the editor to any file:line";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.NAVIGATE_TO_LINE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Opens a file in the NetBeans editor and jumps to the specified line. "
                + "Use after FindUsages or GetTypeHierarchy results to jump directly to a location.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file to open.");
        props.add(NavigateToLineParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Line number to navigate to (1-based).");
        props.add(NavigateToLineParamEnum.LINE.key(), ln);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(NavigateToLineParamEnum.FILE_PATH.key());
        required.add(NavigateToLineParamEnum.LINE.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String fp = args.require(NavigateToLineParamEnum.FILE_PATH.key());
        var server = McpServerRegistry.getServer();
        String sessionId = session.getId();
        if (server == null || sessionId == null || !server.isFileAllowed(sessionId, fp)) {
            return "Access denied: " + fp + " is outside the allowed project scope for this session.";
        }
        return EditorContextProvider.navigateToLine(fp, args.intOr(NavigateToLineParamEnum.LINE.key(), 1));
    }
}
