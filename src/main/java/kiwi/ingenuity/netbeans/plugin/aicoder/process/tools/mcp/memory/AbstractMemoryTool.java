package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryFolders;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryNotifier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

/**
 * Shared behaviour of the session memory tools: they work on the calling session's own notes, never on a path from the
 * arguments, and only for an OpenAI Compatible session.
 */
abstract class AbstractMemoryTool extends AbstractActionTool {

    /**
     * One string parameter of a memory tool.
     */
    record Param(McpToolPropertyEnum property, String description) {

    }

    private final McpToolEnum tool;
    private final String description;
    private final Param[] params;

    AbstractMemoryTool(McpToolEnum tool, String description, String instruction, Param... params) {
        super(McpSectionEnum.MEMORY, tool.toolName(), description, instruction);
        this.tool = tool;
        this.description = description;
        this.params = params;
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Param param : params) {
            JsonObject property = new JsonObject();
            property.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            property.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), param.description());
            properties.add(param.property().key(), property);
            required.add(param.property().key());
        }
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), properties);
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        JsonObject toolSchema = new JsonObject();
        toolSchema.addProperty(ToolSchemaKeyEnum.NAME.key(), tool.toolName());
        toolSchema.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        toolSchema.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(toolSchema, options);
    }

    @Override
    public boolean requiresGlobalMutationLock() {
        // The store serialises its own file changes, and a note is not a project file.
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        if (session == null || session.getType() != AiTypeEnum.OPENAI_COMPATIBLE) {
            return "Error: the memory tools are available in OpenAI Compatible sessions only";
        }
        for (Param param : params) {
            String typeError = args.requireStringIfPresent(param.property().key());
            if (typeError != null) {
                return "Error: " + typeError;
            }
            if (args.str(param.property().key()) == null) {
                return "Error: " + param.property().key() + " is required";
            }
        }
        try {
            SessionMemoryStore store = SessionMemoryFolders.storeFor(session.getId());
            return run(store, args, session.getId());
        }
        catch (IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
        catch (IOException | UncheckedIOException ex) {
            return "Error: " + ex.getMessage();
        }
    }

    /**
     * Records a save or delete where the session's backend can see it (its debug log).
     */
    static void note(String sessionId, String action, String name) {
        SessionMemoryNotifier.notify(sessionId, action, name);
    }

    abstract String run(SessionMemoryStore store, ToolRequestArguments args, String sessionId);
}
