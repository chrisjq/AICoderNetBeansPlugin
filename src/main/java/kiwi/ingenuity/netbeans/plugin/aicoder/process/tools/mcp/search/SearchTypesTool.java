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

public class SearchTypesTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SEARCH;
    }

    @Override
    public String instruction() {
        return "SearchTypes -> INSTEAD OF Glob - locate Java types by name pattern";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.SEARCH_TYPES.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Find Java types (class/interface/enum/annotation) by name pattern using the IDE index. "
                + "Returns FQN and source file path for each match, capped at 100.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Any source file in the project. Omit to use current editor.");
        props.add(SearchTypesParamEnum.FILE_PATH.key(), fp);
        JsonObject n = new JsonObject();
        n.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        n.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Type name pattern.");
        props.add(SearchTypesParamEnum.NAME.key(), n);
        JsonObject k = new JsonObject();
        k.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        k.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Match kind: prefix (default), exact, camelCase, regexp.");
        props.add(SearchTypesParamEnum.KIND.key(), k);
        JsonObject id = new JsonObject();
        id.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        id.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Include types from dependency JARs. Default: false (project source only).");
        props.add(SearchTypesParamEnum.INCLUDE_DEPS.key(), id);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(SearchTypesParamEnum.NAME.key());
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
        return SearchProvider.searchTypes(
                args.str(SearchTypesParamEnum.FILE_PATH.key()),
                args.require(SearchTypesParamEnum.NAME.key()),
                args.str(SearchTypesParamEnum.KIND.key()),
                args.bool(SearchTypesParamEnum.INCLUDE_DEPS.key()));
    }
}
