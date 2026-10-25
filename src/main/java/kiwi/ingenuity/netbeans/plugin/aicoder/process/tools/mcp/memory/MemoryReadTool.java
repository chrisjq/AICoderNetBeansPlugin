package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory;

import java.util.Optional;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class MemoryReadTool extends AbstractMemoryTool {

    public MemoryReadTool() {
        super(McpToolEnum.MEMORY_READ,
              "Read a long-term memory note, with its description and content, by name.",
              McpToolEnum.MEMORY_READ.toolName() + " -> read a saved long-term memory note in full by its name",
              new Param(McpToolPropertyEnum.NAME, "Name of the note, as listed in the memory index."));
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    String run(SessionMemoryStore store, ToolRequestArguments args, String sessionId) {
        String name = args.str(MemoryReadParamEnum.NAME.key());
        Optional<SessionMemoryStore.Note> note = store.read(name);
        if (note.isEmpty()) {
            return "Error: no memory note named '" + name + "'";
        }
        return "Description: " + note.get().description() + "\n\n" + note.get().content();
    }
}
