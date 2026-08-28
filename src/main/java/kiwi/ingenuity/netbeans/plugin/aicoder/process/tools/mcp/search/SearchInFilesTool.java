package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.SearchProvider;

public class SearchInFilesTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SEARCH;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return McpToolEnum.SEARCH_IN_FILES.toolName() + " - text/regex search across project source";
        }
        return McpToolEnum.SEARCH_IN_FILES.toolName() + " -> INSTEAD OF Bash grep/rg - text/regex search across project source";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.SEARCH_IN_FILES.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Grep-style text/regex search across open-project source files. "
                + "Returns file:line:content matches. Capped at 200 matches; the header reports the true total. Omit " + SearchInFilesParamEnum.FILE_PATH.key() + " to search all open projects.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Any source file in the target project. Omit to search all open projects.");
        props.add(SearchInFilesParamEnum.FILE_PATH.key(), fp);
        JsonObject q = new JsonObject();
        q.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        q.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Text or regex pattern to search for.");
        props.add(SearchInFilesParamEnum.QUERY.key(), q);
        JsonObject pat = new JsonObject();
        pat.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        pat.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Glob file filter (e.g. *.java, *.xml). Default: *.java");
        props.add(SearchInFilesParamEnum.FILE_PATTERN.key(), pat);
        JsonObject cs = new JsonObject();
        cs.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        cs.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Case-sensitive match. Default: false.");
        props.add(SearchInFilesParamEnum.CASE_SENSITIVE.key(), cs);
        JsonObject rx = new JsonObject();
        rx.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        rx.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Treat " + SearchInFilesParamEnum.QUERY.key() + " as regex. Default: false (literal text).");
        props.add(SearchInFilesParamEnum.IS_REGEX.key(), rx);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(SearchInFilesParamEnum.QUERY.key());
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
        return SearchProvider.searchInFiles(
                args.str(SearchInFilesParamEnum.FILE_PATH.key()),
                args.require(SearchInFilesParamEnum.QUERY.key()),
                args.str(SearchInFilesParamEnum.FILE_PATTERN.key()),
                args.bool(SearchInFilesParamEnum.CASE_SENSITIVE.key()),
                args.bool(SearchInFilesParamEnum.IS_REGEX.key()));
    }
}
