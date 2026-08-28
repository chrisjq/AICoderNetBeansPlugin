package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

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
 * Pattern-matches lines within a single named file. Fills a gap {@link SearchInFilesTool} cannot: that tool searches a
 * project's source classpath roots and cannot target one specific file, and it never sees anything outside those roots
 * at all — a README, a build script, or (the motivating case) this session's own {@code tool_results} logs under its
 * config directory, none of which sit on a source classpath.
 */
public class FilterFileContentTool implements McpToolInterface {

    private static String description() {
        return "Pattern-matches lines in a file and returns matching line numbers and content. "
                + "Default: literal text, 200 matches max, header reports true total.";
    }

    private final McpHookServer server;

    public FilterFileContentTool(McpHookServer server) {
        this.server = server;
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SEARCH;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return McpToolEnum.FILTER_FILE_CONTENT.toolName() + " -> INSTEAD OF Bash grep/tail on one file - "
                + "pattern-match lines in a single file by path";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.FILTER_FILE_CONTENT.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description());
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file to filter.");
        props.add(FilterFileContentParamEnum.FILE_PATH.key(), fp);
        JsonObject pat = new JsonObject();
        pat.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        pat.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Literal text or regex pattern to match each line against.");
        props.add(FilterFileContentParamEnum.PATTERN.key(), pat);
        JsonObject rx = new JsonObject();
        rx.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        rx.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Treat pattern as regex. Default: false (literal text).");
        props.add(FilterFileContentParamEnum.IS_REGEX.key(), rx);
        JsonObject cs = new JsonObject();
        cs.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        cs.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Case-sensitive match. Default: false.");
        props.add(FilterFileContentParamEnum.CASE_SENSITIVE.key(), cs);
        JsonObject cl = new JsonObject();
        cl.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        cl.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Lines of surrounding context per match (like grep -C). Default: 0.");
        props.add(FilterFileContentParamEnum.CONTEXT_LINES.key(), cl);
        JsonObject mm = new JsonObject();
        mm.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        mm.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Maximum matches to show. Default: 200. The header "
                + "reports the true total even when the result is capped.");
        props.add(FilterFileContentParamEnum.MAX_MATCHES.key(), mm);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(FilterFileContentParamEnum.FILE_PATH.key());
        required.add(FilterFileContentParamEnum.PATTERN.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        // Deliberately false even though a read flushes unsaved editor changes to
        // disk first (see EditorContextProvider.filterFileContent) — same exception
        // GetFileContentTool applies and for the same reason: what gets written is
        // the user's own text exactly as typed, not anything the AI produced, so it
        // is a save of their work rather than a change to the project. Classifying
        // this mutating would put every filter call behind the plugin-wide mutation
        // lock, serialising a read against every build, refactor and edit for no gain.
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String filePath = args.require(FilterFileContentParamEnum.FILE_PATH.key());
        String pattern = args.require(FilterFileContentParamEnum.PATTERN.key());
        String sessionId = session.getId();
        // isFileAccessible covers the session's own config directory (memory, logs,
        // tool_results) too, readable even under restrict-to-project. Build/test
        // tools park their complete logs there precisely so this tool can filter
        // them instead of shelling out to Bash grep.
        if (sessionId == null || !server.isFileAccessible(sessionId, filePath)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, filePath);
        }
        boolean isRegex = args.bool(FilterFileContentParamEnum.IS_REGEX.key());
        boolean caseSensitive = args.bool(FilterFileContentParamEnum.CASE_SENSITIVE.key());
        int contextLines = args.intOr(FilterFileContentParamEnum.CONTEXT_LINES.key(), 0, 0, 50);
        int maxMatches = args.intOr(FilterFileContentParamEnum.MAX_MATCHES.key(), 0, 0, 5000);
        return EditorContextProvider.filterFileContent(filePath, pattern, isRegex, caseSensitive, contextLines, maxMatches);
    }
}
