package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.ProjectStructureProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GetProjectStructureTool extends AbstractActionTool {

    public GetProjectStructureTool() {
        super(McpSectionEnum.HELP,
                McpToolEnum.GET_PROJECT_STRUCTURE.toolName(),
                "Returns the Java source file tree for all open projects, organised by source root. "
                + "Use to understand the package layout before navigating or searching.",
                "GetProjectStructure -> INSTEAD OF Glob for Java project layout overview");
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return ProjectStructureProvider.getProjectStructure();
    }
}
