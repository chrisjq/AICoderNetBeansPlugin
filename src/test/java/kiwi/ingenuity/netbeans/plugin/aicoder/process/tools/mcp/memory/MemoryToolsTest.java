package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryFolders;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryNotifier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class MemoryToolsTest {

    @TempDir
    Path sessions;

    private final MemorySaveTool save = new MemorySaveTool();
    private final MemoryReadTool read = new MemoryReadTool();
    private final MemoryDeleteTool delete = new MemoryDeleteTool();

    @BeforeEach
    void pointSessionFolderAtTheTempDir() {
        SessionMemoryFolders.setResolverForTest(id -> sessions.resolve(id));
    }

    @AfterEach
    void restore() {
        SessionMemoryFolders.setResolverForTest(null);
    }

    private static AbstractAiSession session(String id, AiTypeEnum type) {
        AiSession aiSession = type == AiTypeEnum.OPENAI_COMPATIBLE
                              ? new AiSession(id, "OC", null, type, null, new OpenAiCompatibleSessionSettings(), Instant.now(),
                                              Instant.now())
                              : AiSession.create(null, type);
        return new AbstractAiSession(aiSession) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }

            @Override
            public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
                return Map.of();
            }
        };
    }

    private static ToolRequestArguments args(String... keyValues) {
        JsonObject json = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            json.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return new ToolRequestArguments(json);
    }

    private static ToolRequestArguments saveArgs(String name, String description, String content) {
        return args(MemorySaveParamEnum.NAME.key(), name, MemorySaveParamEnum.DESCRIPTION.key(), description,
                    MemorySaveParamEnum.CONTENT.key(), content);
    }

    @Test
    @Timeout(10)
    void saveReadAndDeleteWorkOnTheCallingSessionsOwnFolder() throws Exception {
        AbstractAiSession one = session("session-one", AiTypeEnum.OPENAI_COMPATIBLE);
        AbstractAiSession two = session("session-two", AiTypeEnum.OPENAI_COMPATIBLE);

        assertEquals("Memory note 'prefers-tabs' created.",
                     save.handle(saveArgs("prefers-tabs", "Tabs please", "Use tabs"), one));
        assertEquals("Memory note 'prefers-tabs' replaced.",
                     save.handle(saveArgs("prefers-tabs", "Tabs please", "Use tabs always"), one));

        assertTrue(Files.isRegularFile(sessions.resolve("session-one").resolve("memory").resolve("prefers-tabs.md")));
        assertFalse(Files.exists(sessions.resolve("session-two")), "the other session's folder is untouched");
        assertEquals("Description: Tabs please\n\nUse tabs always",
                     read.handle(args(MemoryReadParamEnum.NAME.key(), "prefers-tabs"), one));
        assertTrue(read.handle(args(MemoryReadParamEnum.NAME.key(), "prefers-tabs"), two).startsWith("Error: no memory note"),
                   "a session cannot read another session's notes");

        assertEquals("Memory note 'prefers-tabs' deleted.",
                     delete.handle(args(MemoryDeleteParamEnum.NAME.key(), "prefers-tabs"), one));
        assertTrue(delete.handle(args(MemoryDeleteParamEnum.NAME.key(), "prefers-tabs"), one)
                .startsWith("Error: no memory note named 'prefers-tabs'"));
    }

    @Test
    @Timeout(10)
    void badArgumentsComeBackAsClearErrorsAndWriteNothing() {
        AbstractAiSession session = session("bad-args", AiTypeEnum.OPENAI_COMPATIBLE);

        assertTrue(save.handle(saveArgs("../escape", "d", "c"), session).startsWith("Error: name must be"));
        assertTrue(save.handle(saveArgs("Has Space", "d", "c"), session).startsWith("Error: name must be"));
        assertTrue(save.handle(saveArgs("ok-name", "d".repeat(201), "c"), session).contains("description is too long"));
        assertTrue(save.handle(saveArgs("ok-name", "d", "c".repeat(4001)), session).contains("content is too long"));
        assertEquals("Error: content is required",
                     save.handle(args(MemorySaveParamEnum.NAME.key(), "ok-name", MemorySaveParamEnum.DESCRIPTION.key(), "d"),
                                 session));
        assertEquals("Error: name is required", read.handle(args(), session));
        assertTrue(delete.handle(args(MemoryDeleteParamEnum.NAME.key(), "../x"), session).startsWith("Error: name must be"));

        JsonObject numeric = new JsonObject();
        numeric.addProperty(MemoryReadParamEnum.NAME.key(), 5);
        assertTrue(read.handle(new ToolRequestArguments(numeric), session).startsWith("Error: name must be a string"));
        assertFalse(Files.exists(sessions.resolve("bad-args").resolve("memory")), "nothing was created");
    }

    @Test
    @Timeout(10)
    void aSessionOfAnotherBackendIsToldClearlyAndNothingIsWritten() {
        AbstractAiSession claude = session("claude-session", AiTypeEnum.CLAUDE);

        String message = save.handle(saveArgs("note", "d", "c"), claude);

        assertTrue(message.startsWith("Error:") && message.contains("OpenAI Compatible"), message);
        assertTrue(read.handle(args(MemoryReadParamEnum.NAME.key(), "note"), claude).contains("OpenAI Compatible"));
        assertTrue(delete.handle(args(MemoryDeleteParamEnum.NAME.key(), "note"), claude).contains("OpenAI Compatible"));
        assertFalse(Files.exists(sessions.resolve("claude-session")));
    }

    @Test
    @Timeout(10)
    void onlySaveAndDeleteAreMutating() {
        assertTrue(save.isMutating());
        assertTrue(delete.isMutating());
        assertFalse(read.isMutating());
    }

    @Test
    @Timeout(10)
    void theToolsAreRegisteredInTheMemorySectionWithTheirOwnNames() {
        Map<McpToolEnum, McpToolInterface> handlers = ToolHandlerFactory.getToolHandlers(null);

        for (McpToolEnum tool : List.of(McpToolEnum.MEMORY_SAVE, McpToolEnum.MEMORY_READ, McpToolEnum.MEMORY_DELETE)) {
            McpToolInterface handler = handlers.get(tool);
            assertNotNull(handler, tool.toString());
            assertEquals(McpSectionEnum.MEMORY, handler.section());
            JsonObject schema = handler.schema(Set.of());
            assertEquals(tool.toolName(), schema.get(ToolSchemaKeyEnum.NAME.key()).getAsString());
            assertTrue(handler.instruction(EnumSet.allOf(McpInstructionOptionEnum.class)).startsWith(tool.toolName()));
        }
        assertEquals("Memory (OpenAI Compatible only)", McpSectionEnum.MEMORY.title());
    }

    @Test
    @Timeout(10)
    void everyParameterIsRequiredAndNoToolTakesAPath() {
        for (McpToolInterface handler : List.of(save, read, delete)) {
            JsonObject input = handler.schema(Set.of()).getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
            Set<String> properties = input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key()).keySet();
            Set<String> required = new java.util.HashSet<>();
            input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key()).forEach(element -> required.add(element.getAsString()));
            assertEquals(properties, required);
            assertFalse(properties.contains("filePath") || properties.contains("path") || properties.contains("directoryPath"));
        }
        assertEquals(Set.of("name", "description", "content"),
                     save.schema(Set.of()).getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key())
                             .getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key()).keySet());
        assertEquals(Set.of("name"),
                     read.schema(Set.of()).getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key())
                             .getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key()).keySet());
    }

    @Test
    @Timeout(10)
    void savesAndDeletesAreReportedToTheSessionsBackendOnly() {
        List<String> seen = new ArrayList<>();
        SessionMemoryNotifier.Listener listener = (action, name) -> seen.add(action + ":" + name);
        SessionMemoryNotifier.register("noted-session", listener);
        try {
            AbstractAiSession noted = session("noted-session", AiTypeEnum.OPENAI_COMPATIBLE);
            AbstractAiSession other = session("other-session", AiTypeEnum.OPENAI_COMPATIBLE);

            save.handle(saveArgs("kept", "d", "c"), noted);
            read.handle(args(MemoryReadParamEnum.NAME.key(), "kept"), noted);
            save.handle(saveArgs("elsewhere", "d", "c"), other);
            delete.handle(args(MemoryDeleteParamEnum.NAME.key(), "kept"), noted);
            delete.handle(args(MemoryDeleteParamEnum.NAME.key(), "missing"), noted);

            assertEquals(List.of("save:kept", "delete:kept"), seen);
        }
        finally {
            SessionMemoryNotifier.unregister("noted-session", listener);
        }
    }

    @Test
    @Timeout(10)
    void aNoteSavedByTheToolIsSeenByTheStoreTheBackendReads() throws Exception {
        save.handle(saveArgs("visible", "Seen by the backend", "body"), session("shared", AiTypeEnum.OPENAI_COMPATIBLE));

        SessionMemoryStore store = new SessionMemoryStore(SessionMemoryFolders.memoryDir(sessions.resolve("shared")));

        assertEquals("Seen by the backend", store.read("visible").orElseThrow().description());
    }
}
