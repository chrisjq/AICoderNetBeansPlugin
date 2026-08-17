package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;

public class GetFileContentTool implements McpToolInterface {

    /**
     * Model-facing tool description. Callers with ONLY_MCP_TOOL_ACCESS have no
     * built-in Read tool, so the comparison against it is omitted rather than
     * pointing them at something they cannot call.
     */
    private static String description(Set<McpInstructionOptionEnum> options) {
        StringBuilder sb = new StringBuilder(
                "Returns the in-memory content of a file (including unsaved editor changes). ");
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            sb.append("Use this for project source files so you see what the IDE currently holds, "
                    + "not what is on disk. ");
        }
        else {
            sb.append("Use this INSTEAD OF the built-in Read tool for project source files so you see "
                    + "what the IDE currently holds, not what is on disk. ");
        }
        sb.append("Optionally restrict to a line range using startLine and endLine.");
        return sb.toString();
    }

    private final McpHookServer server;

    public GetFileContentTool(McpHookServer server) {
        this.server = server;
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SYSTEM;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return "GetFileContent - reads project source files with live NetBeans annotations (compilation errors, warnings); do not guess paths — use GetProjectStructure for package layout or SearchSymbols/SearchInFiles to locate a file first";
        }
        return "GetFileContent -> INSTEAD OF Read tool for project source files; reads NetBeans "
                + "in-memory content including unsaved changes. Full rewrite: GetFileContent → SaveFile(content). "
                + "Partial edit: GetFileContent → SaveFile (flush) → Read (built-in) → Edit (built-in). "
                + "Do not guess paths — use GetProjectStructure for package layout or SearchSymbols/SearchInFiles to locate a file first";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_FILE_CONTENT.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description(options));
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file.");
        props.add(GetFileContentParamEnum.FILE_PATH.key(), fp);
        JsonObject sl = new JsonObject();
        sl.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        sl.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "First line to include (1-based, inclusive). Omit for beginning of file.");
        props.add(GetFileContentParamEnum.START_LINE.key(), sl);
        JsonObject el = new JsonObject();
        el.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        el.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Last line to include (1-based, inclusive). Omit for end of file.");
        props.add(GetFileContentParamEnum.END_LINE.key(), el);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GetFileContentParamEnum.FILE_PATH.key());
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
        String fp = args.require(GetFileContentParamEnum.FILE_PATH.key());
        String sessionId = session.getId();
        if (sessionId == null || !server.isFileAllowed(sessionId, fp)) {
            return "Access denied: " + fp + " is outside the allowed project scope for this session.";
        }
        return EditorContextProvider.getFileContent(fp, args.intOr(GetFileContentParamEnum.START_LINE.key(), 0), args.intOr(GetFileContentParamEnum.END_LINE.key(), 0));
    }
}
