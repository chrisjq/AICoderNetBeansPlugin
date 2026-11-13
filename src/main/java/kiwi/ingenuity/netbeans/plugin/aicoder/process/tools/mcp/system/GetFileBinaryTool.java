package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

/**
 * Shows an image, lists or reads a zip-based archive, or hex-dumps any other binary file. Text files are refused with a
 * pointer to {@code GetFileContent}. Access and locking match {@code GetFileContentTool}.
 */
public class GetFileBinaryTool implements McpToolInterface {

    private static String description() {
        return "Shows an image or inspects a binary file. Images come back as pictures, scaled if large. "
                + "zip/jar/war/ear are listed; entry=<name> reads one. Other binaries show a paged hex dump (offset, length). "
                + "Text files: use " + McpToolEnum.GET_FILE_CONTENT.toolName() + ".";
    }

    private final McpHookServer server;

    public GetFileBinaryTool(McpHookServer server) {
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
        return McpToolEnum.GET_FILE_BINARY.toolName() + " -> views image files and inspects other binary files "
                + "(images are returned as pictures; zip, jar, war and ear files are listed; other files show a hex dump); "
                + "use " + McpToolEnum.GET_FILE_CONTENT.toolName() + " for text files. Pass "
                + GetFileBinaryParamEnum.MODE.key() + "=image, hex or list to override the automatic choice.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_FILE_BINARY.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description());
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        props.add(GetFileBinaryParamEnum.FILE_PATH.key(),
                  property("string", "Absolute path to the file."));
        props.add(GetFileBinaryParamEnum.MODE.key(),
                  property("string", "auto (default), image, hex or list. auto picks an image, an archive listing, "
                           + "a hex dump, or refuses a text file."));
        props.add(GetFileBinaryParamEnum.OFFSET.key(),
                  property("integer", "Hex mode: first byte to show (limit 2 GB). Default 0."));
        props.add(GetFileBinaryParamEnum.LENGTH.key(),
                  property("integer", "Hex mode: bytes to show. Default " + BinaryHexDump.DEFAULT_LENGTH
                           + ", maximum " + BinaryHexDump.MAX_LENGTH + "."));
        props.add(GetFileBinaryParamEnum.ENTRY.key(),
                  property("string", "For a zip, jar, war or ear file: the entry to read, by its listed name."));
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GetFileBinaryParamEnum.FILE_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    private static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty(ToolSchemaKeyEnum.TYPE.key(), type);
        property.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        return property;
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        return handleResult(args, session).text();
    }

    @Override
    public McpToolResult handleResult(ToolRequestArguments args, AbstractAiSession session)
            throws McpArgumentException {
        String fp = args.require(GetFileBinaryParamEnum.FILE_PATH.key());
        String sessionId = session.getId();
        if (sessionId == null || !server.isFileAccessible(sessionId, fp)) {
            return McpToolResult.text(McpHookServer.fileAccessDeniedMessage(server, sessionId, fp));
        }
        String mode = args.str(GetFileBinaryParamEnum.MODE.key());
        String entry = args.str(GetFileBinaryParamEnum.ENTRY.key());
        int offset = args.intOr(GetFileBinaryParamEnum.OFFSET.key(), 0);
        int length = args.intOr(GetFileBinaryParamEnum.LENGTH.key(), BinaryHexDump.DEFAULT_LENGTH);
        Path path;
        try {
            path = Path.of(fp);
        }
        catch (InvalidPathException e) {
            return McpToolResult.text("Not a valid file path: " + fp);
        }
        // Same short per-file read lock as GetFileContent. Reading the image or archive may take a moment, so the
        // lock is held only for the read itself, never across a review prompt.
        return McpToolInvoker.withFileRead(sessionId, Set.of(fp),
                                           () -> readOrReport(path, fp, mode, entry, offset, length),
                                           McpToolResult::text);
    }

    static McpToolResult readOrReport(Path path, String fp, String mode, String entry, int offset, int length) {
        try {
            return BinaryFileReader.read(path, fp, mode, entry, offset, length);
        }
        catch (IOException e) {
            return McpToolResult.text("Error reading " + fp + ": " + e.getMessage());
        }
        catch (RuntimeException e) {
            // ImageIO and the zip classes can throw unchecked exceptions on damaged input; report them as a result.
            return McpToolResult.text("Could not read " + fp + ": " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        }
    }
}
