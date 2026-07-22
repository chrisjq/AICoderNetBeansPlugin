package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;

public interface McpToolInterface {

    McpSectionEnum section();

    /**
     * Instruction line for MCP text, e.g. "SearchInFiles -> INSTEAD OF Bash
     * grep/rg - ...". Null to omit from instructions.
     */
    String instruction();

    JsonObject schema();

    /**
     * Model-facing schema. Domain surface comes from {@link #schema()}; credentials
     * are injected only when options contains CREDENTIALS.
     *
     * @param options non-null; use contains(...) to decide what to emit
     */
    default JsonObject schema(Set<McpInstructionOptionEnum> options) {
        return McpToolSchemas.applyCredentialsIfRequested(schema(), options);
    }

    default boolean isMutating() {
        return true;
    }

    /**
     * True if this tool acquires and releases its own fine-grained lock(s)
     * inside handle() (e.g. a per-file lock held from before showing a diff
     * through the user's decision and the write). When true, McpHookServer
     * skips wrapping handle() in its own global mutation lock — the tool is
     * fully responsible for guarding against concurrent mutation itself.
     */
    default boolean usesOwnFileLocking() {
        return false;
    }

    String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException;
}
