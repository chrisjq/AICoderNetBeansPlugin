package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;

/**
 * Declared cross-tool references in each tool's description or instruction text.
 */
public final class McpToolReferences {

    private static final Map<McpToolEnum, Set<McpToolEnum>> REFERENCES = references();

    private McpToolReferences() {
    }

    public static Set<McpToolEnum> of(McpToolEnum owner) {
        return REFERENCES.getOrDefault(owner, Set.of());
    }

    private static Map<McpToolEnum, Set<McpToolEnum>> references() {
        Map<McpToolEnum, Set<McpToolEnum>> references = new EnumMap<>(McpToolEnum.class);
        put(references, McpToolEnum.FIND_USAGES, McpToolEnum.SEARCH_TYPES);
        put(references, McpToolEnum.GET_DIAGNOSTICS, McpToolEnum.BUILD_MAVEN_PROJECT, McpToolEnum.RUN_MAVEN_TESTS);
        put(references, McpToolEnum.GET_CLASS_MEMBERS, McpToolEnum.SEARCH_TYPES);
        put(references, McpToolEnum.GET_TYPE_HIERARCHY, McpToolEnum.SEARCH_TYPES);
        put(references, McpToolEnum.GET_FILE_CONTENT, McpToolEnum.GET_PROJECT_STRUCTURE, McpToolEnum.SEARCH_IN_FILES, McpToolEnum.SEARCH_SYMBOLS);
        put(references, McpToolEnum.GET_FILE_INFO, McpToolEnum.GET_FILE_CONTENT);
        put(references, McpToolEnum.WEB_REQUEST, McpToolEnum.FILTER_FILE_CONTENT, McpToolEnum.GET_FILE_CONTENT);
        put(references, McpToolEnum.BUILD_MAVEN_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.RUN_MAVEN_TESTS, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.BUILD_GRADLE_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.RUN_GRADLE_TESTS, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.BUILD_ANT_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.RUN_ANT_TESTS, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.DOWNLOAD_MAVEN_SOURCES, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.DOWNLOAD_MAVEN_JAVADOC, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD, McpToolEnum.GET_JAVADOC);
        put(references, McpToolEnum.STOP_ASYNC_BUILD, McpToolEnum.LIST_BUILDS);
        put(references, McpToolEnum.CLEAN_PROJECT, McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT, McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT, McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.BUILD_PROJECT, McpToolEnum.BUILD_MAVEN_PROJECT, McpToolEnum.BUILD_GRADLE_PROJECT, McpToolEnum.BUILD_ANT_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.CLEAN_AND_BUILD_PROJECT, McpToolEnum.CLEAN_AND_BUILD_MAVEN_PROJECT, McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT, McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT, McpToolEnum.LIST_BUILDS, McpToolEnum.STOP_ASYNC_BUILD);
        put(references, McpToolEnum.LIST_TABLES, McpToolEnum.LIST_DATABASE_CONNECTIONS);
        put(references, McpToolEnum.GET_TABLE_SCHEMA, McpToolEnum.LIST_DATABASE_CONNECTIONS);
        put(references, McpToolEnum.GET_TABLE_DATA, McpToolEnum.LIST_DATABASE_CONNECTIONS);
        put(references, McpToolEnum.EXECUTE_SQL_QUERY, McpToolEnum.LIST_DATABASE_CONNECTIONS);
        put(references, McpToolEnum.MOVE_CLASS, McpToolEnum.APPLY_EDIT);
        put(references, McpToolEnum.FIX_IMPORTS, McpToolEnum.GET_CURRENT_FILE, McpToolEnum.ORGANISE_IMPORTS);
        put(references, McpToolEnum.ORGANISE_IMPORTS, McpToolEnum.GET_CURRENT_FILE, McpToolEnum.FIX_IMPORTS);
        put(references, McpToolEnum.RUN_INSPECT, McpToolEnum.GET_DIAGNOSTICS);
        put(references, McpToolEnum.ORGANISE_MEMBERS, McpToolEnum.GET_CURRENT_FILE);
        put(references, McpToolEnum.REFORMAT_FILE, McpToolEnum.GET_CURRENT_FILE);
        put(references, McpToolEnum.DELETE_FILE, McpToolEnum.GET_CURRENT_FILE);
        put(references, McpToolEnum.DELETE_DIRECTORY, McpToolEnum.GET_CURRENT_FILE);
        put(references, McpToolEnum.CLOSE_FILE, McpToolEnum.GET_CURRENT_FILE);
        put(references, McpToolEnum.SAVE_FILE, McpToolEnum.APPLY_EDIT, McpToolEnum.GET_CURRENT_FILE);
        put(references, McpToolEnum.APPLY_EDIT, McpToolEnum.GET_FILE_CONTENT);
        put(references, McpToolEnum.CREATE_DIRECTORY, McpToolEnum.WRITE_FILE, McpToolEnum.GET_CURRENT_FILE);
        put(references, McpToolEnum.GET_JAVADOC, McpToolEnum.DOWNLOAD_MAVEN_SOURCES, McpToolEnum.DOWNLOAD_MAVEN_JAVADOC);
        put(references, McpToolEnum.PEER_SESSION_LIST, McpToolEnum.PEER_MESSAGE_SEND);
        put(references, McpToolEnum.PEER_MESSAGE_SEND, McpToolEnum.PEER_SESSION_LIST, McpToolEnum.PEER_MESSAGE_LIST, McpToolEnum.PEER_MESSAGE_READ);
        put(references, McpToolEnum.PEER_MESSAGE_LIST, McpToolEnum.PEER_MESSAGE_READ);
        put(references, McpToolEnum.PEER_MESSAGE_READ, McpToolEnum.PEER_MESSAGE_LIST, McpToolEnum.PEER_MESSAGE_DELETE);
        put(references, McpToolEnum.PEER_MESSAGE_MARK_REPLIED, McpToolEnum.PEER_MESSAGE_LIST);
        put(references, McpToolEnum.PEER_SESSION_IS_ACTIVE, McpToolEnum.PEER_SESSION_LIST, McpToolEnum.PEER_MESSAGE_SEND);
        put(references, McpToolEnum.PEER_SESSION_DESCRIBE, McpToolEnum.PEER_SESSION_LIST);
        put(references, McpToolEnum.PEER_IDLE_WATCHER_CREATE, McpToolEnum.PEER_SESSION_LIST);
        put(references, McpToolEnum.PEER_IDLE_WATCHER_CANCEL, McpToolEnum.PEER_IDLE_WATCHER_CREATE, McpToolEnum.PEER_IDLE_WATCHER_LIST);
        return Collections.unmodifiableMap(references);
    }

    private static void put(Map<McpToolEnum, Set<McpToolEnum>> references,
                            McpToolEnum owner, McpToolEnum... tools) {
        references.put(owner, Collections.unmodifiableSet(EnumSet.of(tools[0], tools)));
    }
}
