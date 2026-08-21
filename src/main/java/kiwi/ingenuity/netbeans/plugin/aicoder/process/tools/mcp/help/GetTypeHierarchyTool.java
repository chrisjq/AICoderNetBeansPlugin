package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractClassNameTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ClassAnalysisProvider;

public class GetTypeHierarchyTool extends AbstractClassNameTool {

    public GetTypeHierarchyTool() {
        super(McpSectionEnum.HELP,
                McpToolEnum.GET_TYPE_HIERARCHY.toolName(),
                "Returns the full supertype and subtype hierarchy for a class or interface in the open project: "
                + "direct and indirect supertypes (extends/implements chain) plus all known subtypes and implementors. "
                + "Provide a fully qualified class name; it is required, and is not resolved from the user's cursor.",
                McpToolEnum.GET_TYPE_HIERARCHY.toolName() + " -> INSTEAD OF manual search - shows the full supertype/subtype tree for a class",
                McpToolEnum.GET_TYPE_HIERARCHY.toolName() + " - shows the full supertype/subtype tree for a class");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String cn = args.str(GetTypeHierarchyParamEnum.CLASS_NAME.key());
        if (cn == null || cn.isBlank()) {
            // No cursor fallback — see GetClassMembersTool for the reasoning.
            throw new McpArgumentException(-32602,
                    "className is required — this tool does not read the symbol under the user's cursor. "
                    + "Call GetCurrentFile for the user's position, or SearchTypes to find the class you mean.");
        }
        return ClassAnalysisProvider.getTypeHierarchy(cn);
    }
}
