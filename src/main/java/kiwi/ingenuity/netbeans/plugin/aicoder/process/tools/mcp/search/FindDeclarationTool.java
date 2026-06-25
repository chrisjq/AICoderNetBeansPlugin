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

public class FindDeclarationTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SEARCH;
    }

    @Override
    public String instruction() {
        return "FindDeclaration -> INSTEAD OF manual search - resolve a symbol to its declaration (returns file:line)";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.FIND_DECLARATION.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Go-to-definition: resolves the Java symbol at a file position to its declaration. "
                + "Returns file:line (source) or [binary] FQN (for library types with no source). "
                + "When column is omitted, resolves the first identifier found on the given line.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file containing the reference. Omit to use current editor.");
        props.add(FindDeclarationParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based line number.");
        props.add(FindDeclarationParamEnum.LINE.key(), ln);
        JsonObject col = new JsonObject();
        col.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        col.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based column. Omit to use first identifier on the line.");
        props.add(FindDeclarationParamEnum.COLUMN.key(), col);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(FindDeclarationParamEnum.LINE.key());
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
        return SearchProvider.findDeclaration(
                args.str(FindDeclarationParamEnum.FILE_PATH.key()),
                args.intOr(FindDeclarationParamEnum.LINE.key(), 1),
                args.intOr(FindDeclarationParamEnum.COLUMN.key(), 1));
    }
}
