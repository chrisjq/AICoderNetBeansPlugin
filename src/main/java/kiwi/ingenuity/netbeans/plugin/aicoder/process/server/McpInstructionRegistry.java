package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.HiddenToolTextFilter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * Static registry for the layered MCP instruction architecture.
 * <p>
 * Layer 1 (global static): {@link McpHookServerUtil#getGlobalInstructionsHeader(Set)}
 * <p>
 * Layer 2 (AI-type static): registered per {@link AiTypeEnum} via {@link #register}. Includes per-tool instruction
 * additions.
 * <p>
 * Both layers honour the caller's {@link McpInstructionOptionEnum} set: Layer-2 header text is emitted only when HEADER
 * is present, and Layer-2 per-tool additions only when TOOL_INSTRUCTION is present. Without those checks a registered
 * addition could reintroduce text the options were meant to suppress.
 */
public final class McpInstructionRegistry {

    private static final Map<AiTypeEnum, String> aiTypeHeaders = new ConcurrentHashMap<>();
    private static final Map<AiTypeEnum, Map<McpToolEnum, String>> aiTypeToolInstructions = new ConcurrentHashMap<>();
    private static final Map<AiTypeEnum, Map<McpToolEnum, McpToolInterface>> handlerRegistry = new ConcurrentHashMap<>();

    /**
     * Register Layer-2 header and per-tool instruction additions for an AI type. Later calls overwrite earlier ones for
     * the same type.
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
     * Returns the combined Layer 1 + Layer 2 header for the given AI type. If no Layer-2 header is registered the
     * global header is returned unchanged.
     */
    public static String buildHeader(AiTypeEnum type) {
        return buildHeader(type, Set.of());
    }

    /**
     * The header for a session that cannot see {@code hiddenTools}: the global Layer-1 sentences that list tools name
     * only the visible ones (see {@link McpHookServerUtil#getGlobalInstructionsHeader(Set, Set)}). The Layer-2 text is
     * free-form and left to the sentence filter.
     */
    public static String buildHeader(AiTypeEnum type, Set<McpToolEnum> hiddenTools) {
        Set<McpInstructionOptionEnum> options = type.getMcpOptions();
        StringBuilder sb = new StringBuilder(McpHookServerUtil.getGlobalInstructionsHeader(options, hiddenTools));
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
     * Builds the per-tool instruction map for the given AI type, merging each tool's global
     * {@link McpToolInterface#instruction(Set)} (Layer 1) with any registered AI-type addition (Layer 2). Both present:
     * concatenated with "; ".
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
     * The instructions one session should see, combining Layer 1 (global), Layer 2 (AI-type) and Layer 3
     * (tool-specific) text: built only from the tools its settings leave enabled, with every sentence that names a tool
     * it cannot see removed — so neither a hidden tool's line nor a header sentence pointing at it reaches the model.
     * {@code settings} may be null (plugin defaults).
     *
     * <p>
     * {@code handlers} must be the session's FULL handler map, not one already filtered by
     * {@link McpToolAccess#effectiveTools}: the hidden tools are those in {@code handlers} that the settings leave off,
     * so a pre-filtered map hides nothing and every hidden tool's name survives in the text.
     */
    public static String buildFullInstructions(AiTypeEnum type, Map<McpToolEnum, McpToolInterface> handlers,
                                               AiSessionSettings settings) {
        Map<McpToolEnum, McpToolInterface> effective = McpToolAccess.effectiveTools(settings, handlers);
        Set<McpToolEnum> hidden = HiddenToolTextFilter.hiddenFrom(handlers, effective);
        String full = McpHookServerUtil.buildInstructions(type, buildHeader(type, hidden), effective,
                                                          buildToolInstructions(type, effective));
        return HiddenToolTextFilter.omitSentencesReferencing(full, hidden);
    }

    /**
     * Register the full tool handler set for an AI type; the registry holds one set per type, so put is idempotent.
     * This is not what any one session is shown: each session sees only the subset {@link McpToolAccess#effectiveTools}
     * leaves enabled for its own settings.
     */
    public static void registerHandlers(AiTypeEnum type, Map<McpToolEnum, McpToolInterface> handlers) {
        if (type != null && handlers != null && !handlers.isEmpty()) {
            handlerRegistry.put(type, Map.copyOf(handlers));
        }
    }

    /**
     * Returns the cached handler map for an AI type, or an empty map if not yet registered.
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
