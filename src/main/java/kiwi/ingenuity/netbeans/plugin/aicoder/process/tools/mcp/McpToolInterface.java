package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

public interface McpToolInterface {

    McpSectionEnum section();

    /**
     * Instruction line for MCP text, e.g. "SearchInFiles -> INSTEAD OF Bash grep/rg - ...". Null to omit from
     * instructions.
     */
    String instruction(Set<McpInstructionOptionEnum> options);

    /**
     * Model-facing schema. Build the full schema; inject credentials only when options contains CREDENTIALS.
     *
     * @param options non-null; use contains(...) to decide what to emit
     */
    JsonObject schema(Set<McpInstructionOptionEnum> options);

    default boolean isMutating() {
        return true;
    }

    /**
     * True if this tool acquires and releases its own fine-grained lock(s) inside handle() (e.g. a per-file lock held
     * from before showing a diff through the user's decision and the write). When true, McpHookServer skips wrapping
     * handle() in its own global mutation lock — the tool is fully responsible for guarding against concurrent mutation
     * itself.
     */
    default boolean usesOwnFileLocking() {
        return false;
    }

    /**
     * Whether this handler requires {@code McpToolInvoker}'s process-wide mutation lock. This controls only that lock;
     * {@link #isMutating()} retains its independent read-versus-write meaning, including for Git access control.
     */
    default boolean requiresGlobalMutationLock() {
        return isMutating() && !usesOwnFileLocking();
    }

    String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException;
}
