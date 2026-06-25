package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import java.util.Arrays;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;

public enum McpToolEnum {

    /**
     * IMPORTANT: When adding a new MCP tool to McpHookServer, add its enum
     * constant here at the same time — allMcpNames() auto-generates the
     * --allowedTools entry from this list. Constants are grouped by
     * McpSectionEnum to make coverage easy to audit.
     *
     * Tool names are qualified with the shared MCP server name.
     */
    // DEVOPS_BUILD
    BUILD_MAVEN_PROJECT("BuildMavenProject"),
    CLEAN_AND_BUILD_MAVEN_PROJECT("CleanAndBuildMavenProject"),
    BUILD_GRADLE_PROJECT("BuildGradleProject"),
    BUILD_ANT_PROJECT("BuildAntProject"),
    DOWNLOAD_MAVEN_SOURCES("DownloadMavenSources"),
    DOWNLOAD_MAVEN_JAVADOC("DownloadMavenJavadoc"),
    // DEVOPS_TEST
    RUN_MAVEN_TESTS("RunMavenTests"),
    RUN_GRADLE_TESTS("RunGradleTests"),
    RUN_ANT_TESTS("RunAntTests"),
    // GIT
    GET_GIT_STATUS("GetGitStatus"),
    GET_GIT_DIFF("GetGitDiff"),
    GIT_ADD("GitAdd"),
    GIT_COMMIT("GitCommit"),
    GIT_LOG("GitLog"),
    GIT_PUSH("GitPush"),
    GIT_PULL("GitPull"),
    GIT_CHECKOUT("GitCheckout"),
    GIT_BRANCH("GitBranch"),
    GIT_DELETE_BRANCH("GitDeleteBranch"),
    GIT_STASH("GitStash"),
    GIT_FETCH("GitFetch"),
    GIT_RESET("GitReset"),
    GIT_MERGE("GitMerge"),
    GIT_SHOW("GitShow"),
    GIT_BLAME("GitBlame"),
    GIT_REBASE("GitRebase"),
    GIT_CHERRY_PICK("GitCherryPick"),
    GIT_TAG("GitTag"),
    GIT_REMOTE("GitRemote"),
    GIT_REVERT("GitRevert"),
    // HELP
    GET_PROJECT_STRUCTURE("GetProjectStructure"),
    GET_CLASS_MEMBERS("GetClassMembers"),
    GET_TYPE_HIERARCHY("GetTypeHierarchy"),
    GET_JAVADOC("GetJavadoc"),
    // REFACTORING
    RENAME_SYMBOL("RenameSymbol"),
    MOVE_CLASS("MoveClass"),
    INLINE_VARIABLE("InlineVariable"),
    CHANGE_METHOD_SIGNATURE("ChangeMethodSignature"),
    // SEARCH
    SEARCH_IN_FILES("SearchInFiles"),
    SEARCH_TYPES("SearchTypes"),
    SEARCH_SYMBOLS("SearchSymbols"),
    FIND_DECLARATION("FindDeclaration"),
    FIND_IMPLEMENTATIONS("FindImplementations"),
    FIND_USAGES("FindUsages"),
    // SYSTEM
    GET_FILE_CONTENT("GetFileContent"),
    GET_CLIPBOARD("GetClipboard"),
    SAVE_FILE("SaveFile"),
    APPLY_EDIT("ApplyEdit"),
    WRITE_FILE("WriteFile"),
    DELETE_FILE("DeleteFile"),
    COPY_FILE("CopyFile"),
    MOVE_FILE("MoveFile"),
    REFRESH_NB_FILE_STATUS("RefreshFileStatus"),
    // UI_BUILD
    BUILD_PROJECT("BuildProject"),
    CLEAN_PROJECT("CleanProject"),
    CLEAN_AND_BUILD_PROJECT("CleanAndBuildProject"),
    // UI_FILES
    GET_CURRENT_FILE("GetCurrentFile"),
    GET_CURRENT_FILE_CONTENT("GetCurrentFileContent"),
    GET_OPEN_FILES("GetOpenFiles"),
    GET_SELECTED_TEXT("GetSelectedText"),
    GET_DIAGNOSTICS("GetDiagnostics"),
    CLOSE_FILE("CloseFile"),
    // UI_NAVIGATION
    NAVIGATE_TO_LINE("NavigateToLine"),
    // UI_SOURCE
    FIX_IMPORTS("FixImports"),
    ORGANISE_IMPORTS("OrganiseImports"),
    ORGANISE_MEMBERS("OrganiseMembers"),
    REFORMAT_FILE("ReformatFile"),
    // UI_DIALOG
    RUN_INSPECT("RunInspect"),
    // USER_INPUT
    ASK_USER_QUESTION("AskUserQuestion"),
    // PLUGIN
    GET_PLUGIN_VERSION("GetPluginVersion"),
    GET_INSTRUCTIONS("GetInstructions"),
    // INTER_AI
    LIST_AI_SESSIONS("ListAiSessions"),
    SEND_AI_MESSAGE("SendAiMessage"),
    GET_AI_MESSAGES("GetAiMessages"),
    READ_AI_MESSAGE("ReadAiMessage"),
    DELETE_AI_MESSAGE("DeleteAiMessage"),
    IS_AI_SESSION_ACTIVE("IsAiSessionActive"),
    UPDATE_SESSION_DESCRIPTION("UpdateSessionDescription");

    /**
     * Comma-separated list of all plugin MCP tool names for --allowedTools,
     * qualified with the shared MCP server name.
     */
    public static String allMcpNames() {
        String prefix = "mcp__" + StringConst.PLUGIN_ID + "__";
        return Arrays.stream(values())
                .map(t -> prefix + t.toolName)
                .collect(Collectors.joining(","));
    }

    /**
     * Find by bare tool name. Returns null if not found.
     */
    public static McpToolEnum of(String name) {
        if (name == null) {
            return null;
        }
        for (McpToolEnum t : values()) {
            if (t.toolName.equals(name)) {
                return t;
            }
        }
        return null;
    }

    private final String toolName;

    /**
     * @param toolName the bare name as registered in the MCP server (e.g.
     * "FindUsages").
     */
    McpToolEnum(String toolName) {
        this.toolName = toolName;
    }

    /**
     * Bare tool name as registered in the MCP server (e.g. "FindUsages").
     */
    public String toolName() {
        return toolName;
    }

}
