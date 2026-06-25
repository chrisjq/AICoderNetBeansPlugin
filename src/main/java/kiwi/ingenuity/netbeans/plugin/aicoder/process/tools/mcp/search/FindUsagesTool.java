package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import com.google.gson.JsonObject;
import java.io.PrintWriter;
import java.io.StringWriter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FindUsagesProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import org.openide.util.Exceptions;

public class FindUsagesTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SEARCH;
    }

    @Override
    public String instruction() {
        return "FindUsages -> INSTEAD OF manual search - find all references to a class or member across the project";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.FIND_USAGES.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Find all usages of a Java class or method in the open project(s). "
                + "Returns file paths with line numbers and code snippets. "
                + "Provide a fully qualified class name (e.g. com.example.MyService), "
                + "or omit to use the symbol at the current cursor position. "
                + "Optionally restrict to a specific method name, find subtypes, "
                + "or include comment occurrences.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();

        JsonObject cn = new JsonObject();
        cn.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        cn.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Fully qualified class name (e.g. com.example.MyService). "
                + "Omit to resolve from the symbol at the current cursor position.");
        props.add(FindUsagesParamEnum.CLASS_NAME.key(), cn);

        JsonObject mn = new JsonObject();
        mn.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mn.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Optional method or field name. Omit to find all type references.");
        props.add(FindUsagesParamEnum.MEMBER_NAME.key(), mn);

        JsonObject fs = new JsonObject();
        fs.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        fs.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Also find subtypes and implementors. Default: false.");
        props.add(FindUsagesParamEnum.FIND_SUBCLASSES.key(), fs);

        JsonObject ds = new JsonObject();
        ds.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        ds.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "When findSubclasses is true, limit to direct subtypes only. Default: false (all transitive).");
        props.add(FindUsagesParamEnum.DIRECT_SUBCLASSES_ONLY.key(), ds);

        JsonObject sc = new JsonObject();
        sc.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        sc.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Include occurrences in comments. Default: false.");
        props.add(FindUsagesParamEnum.SEARCH_IN_COMMENTS.key(), sc);

        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String className = args.str(FindUsagesParamEnum.CLASS_NAME.key());
        if (className == null || className.isBlank()) {
            className = EditorContextProvider.resolveClassAtCursor();
        }
        if (className == null || className.isBlank()) {
            throw new McpArgumentException(-32602, "No className provided and no identifiable symbol at cursor");
        }
        try {
            return FindUsagesProvider.findUsages(className, args.str(FindUsagesParamEnum.MEMBER_NAME.key()),
                    args.bool(FindUsagesParamEnum.FIND_SUBCLASSES.key()), args.bool(FindUsagesParamEnum.DIRECT_SUBCLASSES_ONLY.key()), args.bool(FindUsagesParamEnum.SEARCH_IN_COMMENTS.key()));
        }
        catch (Throwable t) {
            if (t instanceof InterruptedException || t.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Exceptions.printStackTrace(t);
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            return "Exception in FindUsages:\n" + sw;
        }
    }
}
