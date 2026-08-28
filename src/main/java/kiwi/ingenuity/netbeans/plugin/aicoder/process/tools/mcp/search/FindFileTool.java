package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FindFileProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FindFileTypeEnum;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Finds files by name below a permitted directory or the accessible open projects.
 */
public class FindFileTool implements McpToolInterface {

    private static void addStringProperty(JsonObject props, FindFileParamEnum property, String description) {
        JsonObject value = new JsonObject();
        value.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        value.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        props.add(property.key(), value);
    }

    private static void addBooleanProperty(JsonObject props, FindFileParamEnum property, String description) {
        JsonObject value = new JsonObject();
        value.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        value.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        props.add(property.key(), value);
    }

    private final McpHookServer server;

    public FindFileTool(McpHookServer server) {
        this.server = server;
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SEARCH;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        return options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)
                ? McpToolEnum.FIND_FILE.toolName() + " -> INSTEAD OF Bash find - locate files or directories by name under a permitted directory"
                : null;
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.FIND_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Finds files OR directories by leaf name below "
                + FindFileParamEnum.DIRECTORY_PATH.key() + " — set " + FindFileParamEnum.TYPE.key()
                + " to choose which. Omit " + FindFileParamEnum.DIRECTORY_PATH.key()
                + " to search every accessible open-project directory. Omit " + FindFileParamEnum.PATTERN.key()
                + " to list everything. Literal text by default; set " + FindFileParamEnum.IS_REGEX.key()
                + " for a pattern. Results are capped but the header reports the true total. Descent is limited to "
                + FindFileProvider.MAX_DEPTH_CEILING + " directory levels even when "
                + FindFileParamEnum.MAX_DEPTH.key() + " is unset, so a search always terminates. If the filesystem "
                + "refuses an entry the walk continues and the header gains a note counting what was skipped — an "
                + "unreadable directory hides its whole subtree, so treat such a result as partial.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        addStringProperty(props, FindFileParamEnum.DIRECTORY_PATH, "Absolute project or parent directory to search recursively. Omit to search accessible open projects.");
        addStringProperty(props, FindFileParamEnum.PATTERN, "Optional literal text or regex pattern matched against each entry's leaf name; omit to list everything of the requested type.");
        addBooleanProperty(props, FindFileParamEnum.IS_REGEX, "Treat " + FindFileParamEnum.PATTERN.key()
                + " as regex. Default: false (literal text).");
        addBooleanProperty(props, FindFileParamEnum.CASE_SENSITIVE, "Case-sensitive match. Default: false.");
        addBooleanProperty(props, FindFileParamEnum.IGNORE_HIDDEN, "Skip hidden files and directories, and everything inside a hidden directory. A leading-dot name such as .git or .env is hidden on EVERY platform including Windows; on Windows the DOS hidden attribute additionally hides files whose names do not start with a dot. Default: true. Set false to include them. A " + FindFileParamEnum.DIRECTORY_PATH.key()
                + " that is itself hidden is always searched, since naming it is an explicit request.");
        addStringProperty(props, FindFileParamEnum.TYPE, "What to match: " + FindFileTypeEnum.typeList()
                + ". Use " + FindFileTypeEnum.DIR.type()
                + " to find directory names, including empty directories. The starting directory is never returned as a match.");
        JsonObject maxDepth = new JsonObject();
        maxDepth.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        maxDepth.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "How many directory levels below the starting directory to search: 0 is the starting directory only, 1 adds one level below it, 2 two levels, and so on. Omit or pass a negative value for the deepest search available. Any value is capped at " + FindFileProvider.MAX_DEPTH_CEILING + " levels, which is far beyond any real source tree and guarantees the walk terminates. This is the parameter that limits how much work the search does.");
        props.add(FindFileParamEnum.MAX_DEPTH.key(), maxDepth);
        JsonObject maxMatches = new JsonObject();
        maxMatches.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        maxMatches.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Maximum entries to show. Default: 200; maximum: "
                + "5000. This caps the LISTING only — the walk still visits everything within "
                + FindFileParamEnum.MAX_DEPTH.key() + ", and the header reports the true total when capped. Use "
                + FindFileParamEnum.MAX_DEPTH.key() + " to limit the work itself.");
        props.add(FindFileParamEnum.MAX_MATCHES.key(), maxMatches);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        String pattern = args.str(FindFileParamEnum.PATTERN.key());
        String directoryPath = args.str(FindFileParamEnum.DIRECTORY_PATH.key());
        String sessionId = session.getId();
        List<Path> directories = new ArrayList<>();
        if (directoryPath != null && !directoryPath.isBlank()) {
            if (sessionId == null || !server.isFileAccessible(sessionId, directoryPath)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, directoryPath);
            }
            directories.add(Path.of(directoryPath));
        }
        else {
            Set<Path> roots = new LinkedHashSet<>();
            for (Project project : OpenProjects.getDefault().getOpenProjects()) {
                FileObject directory = project.getProjectDirectory();
                File directoryFile = directory != null ? FileUtil.toFile(directory) : null;
                if (directoryFile != null && sessionId != null
                        && server.isFileAccessible(sessionId, directoryFile.getPath())) {
                    roots.add(directoryFile.toPath());
                }
            }
            directories.addAll(roots);
        }
        // bool() returns false for an absent key, so a flag that defaults to TRUE has to test has() first — reading it
        // with bool() alone would silently invert the documented default for every caller that omits it.
        boolean ignoreHidden = !args.has(FindFileParamEnum.IGNORE_HIDDEN.key())
                || args.bool(FindFileParamEnum.IGNORE_HIDDEN.key());
        // A misspelled type must be refused, not quietly defaulted: falling back to "file" would answer a directory
        // search with a confident empty result and no indication the argument was ignored.
        String rawType = args.str(FindFileParamEnum.TYPE.key());
        FindFileTypeEnum type = FindFileTypeEnum.from(rawType);
        if (type == null) {
            throw new McpArgumentException(-32602, FindFileParamEnum.TYPE.key() + " must be one of: "
                    + FindFileTypeEnum.typeList() + " — received: " + rawType);
        }
        return FindFileProvider.findFiles(directories, pattern,
                args.bool(FindFileParamEnum.IS_REGEX.key()),
                args.bool(FindFileParamEnum.CASE_SENSITIVE.key()),
                args.intOr(FindFileParamEnum.MAX_MATCHES.key(), 0, 0, FindFileProvider.MAX_MATCHES),
                ignoreHidden,
                // Negative means unlimited, so no lower clamp: intOr's range-checking overload would reject it.
                args.intOr(FindFileParamEnum.MAX_DEPTH.key(), -1),
                type,
                path -> server.isFileAccessible(sessionId, path.toString()));
    }

}
