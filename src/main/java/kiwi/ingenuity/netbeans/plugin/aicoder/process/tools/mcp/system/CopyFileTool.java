package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FileUtils;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.ProjectPathUtil;

public class CopyFileTool implements McpToolInterface {

    private final McpHookServer server;
    long confirmTimeoutMillis = TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis();

    public CopyFileTool(McpHookServer server) {
        this.server = server;
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SYSTEM;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return McpToolEnum.COPY_FILE.toolName() + " -> copies a file to a target directory using FileUtil.copyFile(); "
               + "optionally rename via " + McpToolPropertyEnum.NEW_NAME.key() + " (base name, no extension)";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.COPY_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Copy a file to a target directory. Optionally supply " + McpToolPropertyEnum.NEW_NAME.key()
                + " (base name without extension) to rename the copy. Refreshes VCS status after the operation.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject src = new JsonObject();
        src.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        src.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the source file.");
        props.add(CopyFileParamEnum.SOURCE_PATH.key(), src);
        JsonObject dir = new JsonObject();
        dir.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        dir.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the destination directory (must exist).");
        props.add(CopyFileParamEnum.TARGET_DIRECTORY.key(), dir);
        JsonObject name = new JsonObject();
        name.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        name.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Base name for the copy without extension. Omit to keep the original name.");
        props.add(CopyFileParamEnum.NEW_NAME.key(), name);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(CopyFileParamEnum.SOURCE_PATH.key());
        required.add(CopyFileParamEnum.TARGET_DIRECTORY.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean usesOwnFileLocking() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String sourcePath = args.require(CopyFileParamEnum.SOURCE_PATH.key());
        String targetDir = args.require(CopyFileParamEnum.TARGET_DIRECTORY.key());
        String sessionId = session.getId();
        // Source stays on the READ gate — copying a file out reads it, and denying that
        // here would withdraw an access GetFileContent still grants. The destination is a
        // write, so it uses the write gate and does not inherit the read exemption the
        // persistence base's index/template files carry.
        if (!McpHookServer.isFileAccessible(server, sessionId, sourcePath)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, sourcePath);
        }
        if (!McpHookServer.isFileWritable(server, sessionId, targetDir)) {
            return McpHookServer.fileAccessDeniedMessage(server, sessionId, targetDir);
        }
        String newName = args.str(CopyFileParamEnum.NEW_NAME.key());
        String targetPath = RefactoringProvider.copyTargetPath(sourcePath, targetDir, newName);
        // Flushed before the snapshot, not merely before the eventual copy: a LATER flush —
        // GetFileContent's own, for instance, racing in while the confirm prompt is open — would
        // otherwise bump the mtime between this capture and the under-lock recheck, making the
        // plugin's own read look like someone else's edit. Flushing first makes this capture
        // already the post-flush state, so a later flush of the same file is a no-op that
        // changes nothing.
        RefactoringProvider.FlushResult preFlush
                                        = RefactoringProvider.flushUnsavedEditorChanges(FileUtils.resolveByPath(sourcePath));
        if (preFlush.error() != null) {
            return preFlush.error();
        }
        // Captured before any branch below, so the under-lock recheck catches a change no matter
        // which return site reaches it — including the two that skip confirmation entirely.
        FileUtils.FileSnapshot approvedSource = FileUtils.FileSnapshot.capture(sourcePath);
        boolean targetExistedAtApproval = new java.io.File(targetPath).exists();
        if (!new java.io.File(sourcePath).exists()) {
            return McpToolInvoker.withFileMutation(sessionId, List.of(sourcePath, targetPath),
                    () -> copyFileAfterRecheck(sourcePath, targetDir, newName, targetPath,
                            approvedSource, targetExistedAtApproval));
        }
        AiProcessEventListener listener = session.getAiProcessEventListener();
        if (listener == null) {
            return McpToolInvoker.withFileMutation(sessionId, List.of(sourcePath, targetPath),
                    () -> copyFileAfterRecheck(sourcePath, targetDir, newName, targetPath,
                            approvedSource, targetExistedAtApproval));
        }
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        listener.onAiProcessEvent(new ConfirmEvent("Copy",
                "Copy " + ProjectPathUtil.shortPath(sourcePath) + " → "
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
            return "User declined the copy — do not retry without asking.";
        }
        return McpToolInvoker.withFileMutation(sessionId, List.of(sourcePath, targetPath),
                () -> copyFileAfterRecheck(sourcePath, targetDir, newName, targetPath,
                        approvedSource, targetExistedAtApproval));
    }

    private static String copyFileAfterRecheck(String sourcePath, String targetDir, String newName,
                                               String targetPath, FileUtils.FileSnapshot approvedSource, boolean targetExistedAtApproval) {
        if (!approvedSource.matches(sourcePath)) {
            return "Refused: " + sourcePath + " changed after approval (size or modified time differs); please retry.";
        }
        if (new java.io.File(targetPath).exists() != targetExistedAtApproval) {
            return "Refused: " + targetPath + " " + (targetExistedAtApproval ? "no longer exists" : "appeared")
                   + " after approval; please retry.";
        }
        return RefactoringProvider.copyFile(sourcePath, targetDir, newName);
    }
}
