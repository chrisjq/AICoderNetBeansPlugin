package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

public class FixImportsTool extends AbstractFileTool {

    public FixImportsTool() {
        super(McpSectionEnum.UI_SOURCE,
                McpToolEnum.FIX_IMPORTS.toolName(),
                "Adds missing imports for unresolved types and saves the file, without opening a dialog. "
                + "Set pickBest to also import a name that is still ambiguous. "
                + "Does not remove unused imports; OrganiseImports does that.",
                McpToolEnum.FIX_IMPORTS.toolName() + " -> INSTEAD OF manual import editing - adds missing imports headlessly and never opens a dialog; does not remove unused imports",
                McpToolEnum.FIX_IMPORTS.toolName() + " - adds missing imports headlessly and never opens a dialog; does not remove unused imports");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = super.schema(options);
        JsonObject props = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key())
                .getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        JsonObject pickBest = new JsonObject();
        pickBest.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        pickBest.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Also import a name that is still ambiguous, choosing the top-ranked candidate; ties are left unimported. Default false.");
        props.add(FixImportsParamEnum.PICK_BEST.key(), pickBest);
        return tool;
    }

    @Override
    public boolean usesOwnFileLocking() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String fp = args.str(FixImportsParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
        }
        String typeError = args.requireBooleanIfPresent(FixImportsParamEnum.PICK_BEST.key());
        if (typeError != null) {
            return typeError;
        }
        boolean pickBest = args.bool(FixImportsParamEnum.PICK_BEST.key());
        if (fp == null || fp.isBlank()) {
            return RefactoringProvider.fixImports(fp, pickBest);
        }
        return McpToolInvoker.withFileMutation(session.getId(), Set.of(fp),
                () -> RefactoringProvider.fixImports(fp, pickBest));
    }
}
