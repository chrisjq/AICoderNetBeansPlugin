package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.JavadocProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetJavadocTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.HELP;
    }

    @Override
    public String instruction() {
        return "GetJavadoc -> INSTEAD OF web search - returns Javadoc for any class or member";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_JAVADOC.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Returns Javadoc and method signatures for a class on the project classpath. "
                + "Run DownloadMavenJavadoc first if doc comments are missing for library classes. "
                + "Use memberName to filter to a specific method or field.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject cn = new JsonObject();
        cn.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        cn.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Fully qualified class name (e.g. org.netbeans.modules.refactoring.api.RefactoringSession).");
        props.add(GetJavadocParamEnum.CLASS_NAME.key(), cn);
        JsonObject mn = new JsonObject();
        mn.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mn.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Optional method or field name to filter results to. Omit to return all public members.");
        props.add(GetJavadocParamEnum.MEMBER_NAME.key(), mn);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GetJavadocParamEnum.CLASS_NAME.key());
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
        return JavadocProvider.getJavadoc(args.require(GetJavadocParamEnum.CLASS_NAME.key()), args.str(GetJavadocParamEnum.MEMBER_NAME.key()));
    }
}
