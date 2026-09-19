package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.ProjectPathUtil;

@RequiresLock(LockTypeEnum.FILE_WRITE_LOCK)
public class MoveFileTool implements McpToolInterface {

    private final McpHookServer server;
    long confirmTimeoutMillis = TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis();

    public MoveFileTool(McpHookServer server) {
        this.server = server;
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.REFACTORING;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return McpToolEnum.MOVE_FILE.toolName() + " -> moves a file; Java files use MoveRefactoring (updates package declaration "
                + "and all import references); other files use FileUtil.moveFile()";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.MOVE_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                         "Move a file to a target directory. Java files are moved via MoveRefactoring so the "
                         + "package declaration and all import references are updated automatically. "
                         + "Other file types are moved with FileUtil.moveFile(). "
                         + "Refreshes VCS status in both source and target directories after the operation.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject src = new JsonObject();
        src.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        src.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the source file.");
        props.add(MoveFileParamEnum.SOURCE_PATH.key(), src);
        JsonObject dir = new JsonObject();
        dir.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        dir.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the destination directory (must exist), or a path relative to "
                        + MoveFileParamEnum.TARGET_PROJECT_PATH.key() + " when that is given.");
        props.add(MoveFileParamEnum.TARGET_DIRECTORY.key(), dir);
        JsonObject tpp = new JsonObject();
        tpp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tpp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Optional absolute path of the OPEN project the file should end up in — omit to keep the move inside the source file's own project (default). "
                        + "When given, " + MoveFileParamEnum.TARGET_DIRECTORY.key() + " may be relative to it (e.g. " + MoveFileParamEnum.TARGET_PROJECT_PATH.key()
                        + "=/path/to/app-platform-rest, " + MoveFileParamEnum.TARGET_DIRECTORY.key() + "=src/main/java/kiwi/ingenuity/platform/rest/oauth); "
                        + "an absolute " + MoveFileParamEnum.TARGET_DIRECTORY.key() + " not under it is refused.");
        props.add(MoveFileParamEnum.TARGET_PROJECT_PATH.key(), tpp);
        JsonObject cw = new JsonObject();
        cw.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        cw.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "When a Java move reports only non-fatal warnings (e.g. a cross-module move whose target lacks a dependency on the source module), apply it anyway and report the warnings alongside the result instead of refusing. Fatal problems always refuse regardless of this flag. Default: false.");
        props.add(MoveFileParamEnum.COMMIT_WITH_WARNING.key(), cw);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(MoveFileParamEnum.SOURCE_PATH.key());
        required.add(MoveFileParamEnum.TARGET_DIRECTORY.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String sourcePath = args.require(MoveFileParamEnum.SOURCE_PATH.key());
        String rawTargetDir = args.require(MoveFileParamEnum.TARGET_DIRECTORY.key());
        String targetProjectPath = args.str(MoveFileParamEnum.TARGET_PROJECT_PATH.key());
        boolean commitWithWarning = args.bool(MoveFileParamEnum.COMMIT_WITH_WARNING.key());
        // Combined BEFORE any access check, so the check below and the eventual move agree on the same
        // absolute path — see resolveMoveTargetDirectory's own javadoc.
        RefactoringProvider.TargetDirectoryResolution resolution
                = RefactoringProvider.resolveMoveTargetDirectory(rawTargetDir, targetProjectPath);
        if (resolution.error() != null) {
            return resolution.error();
        }
        String targetDir = resolution.path();
        String sessionId = session.getId();
        // Both sides are writes: the move deletes the source from where it was and
        // creates it under the target. Neither may inherit the read exemption the
        // persistence base's index/template files carry.
        if (!McpHookServer.isFileWritable(server, sessionId, sourcePath)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, sourcePath);
        }
        if (!McpHookServer.isFileWritable(server, sessionId, targetDir)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, targetDir);
        }
        if (!new java.io.File(sourcePath).exists()) {
            return RefactoringProvider.moveFile(sourcePath, targetDir, commitWithWarning);
        }
        AiProcessEventListener listener = session.getAiProcessEventListener();
        if (listener == null) {
            return RefactoringProvider.moveFile(sourcePath, targetDir, commitWithWarning);
        }
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        listener.onAiProcessEvent(new ConfirmEvent("Move",
                                                   "Move " + ProjectPathUtil.shortPath(sourcePath) + " → "
                                                   + ProjectPathUtil.shortPath(targetDir) + "?", sourcePath, targetDir, future));
        PermissionDecision decision;
        try {
            decision = future.get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            future.complete(PermissionDecision.denied("timed out"));
            return "Timed out waiting for the user to confirm this operation — "
                    + "the user did not respond in time. You may retry.";
        }
        catch (Exception e) {
            future.complete(PermissionDecision.denied(null));
            decision = PermissionDecision.denied(null);
        }
        if (decision == null || !decision.allow()) {
            return "User declined the move — do not retry without asking.";
        }
        return RefactoringProvider.moveFile(sourcePath, targetDir, commitWithWarning);
    }
}
