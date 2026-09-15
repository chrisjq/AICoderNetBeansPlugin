package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.JavadocProvider;

public class GetJavadocTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.HELP;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return McpToolEnum.GET_JAVADOC.toolName() + " - returns Javadoc for any class or member";
        }
        return McpToolEnum.GET_JAVADOC.toolName() + " -> INSTEAD OF web search - returns Javadoc for any class or member";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_JAVADOC.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                         "Lists a class's public and protected member signatures, resolved against the classpath of the required " + GitCommonParamEnum.PROJECT_PATH.key() + ". "
                         + "A class lookup includes the class's own Javadoc; member Javadoc is returned only for members named with "
                         + GetJavadocParamEnum.MEMBER_NAME.key() + ". Javadoc is read the same way the editor shows it. "
                         + "For a library class with no Javadoc, run " + McpToolEnum.DOWNLOAD_MAVEN_SOURCES.toolName() + " or "
                         + McpToolEnum.DOWNLOAD_MAVEN_JAVADOC.toolName() + " first.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject pp = new JsonObject();
        pp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        pp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Required absolute path to the open project root whose classpath the class is resolved against.");
        props.add(GitCommonParamEnum.PROJECT_PATH.key(), pp);
        JsonObject cn = new JsonObject();
        cn.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        cn.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Fully qualified class name (e.g. org.netbeans.modules.refactoring.api.RefactoringSession).");
        props.add(GetJavadocParamEnum.CLASS_NAME.key(), cn);
        JsonObject mn = new JsonObject();
        mn.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mn.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Optional method, constructor or field name (substring match), searched across inherited members too. Javadoc text is included only for members matched this way. Omit to get the class's own Javadoc plus its own public and protected member signatures, without member Javadoc.");
        props.add(GetJavadocParamEnum.MEMBER_NAME.key(), mn);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(GitCommonParamEnum.PROJECT_PATH.key());
        required.add(GetJavadocParamEnum.CLASS_NAME.key());
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
        return JavadocProvider.getJavadoc(
                args.require(GitCommonParamEnum.PROJECT_PATH.key()),
                args.require(GetJavadocParamEnum.CLASS_NAME.key()),
                args.str(GetJavadocParamEnum.MEMBER_NAME.key()));
    }
}
