package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import java.util.Set;

/** Non-null immutable option-set factories. No helper predicates - callers use contains(). */
public final class McpInstructionOptions {
    private McpInstructionOptions() {
    }

    public static Set<McpInstructionOptionEnum> cli() {
        return Set.of(McpInstructionOptionEnum.HEADER,
                McpInstructionOptionEnum.TOOL_INSTRUCTION,
                McpInstructionOptionEnum.CREDENTIALS);
    }

    public static Set<McpInstructionOptionEnum> apiBackend() {
        return Set.of(McpInstructionOptionEnum.HEADER,
                McpInstructionOptionEnum.TOOL_INSTRUCTION);
    }
}
