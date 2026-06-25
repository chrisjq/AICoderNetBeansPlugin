package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;

public interface McpToolInterface {

    McpSectionEnum section();

    /**
     * Instruction line for MCP text, e.g. "SearchInFiles -> INSTEAD OF Bash
     * grep/rg - ...". Null to omit from instructions.
     */
    String instruction();

    JsonObject schema();

    default boolean isMutating() {
        return true;
    }

    String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException;
}
