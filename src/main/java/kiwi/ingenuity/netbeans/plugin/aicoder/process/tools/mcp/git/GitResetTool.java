package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ProjectPathParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitResetTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return McpToolEnum.GIT_RESET.toolName() + " - unstage files or reset HEAD to a revision (SOFT/MIXED/HARD). "
                    + "Optional hunks unstage part of a file; hunk numbers come from GetGitDiff with staged=true and line numbers are index lines. "
                    + "Requires " + ProjectPathParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root.";
        }
        return McpToolEnum.GIT_RESET.toolName() + " -> INSTEAD OF Bash git reset - unstage files or reset HEAD to a revision (SOFT/MIXED/HARD). "
                + "Optional hunks unstage part of a file; hunk numbers come from GetGitDiff with staged=true and line numbers are index lines. "
                + "Requires " + ProjectPathParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_RESET.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                         "Resets files or HEAD. With files: unstages those files. "
                         + "Without files: resets HEAD using type (SOFT/MIXED/HARD). "
                         + "Equivalent to: git reset [--soft|--mixed|--hard] [revision] [files]");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject files = new JsonObject();
        files.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        files.add(ToolSchemaKeyEnum.ITEMS.key(), items);
        files.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Specific files to unstage. Omit to do a full reset.");
        props.add(GitResetParamEnum.FILES.key(), files);
        JsonObject revision = new JsonObject();
        revision.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        revision.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Revision to reset to. Default: HEAD.");
        props.add(GitResetParamEnum.REVISION.key(), revision);
        JsonObject type = new JsonObject();
        type.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        type.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Reset type: SOFT, MIXED (default), or HARD.");
        props.add(GitResetParamEnum.TYPE.key(), type);
        JsonObject hunks = new JsonObject();
        hunks.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject hunksItems = new JsonObject();
        hunksItems.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject hunksProps = new JsonObject();
        JsonObject entryFile = new JsonObject();
        entryFile.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        entryFile.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                              "Path of the file to unstage part of.");
        hunksProps.add(McpToolPropertyEnum.FILE.key(), entryFile);
        JsonObject entryHunk = new JsonObject();
        entryHunk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        entryHunk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                              "1-based hunk number within that file's staged diff (GetGitDiff staged=true) (optional).");
        hunksProps.add(McpToolPropertyEnum.HUNK.key(), entryHunk);
        JsonObject entryFrom = new JsonObject();
        entryFrom.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        entryFrom.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                              "Index line of the range, inclusive (optional; give together with toLine).");
        hunksProps.add(McpToolPropertyEnum.FROM_LINE.key(), entryFrom);
        JsonObject entryTo = new JsonObject();
        entryTo.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        entryTo.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                            "Index line of the range, inclusive (optional; give together with fromLine).");
        hunksProps.add(McpToolPropertyEnum.TO_LINE.key(), entryTo);
        hunksItems.add(ToolSchemaKeyEnum.PROPERTIES.key(), hunksProps);
        JsonArray entryRequired = new JsonArray();
        entryRequired.add(McpToolPropertyEnum.FILE.key());
        hunksItems.add(ToolSchemaKeyEnum.REQUIRED.key(), entryRequired);
        hunks.add(ToolSchemaKeyEnum.ITEMS.key(), hunksItems);
        hunks.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                          "Optional partial unstaging. Entry: {file} = whole file; {file, hunk} = one hunk; "
                          + "{file, fromLine, toLine} = changes in that index line range; "
                          + "add hunk to limit the range to that hunk. "
                          + "Hunk numbers come from GetGitDiff with staged=true.");
        props.add(GitResetParamEnum.HUNKS.key(), hunks);
        JsonObject projectPath = new JsonObject();
        projectPath.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        projectPath.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                                "Target git repository or project root; relative paths resolve against the default project.");
        props.add(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(ProjectPathParamEnum.PROJECT_PATH.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String projectPath = args.require(ProjectPathParamEnum.PROJECT_PATH.key());
        JsonArray hunks = args.array(GitResetParamEnum.HUNKS.key());
        String revision = args.str(GitResetParamEnum.REVISION.key());
        String type = args.str(GitResetParamEnum.TYPE.key());
        if (hunks != null) {
            if ((revision != null && !revision.isBlank()) || (type != null && !type.isBlank())) {
                throw new McpArgumentException(-32602, "hunks only applies to unstaging files");
            }
            GitHunkSelectionsParser.ParsedSelections parsed = GitHunkSelectionsParser.parse(hunks);
            List<String> wholeFiles = new ArrayList<>(parsed.wholeFiles());
            JsonArray filesArgument = args.array(GitResetParamEnum.FILES.key());
            if (filesArgument != null) {
                for (int i = 0; i < filesArgument.size(); i++) {
                    if (filesArgument.get(i).isJsonPrimitive() && filesArgument.get(i).getAsJsonPrimitive().isString()) {
                        wholeFiles.add(filesArgument.get(i).getAsString());
                    }
                }
            }
            // A file unstaged whole wins over its partial selections: drop the latter.
            for (String f : wholeFiles) {
                parsed.selectionsByFile().remove(f);
            }
            // Unstaging everything ("." or a directory) unstages every file whole; drop all partial selections.
            if (wholeFiles.contains(".")) {
                parsed.selectionsByFile().clear();
            }
            if (wholeFiles.isEmpty() && parsed.selectionsByFile().isEmpty()) {
                throw new McpArgumentException(-32602, "files or hunks is required");
            }
            List<String> messages = new ArrayList<>();
            if (!wholeFiles.isEmpty()) {
                messages.add(GitProvider.gitReset(projectPath, wholeFiles, null, null));
            }
            for (var entry : parsed.selectionsByFile().entrySet()) {
                try {
                    messages.add(GitProvider.gitResetHunks(projectPath, entry.getKey(), entry.getValue()));
                }
                catch (IllegalArgumentException ex) {
                    String alreadyUnstaged = messages.isEmpty() ? "" : " (already unstaged before this error: "
                            + String.join("; ", messages) + ")";
                    throw new McpArgumentException(-32602, ex.getMessage() + alreadyUnstaged);
                }
            }
            return String.join("\n", messages);
        }
        JsonArray arr = args.array(GitResetParamEnum.FILES.key());
        List<String> files = null;
        if (arr != null) {
            files = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i).isJsonPrimitive() && arr.get(i).getAsJsonPrimitive().isString()) {
                    files.add(arr.get(i).getAsString());
                }
            }
        }
        return GitProvider.gitReset(projectPath, files, revision, type);
    }
}
