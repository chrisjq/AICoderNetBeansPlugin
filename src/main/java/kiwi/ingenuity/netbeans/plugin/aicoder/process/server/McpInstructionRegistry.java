package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * Static registry for the layered MCP instruction architecture.
 * <p>
 * Layer 1 (global static):
 * {@link McpHookServerUtil#getGlobalInstructionsHeader()}
 * <p>
 * Layer 2 (AI-type static): registered per {@link AiTypeEnum} via
 * {@link #register}. Includes per-tool instruction additions.
 * <p>
 * Full instructions for each AI type are cached after first build to avoid
 * redundant concatenation on every initialize request. The cache is invalidated
 * whenever {@link #registerHandlers} is called.
 */
public final class McpInstructionRegistry {

    private static final Map<AiTypeEnum, String> aiTypeHeaders = new ConcurrentHashMap<>();
    private static final Map<AiTypeEnum, Map<McpToolEnum, String>> aiTypeToolInstructions = new ConcurrentHashMap<>();
    private static final Map<AiTypeEnum, String> instructionCache = new ConcurrentHashMap<>();
    private static final Map<AiTypeEnum, Map<McpToolEnum, McpToolInterface>> handlerRegistry = new ConcurrentHashMap<>();

    /**
     * Register Layer-2 header and per-tool instruction additions for an AI
     * type. Later calls overwrite earlier ones for the same type.
     */
    public static void register(AiTypeEnum type, String header, Map<McpToolEnum, String> toolInstructions) {
        if (header != null && !header.isBlank()) {
            aiTypeHeaders.put(type, header);
        }
        if (toolInstructions != null && !toolInstructions.isEmpty()) {
            aiTypeToolInstructions.put(type, Map.copyOf(toolInstructions));
        }
    }

    /**
     * Returns the combined Layer 1 + Layer 2 header for the given AI type. If
     * no Layer-2 header is registered the global header is returned unchanged.
     */
    public static String buildHeader(AiTypeEnum type) {
        StringBuilder sb = new StringBuilder(McpHookServerUtil.getGlobalInstructionsHeader());
        String aiTypeHeader = aiTypeHeaders.get(type);
        if (aiTypeHeader != null && !aiTypeHeader.isBlank()) {
            sb.append("\n\n").append(aiTypeHeader.trim());
        }
        return sb.toString();
    }

    /**
     * Builds the per-tool instruction map for the given AI type, merging each
     * tool's global {@link McpToolInterface#instruction()} (Layer 1) with any
     * registered AI-type addition (Layer 2). Both present: concatenated with ";
     * ".
     */
    public static Map<McpToolEnum, String> buildToolInstructions(
            AiTypeEnum type, Map<McpToolEnum, McpToolInterface> handlers) {
        Map<McpToolEnum, String> aiTypeMap = aiTypeToolInstructions.getOrDefault(type, Map.of());
        LinkedHashMap<McpToolEnum, String> result = new LinkedHashMap<>();
        for (McpToolEnum e : McpToolEnum.values()) {
            McpToolInterface handler = handlers.get(e);
            if (handler == null) {
                continue;
            }
            String global = handler.instruction();
            String aiTypeAddition = aiTypeMap.get(e);
            String combined;
            if (global != null && aiTypeAddition != null) {
                combined = global + "; " + aiTypeAddition;
            }
            else if (global != null) {
                combined = global;
            }
            else if (aiTypeAddition != null) {
                combined = aiTypeAddition;
            }
            else {
                continue;
            }
            result.put(e, combined);
        }
        return result;
    }

    /**
     * Returns cached instructions for the given AI type, or null if not cached.
     */
    public static String getCachedInstructions(AiTypeEnum type) {
        return instructionCache.get(type);
    }

    /**
     * Stores instructions in the cache for the given AI type.
     */
    public static void cacheInstructions(AiTypeEnum type, String instructions) {
        if (type != null && instructions != null) {
            instructionCache.put(type, instructions);
        }
    }

    /**
     * Builds the complete instructions string for the given AI type by
     * combining Layer 1 (global), Layer 2 (AI-type), and Layer 3
     * (tool-specific) instructions. The result is cached for subsequent calls.
     */
    public static String buildFullInstructions(AiTypeEnum type, Map<McpToolEnum, McpToolInterface> handlers) {
        String header = buildHeader(type);
        Map<McpToolEnum, String> overrides = buildToolInstructions(type, handlers);
        return McpHookServerUtil.buildInstructions(header, handlers, overrides);
    }

    /**
     * Register the tool handlers for an AI type. All sessions of the same type
     * have identical handlers, so put is idempotent. Invalidates the
     * instruction cache so the next initialize call rebuilds with real
     * handlers.
     */
    public static void registerHandlers(AiTypeEnum type, Map<McpToolEnum, McpToolInterface> handlers) {
        if (type != null && handlers != null && !handlers.isEmpty()) {
            handlerRegistry.put(type, Map.copyOf(handlers));
            instructionCache.remove(type);  // Invalidate stale cache
        }
    }

    /**
     * Returns the cached handler map for an AI type, or an empty map if not yet
     * registered.
     */
    public static Map<McpToolEnum, McpToolInterface> getHandlers(AiTypeEnum type) {
        if (type == null) {
            return Map.of();
        }
        return handlerRegistry.getOrDefault(type, Map.of());
    }

    private McpInstructionRegistry() {
    }
}
