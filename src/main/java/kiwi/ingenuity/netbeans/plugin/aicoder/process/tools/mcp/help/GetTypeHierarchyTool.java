package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ClassAnalysisProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractClassNameTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetTypeHierarchyTool extends AbstractClassNameTool {

    public GetTypeHierarchyTool() {
        super(McpSectionEnum.HELP,
                McpToolEnum.GET_TYPE_HIERARCHY.toolName(),
                "Returns the full supertype and subtype hierarchy for a class or interface in the open project: "
                + "direct and indirect supertypes (extends/implements chain) plus all known subtypes and implementors. "
                + "Provide a fully qualified class name, or omit to use the symbol at the current cursor position.",
                "GetTypeHierarchy -> INSTEAD OF manual search - shows the full supertype/subtype tree for a class");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String cn = args.str(GetTypeHierarchyParamEnum.CLASS_NAME.key());
        if (cn == null || cn.isBlank()) {
            cn = EditorContextProvider.resolveClassAtCursor();
        }
        if (cn == null || cn.isBlank()) {
            throw new McpArgumentException(-32602, "No className provided and no identifiable symbol at cursor");
        }
        return ClassAnalysisProvider.getTypeHierarchy(cn);
    }
}
