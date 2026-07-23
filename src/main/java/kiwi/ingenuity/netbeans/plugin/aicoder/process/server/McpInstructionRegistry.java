package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * Static registry for the layered MCP instruction architecture.
 * <p>
 * Layer 1 (global static):
 * {@link McpHookServerUtil#getGlobalInstructionsHeader(Set)}
 * <p>
 * Layer 2 (AI-type static): registered per {@link AiTypeEnum} via
 * {@link #register}. Includes per-tool instruction additions.
 * <p>
 * Both layers honour the caller's {@link McpInstructionOptionEnum} set: Layer-2
 * header text is emitted only when HEADER is present, and Layer-2 per-tool
 * additions only when TOOL_INSTRUCTION is present. Without those checks a
 * registered addition could reintroduce text the options were meant to suppress.
 */
public final class McpInstructionRegistry {

    private static final Map<AiTypeEnum, String> aiTypeHeaders = new ConcurrentHashMap<>();
    private static final Map<AiTypeEnum, Map<McpToolEnum, String>> aiTypeToolInstructions = new ConcurrentHashMap<>();
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
        Set<McpInstructionOptionEnum> options = type.getMcpOptions();
        StringBuilder sb = new StringBuilder(McpHookServerUtil.getGlobalInstructionsHeader(options));
        if (!options.contains(McpInstructionOptionEnum.HEADER)) {
            return sb.toString();
        }
        String aiTypeHeader = aiTypeHeaders.get(type);
        if (aiTypeHeader != null && !aiTypeHeader.isBlank()) {
            sb.append("\n\n").append(aiTypeHeader.trim());
        }
        return sb.toString();
    }

    /**
     * Builds the per-tool instruction map for the given AI type, merging each
     * tool's global {@link McpToolInterface#instruction(Set)} (Layer 1) with any
     * registered AI-type addition (Layer 2). Both present: concatenated with ";
     * ".
     */
    public static Map<McpToolEnum, String> buildToolInstructions(
            AiTypeEnum type, Map<McpToolEnum, McpToolInterface> handlers) {
        Set<McpInstructionOptionEnum> options = type.getMcpOptions();
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return Map.of();
        }
        Map<McpToolEnum, String> aiTypeMap = aiTypeToolInstructions.getOrDefault(type, Map.of());
        LinkedHashMap<McpToolEnum, String> result = new LinkedHashMap<>();
        for (McpToolEnum e : McpToolEnum.values()) {
            McpToolInterface handler = handlers.get(e);
            if (handler == null) {
                continue;
            }
            String global = handler.instruction(options);
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
     * Builds the complete instructions string for the given AI type by
     * combining Layer 1 (global), Layer 2 (AI-type), and Layer 3
     * (tool-specific) instructions.
     */
    public static String buildFullInstructions(AiTypeEnum type, Map<McpToolEnum, McpToolInterface> handlers) {
        String header = buildHeader(type);
        Map<McpToolEnum, String> overrides = buildToolInstructions(type, handlers);
        return McpHookServerUtil.buildInstructions(type, header, handlers, overrides);
    }

    /**
     * Register the tool handlers for an AI type. All sessions of the same type
     * have identical handlers, so put is idempotent.
     */
    public static void registerHandlers(AiTypeEnum type, Map<McpToolEnum, McpToolInterface> handlers) {
        if (type != null && handlers != null && !handlers.isEmpty()) {
            handlerRegistry.put(type, Map.copyOf(handlers));
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
