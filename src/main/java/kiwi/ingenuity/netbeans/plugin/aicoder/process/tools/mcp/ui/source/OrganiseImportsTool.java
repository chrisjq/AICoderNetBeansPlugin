package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

public class OrganiseImportsTool extends AbstractFileTool {

    public OrganiseImportsTool() {
        super(McpSectionEnum.UI_SOURCE,
                McpToolEnum.ORGANISE_IMPORTS.toolName(),
                "Removes unused imports, sorts the rest by the code-style import groups, and saves the file, without opening an editor. "
                + "A javadoc-only reference keeps its import; with compile errors, only provably-unused imports are removed. "
                + "Does not add missing imports; FixImports does that.",
                McpToolEnum.ORGANISE_IMPORTS.toolName() + " -> INSTEAD OF manual import sorting - removes unused imports and sorts the rest, headless; does not add missing ones",
                McpToolEnum.ORGANISE_IMPORTS.toolName() + " - removes unused imports and sorts the rest, headless; does not add missing ones");
    }

    @Override
    public boolean usesOwnFileLocking() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(OrganiseImportsParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
        }
        if (fp == null || fp.isBlank()) {
            return RefactoringProvider.organiseImports(fp);
        }
        return McpToolInvoker.withFileMutation(session.getId(), Set.of(fp),
                () -> RefactoringProvider.organiseImports(fp));
    }
}
