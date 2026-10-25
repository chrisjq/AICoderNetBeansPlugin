package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitHunkPatch;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;

@RequiresLock(LockTypeEnum.GIT_LOCK)
public class GitAddTool implements McpToolInterface {

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
            return McpToolEnum.GIT_ADD.toolName() + " - stages files for commit; pass [\".\" ] to stage all. "
                    + "Requires " + ProjectPathParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root."
                    + " Optional hunks stages part of a file: {file, hunk} / {file, fromLine, toLine} / {file, hunk, fromLine, toLine}; hunk numbers come from GetGitDiff.";
        }
        return McpToolEnum.GIT_ADD.toolName() + " -> INSTEAD OF Bash git add - stages files for commit; pass [\".\" ] to stage all. "
                + "Requires " + ProjectPathParamEnum.PROJECT_PATH.key() + " to select the target git repository or project root."
                + " Optional hunks stages part of a file: {file, hunk} / {file, fromLine, toLine} / {file, hunk, fromLine, toLine}; hunk numbers come from GetGitDiff.";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_ADD.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                         "Stages files for commit. Pass [\".\" ] to stage all.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject files = new JsonObject();
        files.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject fileItems = new JsonObject();
        fileItems.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        files.add(ToolSchemaKeyEnum.ITEMS.key(), fileItems);
        files.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                          "Paths to stage whole. Use [\".\" ] for all changes. Optional when hunks is given.");
        props.add(GitAddParamEnum.FILES.key(), files);
        JsonObject hunks = new JsonObject();
        hunks.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject hunksItems = new JsonObject();
        hunksItems.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject hunksProps = new JsonObject();
        JsonObject entryFile = new JsonObject();
        entryFile.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        entryFile.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                              "Path of the file to stage part of.");
        hunksProps.add(McpToolPropertyEnum.FILE.key(), entryFile);
        JsonObject entryHunk = new JsonObject();
        entryHunk.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        entryHunk.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                              "1-based hunk number within that file's GetGitDiff output (optional).");
        hunksProps.add(McpToolPropertyEnum.HUNK.key(), entryHunk);
        JsonObject entryFrom = new JsonObject();
        entryFrom.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        entryFrom.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                              "First working-tree line of the range, inclusive (optional; give together with toLine).");
        hunksProps.add(McpToolPropertyEnum.FROM_LINE.key(), entryFrom);
        JsonObject entryTo = new JsonObject();
        entryTo.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        entryTo.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                            "Last working-tree line of the range, inclusive (optional; give together with fromLine).");
        hunksProps.add(McpToolPropertyEnum.TO_LINE.key(), entryTo);
        hunksItems.add(ToolSchemaKeyEnum.PROPERTIES.key(), hunksProps);
        JsonArray entryRequired = new JsonArray();
        entryRequired.add(McpToolPropertyEnum.FILE.key());
        hunksItems.add(ToolSchemaKeyEnum.REQUIRED.key(), entryRequired);
        hunks.add(ToolSchemaKeyEnum.ITEMS.key(), hunksItems);
        hunks.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                          "Optional partial staging. Entry: {file} = whole file; {file, hunk} = one hunk; "
                          + "{file, fromLine, toLine} = changes in that working-tree line range; "
                          + "add hunk to limit the range to that hunk. Hunk numbers come from GetGitDiff (unstaged).");
        props.add(GitAddParamEnum.HUNKS.key(), hunks);
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

        // Whole-file staging.
        JsonArray filesArr = args.array(GitAddParamEnum.FILES.key());
        List<String> wholeFiles = new ArrayList<>();
        if (filesArr != null) {
            for (JsonElement el : filesArr) {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    wholeFiles.add(el.getAsString());
                }
            }
        }

        // Partial staging.
        GitHunkSelectionsParser.ParsedSelections parsedSelections
                = GitHunkSelectionsParser.parse(args.array(GitAddParamEnum.HUNKS.key()));
        wholeFiles.addAll(parsedSelections.wholeFiles());
        Map<String, List<GitHunkPatch.Selection>> selectionsByFile = parsedSelections.selectionsByFile();

        if (wholeFiles.isEmpty() && selectionsByFile.isEmpty()) {
            throw new McpArgumentException(-32602, "files or hunks is required");
        }

        // A file staged whole wins over its partial selections: drop the latter.
        for (String f : wholeFiles) {
            selectionsByFile.remove(f);
        }
        // Staging everything ("." or a directory) stages every file whole; a later gitAddHunks would find no
        // unstaged diff and fail, so drop all partial selections.
        if (wholeFiles.contains(".")) {
            selectionsByFile.clear();
        }

        List<String> messages = new ArrayList<>();
        if (!wholeFiles.isEmpty()) {
            messages.add(GitProvider.gitAdd(projectPath, wholeFiles));
        }
        for (Map.Entry<String, List<GitHunkPatch.Selection>> e : selectionsByFile.entrySet()) {
            try {
                messages.add(GitProvider.gitAddHunks(projectPath, e.getKey(), e.getValue()));
            }
            catch (IllegalArgumentException ex) {
                // If an earlier call in this batch already staged something, say so — the user now has a
                // partially-staged state that the bare error would hide.
                String alreadyStaged = messages.isEmpty() ? "" : " (already staged before this error: "
                        + String.join("; ", messages) + ")";
                throw new McpArgumentException(-32602, ex.getMessage() + alreadyStaged);
            }
        }
        return String.join("\n", messages);
    }

}
