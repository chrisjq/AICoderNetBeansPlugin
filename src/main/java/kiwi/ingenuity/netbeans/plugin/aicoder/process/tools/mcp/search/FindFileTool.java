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
                ? McpToolEnum.FIND_FILE.toolName() + " -> INSTEAD OF Bash find - locate files by name under a permitted directory"
                : null;
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.FIND_FILE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Finds files by leaf name below directoryPath. Omit directoryPath to search every accessible open-project directory. Omit pattern to list every file. Literal text by default; set isRegex for a pattern. Results are capped but the header reports the true total.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        addStringProperty(props, FindFileParamEnum.DIRECTORY_PATH, "Absolute project or parent directory to search recursively. Omit to search accessible open projects.");
        addStringProperty(props, FindFileParamEnum.PATTERN, "Optional literal text or regex pattern to match each file name; omit to list every file.");
        addBooleanProperty(props, FindFileParamEnum.IS_REGEX, "Treat pattern as regex. Default: false (literal text).");
        addBooleanProperty(props, FindFileParamEnum.CASE_SENSITIVE, "Case-sensitive match. Default: false.");
        JsonObject maxMatches = new JsonObject();
        maxMatches.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        maxMatches.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Maximum files to show. Default: 200; maximum: 5000. The header reports the true total when capped.");
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
        return FindFileProvider.findFiles(directories, pattern,
                args.bool(FindFileParamEnum.IS_REGEX.key()),
                args.bool(FindFileParamEnum.CASE_SENSITIVE.key()),
                args.intOr(FindFileParamEnum.MAX_MATCHES.key(), 0, 0, FindFileProvider.MAX_MATCHES),
                path -> server.isFileAccessible(sessionId, path.toString()));
    }

}
