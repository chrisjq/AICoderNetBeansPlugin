package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.SearchProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class SearchSymbolsTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SEARCH;
    }

    @Override
    public String instruction() {
        return "SearchSymbols -> INSTEAD OF Grep - find methods or fields by name";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.SEARCH_SYMBOLS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Find methods, fields, and nested types by member name across the project. "
                + "Returns enclosing type FQN, matching symbol names, and source file, capped at 100.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Any source file in the project. Omit to use current editor.");
        props.add(SearchSymbolsParamEnum.FILE_PATH.key(), fp);
        JsonObject n = new JsonObject();
        n.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        n.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Symbol name pattern.");
        props.add(SearchSymbolsParamEnum.NAME.key(), n);
        JsonObject k = new JsonObject();
        k.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        k.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Match kind: prefix (default), exact, camelCase, regexp.");
        props.add(SearchSymbolsParamEnum.KIND.key(), k);
        JsonObject id = new JsonObject();
        id.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        id.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Include symbols from dependency JARs. Default: false.");
        props.add(SearchSymbolsParamEnum.INCLUDE_DEPS.key(), id);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(SearchSymbolsParamEnum.NAME.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        return SearchProvider.searchSymbols(
                args.str(SearchSymbolsParamEnum.FILE_PATH.key()),
                args.require(SearchSymbolsParamEnum.NAME.key()),
                args.str(SearchSymbolsParamEnum.KIND.key()),
                args.bool(SearchSymbolsParamEnum.INCLUDE_DEPS.key()));
    }
}
