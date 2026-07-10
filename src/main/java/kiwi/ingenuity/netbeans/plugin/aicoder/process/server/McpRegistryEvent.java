package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

/**
 * An immutable lifecycle event for the {@link McpServerRegistry} supervisor
 * queue. Only the supervisor thread interprets these; callers enqueue them and,
 * for {@link McpRegistryEventTypeEnum#REGISTER}, wait on {@link #result()}.
 *
 * @param type      the event kind
 * @param key       the registry key (sessionId) for REGISTER/DEREGISTER; null
 *                  for STOP_ALL
 * @param aiType    the AI type for REGISTER/DEREGISTER; null for STOP_ALL
 * @param registrar the registrar for REGISTER (the supervisor stores it and
 *                  reuses it for last-of-type teardown); null otherwise
 * @param result    completion handle for REGISTER — the supervisor completes it
 *                  with true on success, false on failure; null for other types
 */
record McpRegistryEvent(
        McpRegistryEventTypeEnum type,
        String key,
        AiTypeEnum aiType,
        AiMcpRegistrar registrar,
        CompletableFuture<Boolean> result) {

    static McpRegistryEvent register(AiMcpRegistrar registrar, CompletableFuture<Boolean> result) {
        return new McpRegistryEvent(McpRegistryEventTypeEnum.REGISTER,
                registrar.getSessionId(), registrar.getAiType(), registrar, result);
    }

    static McpRegistryEvent deregister(String key, AiTypeEnum aiType) {
        return new McpRegistryEvent(McpRegistryEventTypeEnum.DEREGISTER, key, aiType, null, null);
    }

    static McpRegistryEvent stopAll() {
        return new McpRegistryEvent(McpRegistryEventTypeEnum.STOP_ALL, null, null, null, null);
    }
}
