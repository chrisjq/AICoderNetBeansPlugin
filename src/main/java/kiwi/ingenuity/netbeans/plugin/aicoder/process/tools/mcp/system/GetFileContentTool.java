package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;

public class GetFileContentTool implements McpToolInterface {

    /**
     * Model-facing tool description. Callers with ONLY_MCP_TOOL_ACCESS have no built-in Read tool, so the comparison
     * against it is omitted rather than pointing them at something they cannot call.
     */
    private static String description(Set<McpInstructionOptionEnum> options) {
        return "Read file content with unsaved editor changes flushed first. Output includes a line-number gutter; strip it before using in "
                + McpToolEnum.APPLY_EDIT.toolName() + " " + McpToolPropertyEnum.OLD_STRING.key() + ". Omit " + GetFileContentParamEnum.START_LINE.key() + "/" + GetFileContentParamEnum.END_LINE.key() + " for full file.";
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
            return McpToolEnum.GET_FILE_CONTENT.toolName() + " - reads project source files with live NetBeans annotations (compilation errors, warnings); do not guess paths — use " + McpToolEnum.GET_PROJECT_STRUCTURE.toolName() + " for package layout or " + McpToolEnum.SEARCH_SYMBOLS.toolName() + "/" + McpToolEnum.SEARCH_IN_FILES.toolName() + " to locate a file first";
        }
        return McpToolEnum.GET_FILE_CONTENT.toolName() + " -> INSTEAD OF Read tool for project source files; saves any "
                + "unsaved editor changes first so you read what the user has on screen; output carries a line-number gutter — strip it before using in " + McpToolEnum.APPLY_EDIT.toolName() + " " + McpToolPropertyEnum.OLD_STRING.key() + ". "
                + "Full rewrite: " + McpToolEnum.GET_FILE_CONTENT.toolName() + " → " + McpToolEnum.SAVE_FILE.toolName() + " (" + McpToolPropertyEnum.CONTENT.key() + "). "
                + "Partial edit: " + McpToolEnum.GET_FILE_CONTENT.toolName() + " → Read (built-in) → Edit (built-in). "
                + "Do not guess paths — use " + McpToolEnum.GET_PROJECT_STRUCTURE.toolName() + " for package layout or " + McpToolEnum.SEARCH_SYMBOLS.toolName() + "/" + McpToolEnum.SEARCH_IN_FILES.toolName() + " to locate a file first";
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
        // Deliberately false even though reading flushes unsaved editor changes to
        // disk, which is a write. What it writes is the user's own text, exactly as
        // they typed it - nothing the AI produced - so it is a save of their work
        // rather than a change to the project. Classifying it mutating would put
        // every file read behind the plugin-wide mutation lock, serialising the most
        // frequent operation against every build, refactor and edit for no gain.
        // Reviewed and kept: this is a considered exception, not an oversight.
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String fp = args.require(GetFileContentParamEnum.FILE_PATH.key());
        String sessionId = session.getId();
        // isFileAccessible covers the session's OWN config directory too, readable
        // even under restrict-to-project. Build/test tools park their complete
        // logs there precisely so the AI can read them back here instead of
        // shelling out to Bash.
        if (sessionId == null || !server.isFileAccessible(sessionId, fp)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
        }
        return EditorContextProvider.getFileContent(fp, args.intOr(GetFileContentParamEnum.START_LINE.key(), 0), args.intOr(GetFileContentParamEnum.END_LINE.key(), 0));
    }
}
