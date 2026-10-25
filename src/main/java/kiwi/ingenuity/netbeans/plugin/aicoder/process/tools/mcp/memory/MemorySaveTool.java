package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class MemorySaveTool extends AbstractMemoryTool {

    public MemorySaveTool() {
        super(McpToolEnum.MEMORY_SAVE,
              "Save a long-term memory note for this conversation, replacing any note with the same name.",
              McpToolEnum.MEMORY_SAVE.toolName() + " -> save or replace one long-term memory note (one fact per note)",
              new Param(McpToolPropertyEnum.NAME, "Short kebab-case name, lowercase letters and digits joined by hyphens, "
                        + "at most " + SessionMemoryStore.MAX_NAME_LENGTH + " characters, e.g. prefers-tabs."),
              new Param(McpToolPropertyEnum.DESCRIPTION, "One line (at most " + SessionMemoryStore.MAX_DESCRIPTION_LENGTH
                        + " characters) saying what the note holds; it is shown in the memory index."),
              new Param(McpToolPropertyEnum.CONTENT, "The note text, at most " + SessionMemoryStore.MAX_CONTENT_LENGTH
                        + " characters."));
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    String run(SessionMemoryStore store, ToolRequestArguments args, String sessionId) {
        String name = args.str(MemorySaveParamEnum.NAME.key());
        SessionMemoryStore.SaveResult result = store.save(name, args.str(MemorySaveParamEnum.DESCRIPTION.key()),
                                                          args.str(MemorySaveParamEnum.CONTENT.key()));
        note(sessionId, "save", name);
        return "Memory note '" + name + "' " + (result == SessionMemoryStore.SaveResult.CREATED ? "created" : "replaced")
                + ".";
    }
}
