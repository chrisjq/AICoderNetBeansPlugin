package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

@RequiresLock(LockTypeEnum.REFACTOR_LOCK)
public class MoveClassTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.REFACTORING;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return McpToolEnum.MOVE_CLASS.toolName() + " - moves one or more Java classes and updates ALL import references project-wide; use " + McpToolEnum.APPLY_EDIT.toolName() + " for any content changes to a moved file";
        }
        return McpToolEnum.MOVE_CLASS.toolName() + " -> INSTEAD OF " + McpToolEnum.WRITE_FILE.toolName() + "+" + McpToolEnum.DELETE_FILE.toolName() + " for Java classes — use this first to move one or more classes and update ALL import references project-wide, then use " + McpToolEnum.APPLY_EDIT.toolName() + " for any content changes to a moved file";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.MOVE_CLASS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Moves one or more Java classes to a different package, updating the package declaration and all import references; edits route through the Accept/Reject diff panel. "
                + "Exactly one of " + MoveClassParamEnum.FILE_PATH.key() + " (one file) or " + MoveClassParamEnum.FILE_PATHS.key() + " (several files, moved together in a single refactoring) is required. "
                + "With " + MoveClassParamEnum.LINE.key() + " (single-file only; rejected together with " + MoveClassParamEnum.FILE_PATHS.key() + ") it moves just the class declared at that line; without it each file moves as a whole (refused for a file with more than one top-level type). "
                + "This tool does not fall back to the focused editor — call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " if you want the file the user is looking at.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject tp = new JsonObject();
        tp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Target package (e.g. com.example.ui).");
        props.add(MoveClassParamEnum.TARGET_PACKAGE.key(), tp);
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the source file. Exactly one of this or " + MoveClassParamEnum.FILE_PATHS.key() + " is required — this tool does not fall back to the focused editor. Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " if you want the file the user is looking at.");
        props.add(MoveClassParamEnum.FILE_PATH.key(), fp);
        JsonObject fps = new JsonObject();
        fps.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject fpsItems = new JsonObject();
        fpsItems.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fps.add(ToolSchemaKeyEnum.ITEMS.key(), fpsItems);
        fps.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute paths of several source files to move together in one refactoring, each reviewed as its own change. Exactly one of this or " + MoveClassParamEnum.FILE_PATH.key() + " is required; " + MoveClassParamEnum.LINE.key() + " is rejected when this is used.");
        props.add(MoveClassParamEnum.FILE_PATHS.key(), fps);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based line of the class declaration to move. Only valid with " + MoveClassParamEnum.FILE_PATH.key() + " — rejected together with " + MoveClassParamEnum.FILE_PATHS.key() + ". Omit to move the whole file (single top-level type only). Default: move the whole file.");
        props.add(MoveClassParamEnum.LINE.key(), ln);
        JsonObject cw = new JsonObject();
        cw.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        cw.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "When a refactoring reports only non-fatal warnings, apply it anyway and report the warnings alongside the result instead of refusing. Fatal problems always refuse regardless of this flag — these tools apply changes immediately with no diff panel to review them in, so a fatal problem (the engine's own signal that the result would be broken) is never applied unreviewed. Default: false.");
        props.add(MoveClassParamEnum.COMMIT_WITH_WARNING.key(), cw);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(MoveClassParamEnum.TARGET_PACKAGE.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String fp = args.str(MoveClassParamEnum.FILE_PATH.key());
        JsonArray fpsArr = args.array(MoveClassParamEnum.FILE_PATHS.key());
        boolean hasSingle = fp != null && !fp.isBlank();
        // Presence, rather than non-emptiness, preserves the useful empty-array error below.
        boolean hasMulti = fpsArr != null;
        if (hasSingle == hasMulti) {
            throw new McpArgumentException(-32602, "Exactly one of " + MoveClassParamEnum.FILE_PATH.key()
                    + " and " + MoveClassParamEnum.FILE_PATHS.key()
                    + " is required — supplying both or neither is not allowed.");
        }

        boolean commitWithWarning = args.bool(MoveClassParamEnum.COMMIT_WITH_WARNING.key());

        if (hasSingle) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
            return RefactoringProvider.moveClass(fp, args.intOr(MoveClassParamEnum.LINE.key(), 0),
                    args.require(MoveClassParamEnum.TARGET_PACKAGE.key()), commitWithWarning);
        }

        if (args.has(MoveClassParamEnum.LINE.key())) {
            throw new McpArgumentException(-32602, MoveClassParamEnum.LINE.key()
                    + " cannot be used with " + MoveClassParamEnum.FILE_PATHS.key()
                    + " — a line number cannot identify a class across several files.");
        }
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < fpsArr.size(); index++) {
            JsonElement element = fpsArr.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || element.getAsString().isBlank()) {
                throw new McpArgumentException(-32602, MoveClassParamEnum.FILE_PATHS.key() + "[" + index
                        + "] must be a non-blank string path; received " + element);
            }
            paths.add(element.getAsString());
        }
        if (paths.isEmpty()) {
            throw new McpArgumentException(-32602, MoveClassParamEnum.FILE_PATHS.key()
                    + " must contain at least one non-null string path");
        }
        McpHookServer server = McpServerRegistry.getServer();
        String sessionId = session.getId();
        for (String path : paths) {
            if (!McpHookServer.isFileAccessible(server, sessionId, path)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, path);
            }
        }
        return RefactoringProvider.moveClasses(paths, args.require(MoveClassParamEnum.TARGET_PACKAGE.key()), commitWithWarning);
    }
}
