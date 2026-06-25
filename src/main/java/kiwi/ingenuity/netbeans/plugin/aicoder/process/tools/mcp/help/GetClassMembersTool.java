package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ClassAnalysisProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractClassNameTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetClassMembersTool extends AbstractClassNameTool {

    public GetClassMembersTool() {
        super(McpSectionEnum.HELP,
                McpToolEnum.GET_CLASS_MEMBERS.toolName(),
                "Returns the methods and fields declared in a class. "
                + "Provide a fully qualified class name, or omit to use the symbol at the current cursor position.",
                "GetClassMembers -> INSTEAD OF Read + manual parsing - lists fields, methods and constructors of a class");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String cn = args.str(GetClassMembersParamEnum.CLASS_NAME.key());
        if (cn == null || cn.isBlank()) {
            cn = EditorContextProvider.resolveClassAtCursor();
        }
        if (cn == null || cn.isBlank()) {
            throw new McpArgumentException(-32602, "No className provided and no identifiable symbol at cursor");
        }
        return ClassAnalysisProvider.getClassMembers(cn);
    }
}
