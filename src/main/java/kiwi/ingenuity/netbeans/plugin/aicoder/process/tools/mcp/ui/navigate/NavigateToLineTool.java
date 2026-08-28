package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.navigate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;

public class NavigateToLineTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.UI_NAVIGATION;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return McpToolEnum.NAVIGATE_TO_LINE.toolName() + " - jumps the editor to any file:line";
        }
        return McpToolEnum.NAVIGATE_TO_LINE.toolName() + " -> INSTEAD OF asking user to open file - jumps the editor to any file:line";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.NAVIGATE_TO_LINE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "For interactive work with the user, opens a file and moves to " + NavigateToLineParamEnum.LINE.key() + " when supplied; omit " + NavigateToLineParamEnum.LINE.key() + " to leave the caret unchanged. "
                + NavigateToLineParamEnum.FOCUS.key() + " defaults to true; set false to open or navigate without stealing focus.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file to open.");
        props.add(NavigateToLineParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based line to navigate to. Omit to leave the caret unchanged.");
        props.add(NavigateToLineParamEnum.LINE.key(), ln);
        JsonObject focus = new JsonObject();
        focus.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        focus.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Activate the editor. Default: true.");
        props.add(NavigateToLineParamEnum.FOCUS.key(), focus);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(NavigateToLineParamEnum.FILE_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
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
        if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
        }
        Integer line = null;
        if (args.has(NavigateToLineParamEnum.LINE.key())) {
            line = args.intOr(NavigateToLineParamEnum.LINE.key(), 1);
            if (line < 1) {
                throw new McpArgumentException(-32602, NavigateToLineParamEnum.LINE.key() + " must be at least 1");
            }
        }
        boolean focus = !args.has(NavigateToLineParamEnum.FOCUS.key()) || args.bool(NavigateToLineParamEnum.FOCUS.key());
        return EditorContextProvider.navigateToLine(fp, line, focus);
    }
}
