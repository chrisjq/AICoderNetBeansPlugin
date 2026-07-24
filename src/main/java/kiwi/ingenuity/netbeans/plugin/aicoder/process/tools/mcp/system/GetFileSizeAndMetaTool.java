package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

/**
 * Reports a file's size and metadata (byte count, line count, encoding) without
 * returning its contents. Cheap to call before a GetFileContent when a caller's
 * result limit might clip a large file — the byte count says up front whether
 * the read must be paged with startLine/endLine, and the encoding says which
 * charset the bytes decode with.
 */
public class GetFileSizeAndMetaTool implements McpToolInterface {

    private final McpHookServer server;

    public GetFileSizeAndMetaTool(McpHookServer server) {
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
        return "GetFileSizeAndMeta - reports a file's byte size, line count, encoding, last-modified "
                + "time and age, writable flag and unsaved-editor-changes flag without returning its "
                + "content; call it before GetFileContent on a large file to decide whether to page "
                + "the read with startLine/endLine";
    }

    private static String description() {
        return "Returns a file's metadata without its content: exact byte size, line count, the text "
                + "encoding the IDE uses for it, the last-modified time and age in seconds, whether "
                + "the file is writable, and whether the editor holds unsaved changes for it. Call "
                + "this before GetFileContent on a large file to decide whether the read must be "
                + "paged with startLine/endLine. Size and line count are the on-disk copy; an "
                + "\"unsaved editor changes\" flag warns when the editor's in-memory copy has diverged.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_FILE_SIZE_AND_META.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description());
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file.");
        props.add(GetFileSizeAndMetaParamEnum.FILE_PATH.key(), fp);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GetFileSizeAndMetaParamEnum.FILE_PATH.key());
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
        String fp = args.require(GetFileSizeAndMetaParamEnum.FILE_PATH.key());
        String sessionId = session.getId();
        if (sessionId == null || !server.isFileAllowed(sessionId, fp)) {
            return "Access denied: " + fp + " is outside the allowed project scope for this session.";
        }
        return EditorContextProvider.getFileSizeAndMeta(fp);
    }
}
