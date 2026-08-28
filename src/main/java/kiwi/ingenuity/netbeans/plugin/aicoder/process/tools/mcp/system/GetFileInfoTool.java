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

/**
 * Reports metadata for any path — regular file, directory or symbolic link — without returning its contents. Cheap to
 * call before a GetFileContent when a caller's result limit might clip a large file — the byte count says up front
 * whether the read must be paged with startLine/endLine, and the encoding says which charset the bytes decode with.
 */
public class GetFileInfoTool implements McpToolInterface {

    private static String description() {
        return "Returns file/directory/symlink metadata: size, lines, encoding, MIME type, timestamps, writable/unsaved-changes flags. "
                + "For symlinks, resolves and reports target. Call before " + McpToolEnum.GET_FILE_CONTENT.toolName()
                + " on large files to decide whether to page with " + GetFileContentParamEnum.START_LINE.key() + "/"
                + GetFileContentParamEnum.END_LINE.key() + ".";
    }

    private final McpHookServer server;

    public GetFileInfoTool(McpHookServer server) {
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
        return McpToolEnum.GET_FILE_INFO.toolName() + " - reports a file's byte size, line count, encoding, MIME type, "
                + "last-modified time and age (plus created time only on Windows and macOS, absent elsewhere), "
                + "writable flag and unsaved-editor-changes flag, or a directory's immediate-entry counts "
                + "split by file/directory and hidden/non-hidden; resolves symbolic links to their target and reports the "
                + "target's info with both paths shown; a broken link is stated as such; call it before "
                + McpToolEnum.GET_FILE_CONTENT.toolName()
                + " on a large file to decide whether to page "
                + "the read with " + GetFileContentParamEnum.START_LINE.key() + "/"
                + GetFileContentParamEnum.END_LINE.key();
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_FILE_INFO.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description());
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to a file, directory or symlink.");
        props.add(GetFileInfoParamEnum.FILE_PATH.key(), fp);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GetFileInfoParamEnum.FILE_PATH.key());
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
        String fp = args.require(GetFileInfoParamEnum.FILE_PATH.key());
        String sessionId = session.getId();
        if (sessionId == null || !server.isFileAccessible(sessionId, fp)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
        }
        return EditorContextProvider.getFileInfo(fp);
    }
}
