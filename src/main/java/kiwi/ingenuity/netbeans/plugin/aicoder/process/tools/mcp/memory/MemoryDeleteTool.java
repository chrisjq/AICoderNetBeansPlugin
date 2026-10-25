package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class MemoryDeleteTool extends AbstractMemoryTool {

    public MemoryDeleteTool() {
        super(McpToolEnum.MEMORY_DELETE,
              "Delete a long-term memory note by name, for example one that turned out wrong.",
              McpToolEnum.MEMORY_DELETE.toolName() + " -> delete a long-term memory note that is wrong or no longer needed",
              new Param(McpToolPropertyEnum.NAME, "Name of the note to delete, as listed in the memory index."));
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    String run(SessionMemoryStore store, ToolRequestArguments args, String sessionId) {
        String name = args.str(MemoryDeleteParamEnum.NAME.key());
        if (!store.delete(name)) {
            return "Error: no memory note named '" + name + "'";
        }
        note(sessionId, "delete", name);
        return "Memory note '" + name + "' deleted.";
    }
}
