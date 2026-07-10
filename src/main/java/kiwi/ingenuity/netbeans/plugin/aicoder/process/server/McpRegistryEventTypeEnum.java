package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

/**
 * The kinds of lifecycle event the {@link McpServerRegistry} supervisor thread
 * consumes from its queue.
 */
enum McpRegistryEventTypeEnum {

    /**
     * A session wants the shared MCP server up; carries a completion future.
     */
    REGISTER,
    /**
     * A session is gone; the supervisor drops it and may stop the server.
     */
    DEREGISTER,
    /**
     * Force everything down and exit the supervisor thread (plugin unload).
     */
    STOP_ALL
}
