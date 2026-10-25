package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * Individually switchable MCP capabilities, with fixed membership except for Git.
 */
public enum McpToolGroupEnum {
    ALWAYS_ON(McpToolCategoryEnum.ALWAYS_ON, "Always on", false, McpToolEnum.GET_INSTRUCTIONS),
    FILE_READING(McpToolCategoryEnum.FILES, "File reading", true, McpToolEnum.GET_FILE_CONTENT, McpToolEnum.GET_FILE_INFO, McpToolEnum.FILTER_FILE_CONTENT),
    FILE_EDITING(McpToolCategoryEnum.FILES, "File editing", true, McpToolEnum.APPLY_EDIT, McpToolEnum.WRITE_FILE, McpToolEnum.SAVE_FILE),
    FILE_MANAGEMENT(McpToolCategoryEnum.FILES, "File management", true, McpToolEnum.COPY_FILE, McpToolEnum.MOVE_FILE, McpToolEnum.DELETE_FILE, McpToolEnum.CREATE_DIRECTORY, McpToolEnum.DELETE_DIRECTORY),
    TEXT_SEARCH(McpToolCategoryEnum.FILES, "Text search", true, McpToolEnum.SEARCH_IN_FILES, McpToolEnum.FIND_FILE, McpToolEnum.GET_PROJECT_STRUCTURE),
    JAVA_CODE_INTELLIGENCE(McpToolCategoryEnum.CODE, "Java code intelligence", true, McpToolEnum.SEARCH_SYMBOLS, McpToolEnum.SEARCH_TYPES, McpToolEnum.FIND_DECLARATION, McpToolEnum.FIND_USAGES, McpToolEnum.FIND_IMPLEMENTATIONS, McpToolEnum.GET_TYPE_HIERARCHY, McpToolEnum.GET_CLASS_MEMBERS, McpToolEnum.GET_JAVADOC),
    SOURCE_TIDY_UP(McpToolCategoryEnum.CODE, "Source tidy-up", true, McpToolEnum.ORGANISE_IMPORTS, McpToolEnum.REFORMAT_FILE, McpToolEnum.FIX_IMPORTS, McpToolEnum.ORGANISE_MEMBERS),
    REFACTORING(McpToolCategoryEnum.CODE, "Refactoring", true, McpToolEnum.RENAME_SYMBOL, McpToolEnum.MOVE_CLASS, McpToolEnum.INLINE_VARIABLE, McpToolEnum.CHANGE_METHOD_SIGNATURE),
    DIAGNOSTICS(McpToolCategoryEnum.EDITOR, "Diagnostics", true, McpToolEnum.GET_DIAGNOSTICS, McpToolEnum.RUN_INSPECT),
    EDITOR_CONTEXT(McpToolCategoryEnum.EDITOR, "Editor context", true, McpToolEnum.GET_CURRENT_FILE, McpToolEnum.GET_CURRENT_FILE_CONTENT, McpToolEnum.GET_OPEN_FILES, McpToolEnum.GET_SELECTED_TEXT, McpToolEnum.NAVIGATE_TO_LINE, McpToolEnum.CLOSE_FILE, McpToolEnum.REFRESH_NB_FILE_STATUS),
    MAVEN(McpToolCategoryEnum.BUILD, "Maven", true, McpToolEnum.BUILD_MAVEN_PROJECT, McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT, McpToolEnum.RUN_MAVEN_TESTS, McpToolEnum.DOWNLOAD_MAVEN_JAVADOC, McpToolEnum.DOWNLOAD_MAVEN_SOURCES),
    GRADLE(McpToolCategoryEnum.BUILD, "Gradle", true, McpToolEnum.BUILD_GRADLE_PROJECT, McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT, McpToolEnum.RUN_GRADLE_TESTS),
    ANT(McpToolCategoryEnum.BUILD, "Ant", true, McpToolEnum.BUILD_ANT_PROJECT, McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT, McpToolEnum.RUN_ANT_TESTS),
    GENERIC_PROJECT(McpToolCategoryEnum.BUILD, "Project actions", true, McpToolEnum.BUILD_PROJECT, McpToolEnum.CLEAN_AND_BUILD_PROJECT, McpToolEnum.CLEAN_PROJECT, McpToolEnum.RUN_PROJECT),
    BUILD_QUEUE(McpToolCategoryEnum.BUILD, "Build queue", false, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD),
    GIT_READ(McpToolCategoryEnum.GIT, "Git read", true, McpToolEnum.GET_GIT_STATUS, McpToolEnum.GET_GIT_DIFF, McpToolEnum.GIT_LOG, McpToolEnum.GIT_SHOW, McpToolEnum.GIT_BLAME),
    GIT_WRITE(McpToolCategoryEnum.GIT, "Git write", true, McpToolEnum.GIT_ADD, McpToolEnum.GIT_COMMIT, McpToolEnum.GIT_BRANCH, McpToolEnum.GIT_CHECKOUT, McpToolEnum.GIT_CHERRY_PICK, McpToolEnum.GIT_DELETE_BRANCH, McpToolEnum.GIT_FETCH, McpToolEnum.GIT_MERGE, McpToolEnum.GIT_PULL, McpToolEnum.GIT_PUSH, McpToolEnum.GIT_REBASE, McpToolEnum.GIT_REMOTE, McpToolEnum.GIT_RESET, McpToolEnum.GIT_REVERT, McpToolEnum.GIT_STASH, McpToolEnum.GIT_TAG),
    DATABASE(McpToolCategoryEnum.EXTERNAL, "Database", true, McpToolEnum.LIST_DATABASE_CONNECTIONS, McpToolEnum.LIST_TABLES, McpToolEnum.GET_TABLE_SCHEMA, McpToolEnum.GET_TABLE_DATA, McpToolEnum.EXECUTE_SQL_QUERY),
    WEB(McpToolCategoryEnum.EXTERNAL, "Web", true, McpToolEnum.WEB_REQUEST),
    CLIPBOARD(McpToolCategoryEnum.EXTERNAL, "Clipboard", true, McpToolEnum.GET_CLIPBOARD),
    PEER_MESSAGING(McpToolCategoryEnum.COLLABORATION, "Peer messaging", true, McpToolEnum.PEER_MESSAGE_SEND, McpToolEnum.PEER_MESSAGE_LIST, McpToolEnum.PEER_MESSAGE_READ, McpToolEnum.PEER_MESSAGE_MARK_REPLIED, McpToolEnum.PEER_MESSAGE_DELETE, McpToolEnum.PEER_SESSION_LIST, McpToolEnum.PEER_SESSION_DESCRIBE, McpToolEnum.PEER_SESSION_IS_ACTIVE),
    IDLE_WATCHERS(McpToolCategoryEnum.COLLABORATION, "Idle watchers", true, McpToolEnum.PEER_IDLE_WATCHER_CREATE, McpToolEnum.PEER_IDLE_WATCHER_LIST, McpToolEnum.PEER_IDLE_WATCHER_CANCEL),
    ASK_USER(McpToolCategoryEnum.COLLABORATION, "Ask user", true, McpToolEnum.ASK_USER_QUESTION),
    PLUGIN_INFO(McpToolCategoryEnum.COLLABORATION, "Plugin information", true, McpToolEnum.GET_PLUGIN_VERSION),
    MEMORY(McpToolCategoryEnum.MEMORY, "Session memory", true, AiSessionSettings::supportsSessionMemory, McpToolEnum.MEMORY_SAVE, McpToolEnum.MEMORY_READ, McpToolEnum.MEMORY_DELETE);

    public static final Set<McpToolEnum> GIT_TOOLS = gitTools();
    public static final Set<McpToolGroupEnum> BUILD_SYSTEM_GROUPS = Set.of(MAVEN, GRADLE, ANT, GENERIC_PROJECT);

    private final McpToolCategoryEnum category;
    private final String label;
    private final boolean switchable;
    private final Predicate<AiSessionSettings> availableFor;
    private final Set<McpToolEnum> fixedMembers;

    McpToolGroupEnum(McpToolCategoryEnum category, String label, boolean switchable, McpToolEnum... members) {
        this(category, label, switchable, settings -> true, members);
    }

    McpToolGroupEnum(McpToolCategoryEnum category, String label, boolean switchable,
                     Predicate<AiSessionSettings> availableFor, McpToolEnum... members) {
        this.category = category;
        this.label = label;
        this.switchable = switchable;
        this.availableFor = availableFor;
        this.fixedMembers = members.length == 0 ? Set.of() : Collections.unmodifiableSet(EnumSet.of(members[0], members));
    }

    /**
     * Whether the group can be offered to a session at all. Most groups are offered to every backend; a group that
     * belongs to one backend (the session memory tools) is never offered to the others, whatever the switches say.
     */
    public boolean availableFor(AiSessionSettings settings) {
        return availableFor.test(settings);
    }

    public McpToolCategoryEnum category() {
        return category;
    }

    public String label() {
        return label;
    }

    public boolean switchable() {
        return switchable;
    }

    public Set<McpToolEnum> fixedMembers() {
        return fixedMembers;
    }

    public static McpToolGroupEnum groupOf(McpToolEnum tool, McpToolInterface handler) {
        if (handler != null && handler.section() == McpSectionEnum.GIT) {
            return handler.isMutating() ? GIT_WRITE : GIT_READ;
        }
        for (McpToolGroupEnum group : values()) {
            if (group.fixedMembers.contains(tool)) {
                return group;
            }
        }
        throw new IllegalStateException("No MCP tool exposure group for " + tool);
    }

    private static Set<McpToolEnum> gitTools() {
        EnumSet<McpToolEnum> tools = EnumSet.noneOf(McpToolEnum.class);
        tools.addAll(GIT_READ.fixedMembers);
        tools.addAll(GIT_WRITE.fixedMembers);
        return Collections.unmodifiableSet(tools);
    }
}
