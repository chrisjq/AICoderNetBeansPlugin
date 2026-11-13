package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
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
        return "Reads a file, first flushing any unsaved editor changes. Output has a line-number gutter; pass "
                + GetFileContentParamEnum.RAW.key() + "=true for the exact file text, or " + GetFileContentParamEnum.START_LINE.key()
                + "/" + GetFileContentParamEnum.END_LINE.key() + " for a range.";
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
                + "Full rewrite: " + McpToolEnum.GET_FILE_CONTENT.toolName() + " → " + McpToolEnum.WRITE_FILE.toolName() + " (shows full diff). "
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
        JsonObject raw = new JsonObject();
        raw.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        raw.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "When true, return the exact file text (decoded with "
                        + "the file's encoding): no line-number gutter and no header. "
                        + GetFileContentParamEnum.START_LINE.key() + "/" + GetFileContentParamEnum.END_LINE.key()
                        + " still apply. Default false.");
        props.add(GetFileContentParamEnum.RAW.key(), raw);
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
        int startLine = args.intOr(GetFileContentParamEnum.START_LINE.key(), 0);
        int endLine = args.intOr(GetFileContentParamEnum.END_LINE.key(), 0);
        boolean raw = args.bool(GetFileContentParamEnum.RAW.key());
        // This may flush unsaved editor text before reading. Use the same short
        // per-file lock as writers so it cannot save/read half of a concurrent write;
        // it is acquired here, not while another tool waits at a review prompt.
        return McpToolInvoker.withFileRead(sessionId, Set.of(fp), () -> {
                                       String binary = binaryPointer(session, fp);
                                       if (binary != null) {
                                           return binary;
                                       }
                                       return EditorContextProvider.getFileContent(fp, startLine, endLine, raw);
                                   });
    }

    /**
     * The one line that stands in for a binary file, or null when the file is text or cannot be read here. The decision
     * is {@link BinaryFileSniffer#isBinary}; the MIME type is only the label in the message. A path that cannot be
     * parsed, or a file that cannot be read, falls through to the normal read, which reports the error as it always
     * has.
     */
    static String binaryPointer(AbstractAiSession session, String filePath) {
        Path path;
        try {
            path = Path.of(filePath);
        }
        catch (InvalidPathException ex) {
            return null;
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            if (!BinaryFileSniffer.isBinary(path)) {
                return null;
            }
            String mime = Files.probeContentType(path);
            String type = mime == null ? "unknown type" : mime;
            String size = humanSize(Files.size(path));
            return binaryToolEnabled(session)
                   ? "Binary file (" + type + ", " + size + "). Use " + McpToolEnum.GET_FILE_BINARY.toolName() + " to view it."
                   : "Binary file (" + type + ", " + size + "); it cannot be read as text.";
        }
        catch (IOException ex) {
            return null;
        }
    }

    /**
     * Whether this session can call GetFileBinary. A session with no handler for it has no such tool, so it is treated
     * as unavailable and the caller never names it.
     */
    private static boolean binaryToolEnabled(AbstractAiSession session) {
        McpToolInterface handler = session.getMcpToolHandlers().get(McpToolEnum.GET_FILE_BINARY);
        return handler != null && McpToolAccess.isToolEnabled(session.getSettings(), McpToolEnum.GET_FILE_BINARY, handler);
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024L * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
