package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractClassNameTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ClassAnalysisProvider;

public class GetClassMembersTool extends AbstractClassNameTool {

    public GetClassMembersTool() {
        super(McpSectionEnum.HELP,
                McpToolEnum.GET_CLASS_MEMBERS.toolName(),
                "Returns the methods and fields declared in a class. "
                + "Provide a fully qualified class name; it is required, and is not resolved from the user's cursor.",
                McpToolEnum.GET_CLASS_MEMBERS.toolName() + " -> INSTEAD OF Read + manual parsing - lists fields, methods and constructors of a class",
                McpToolEnum.GET_CLASS_MEMBERS.toolName() + " - lists fields, methods and constructors of a class");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String cn = args.str(GetClassMembersParamEnum.CLASS_NAME.key());
        if (cn == null || cn.isBlank()) {
            // Deliberately no fallback to the symbol under the cursor. The caller
            // cannot see where the cursor is, so the same call would answer about
            // a different class depending on where the user last clicked — and
            // the answer never says which class it described.
            throw new McpArgumentException(-32602,
                    GetClassMembersParamEnum.CLASS_NAME.key() + " is required — this tool does not read the symbol under the user's cursor. "
                    + "Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " for the user's position, or "
                    + McpToolEnum.SEARCH_TYPES.toolName() + " to find the class you mean.");
        }
        return ClassAnalysisProvider.getClassMembers(cn);
    }
}
