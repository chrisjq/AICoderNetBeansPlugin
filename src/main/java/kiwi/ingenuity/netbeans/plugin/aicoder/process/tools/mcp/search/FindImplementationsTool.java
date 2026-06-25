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

public class FindImplementationsTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SEARCH;
    }

    @Override
    public String instruction() {
        return "FindImplementations -> INSTEAD OF manual search - find all subtypes/implementors of an interface or class";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.FIND_IMPLEMENTATIONS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Find all direct subtypes/implementors of a type in project source. "
                + "Point to the class or interface keyword line. "
                + "Returns FQN and source file for each implementor.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file containing the type declaration. Omit to use current editor.");
        props.add(FindImplementationsParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based line of the class/interface declaration.");
        props.add(FindImplementationsParamEnum.LINE.key(), ln);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(FindImplementationsParamEnum.LINE.key());
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
        return SearchProvider.findImplementations(args.str(FindImplementationsParamEnum.FILE_PATH.key()), args.intOr(FindImplementationsParamEnum.LINE.key(), 1));
    }
}
