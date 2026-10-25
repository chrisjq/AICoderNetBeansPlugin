package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryFolders;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory.SessionMemoryStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.ChatClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory.MemoryDeleteTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.memory.MemorySaveTool;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The Memory section of the system prompt of an OpenAI Compatible session, and the debug-log note of memory changes.
 */
@ResourceLock("aicoder-debuglog")
class OpenAiCompatibleMemoryPromptTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50))
            .withProbeTimeout(Duration.ofMillis(100));

    private static final SecretStore NO_SECRETS = new SecretStore() {
        @Override
        public char[] read(String key) {
            return null;
        }

        @Override
        public void save(String key, char[] value, String description) {
        }

        @Override
        public void delete(String key) {
        }
    };

    @TempDir
    Path sessions;

    private final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
    private final List<ChatRequestSpec> requests = new CopyOnWriteArrayList<>();
    private OpenAiCompatibleAiProcessManager manager;
    private AiSession session;

    @BeforeEach
    void pointSessionFoldersAtTheTempDir() {
        SessionMemoryFolders.setResolverForTest(id -> sessions.resolve(id));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
        SessionMemoryFolders.setResolverForTest(null);
    }

    private ChatClient recordingClient() {
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            requests.add(request);
            return CompletableFuture.completedFuture(new ClientResult(
                    new AssembledReply("Done.", List.of(), "stop", null, null, true, true), ClientResult.End.COMPLETE,
                    null, false));
        };
    }

    private OpenAiCompatibleAiProcessManager start(OpenAiCompatibleSessionSettings settings) {
        String id = "oc-memory-" + System.nanoTime();
        session = new AiSession(id, "OC", null, AiTypeEnum.OPENAI_COMPATIBLE, null, settings, Instant.now(), Instant.now());
        ChatClient client = recordingClient();
        AiProcessEventListener listener = events::add;
        OpenAiCompatibleAiProcessManager created = new OpenAiCompatibleAiProcessManager(
                listener, limits -> client, (sid, keySet) -> new SessionSecrets(sid, NO_SECRETS, Runnable::run, keySet),
                LIMITS);
        created.setContextDirResolverForTest(sid -> sessions.resolve(sid));
        created.startWithoutMcpForTest(session);
        manager = created;
        return created;
    }

    private OpenAiCompatibleAiProcessManager start() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://localhost:1");
        settings.setModel("model-x");
        return start(settings);
    }

    private long turnsDone() {
        return events.stream().filter(TurnCompleteEvent.class::isInstance).count();
    }

    /**
     * Runs one turn and returns the system prompt of its first request.
     */
    private String turn(String text) throws Exception {
        long before = turnsDone();
        int requestsBefore = requests.size();
        manager.sendPrompt(text, null, List.of());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (turnsDone() == before || manager.isProcessing()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The turn did not finish");
            }
            Thread.sleep(5);
        }
        assertTrue(requests.size() > requestsBefore, "the turn sent a request");
        return systemPromptOf(requests.get(requestsBefore));
    }

    private static String systemPromptOf(ChatRequestSpec request) {
        ChatMessage first = request.messages().get(0);
        return first.role() == ChatRole.SYSTEM ? first.content() : "";
    }

    private SessionMemoryStore store() {
        return new SessionMemoryStore(SessionMemoryFolders.memoryDir(sessions.resolve(session.id())));
    }

    private AbstractAiSession toolSession() {
        return new AbstractAiSession(session) {
            @Override
            public String getId() {
                return session.id();
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

    private void save(String name, String description) {
        String result = new MemorySaveTool().handle(
                args("name", name, "description", description, "content", "Body of " + name), toolSession());
        assertTrue(result.startsWith("Memory note"), result);
    }

    private static void touch(Path file, String isoInstant) throws Exception {
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(isoInstant)));
    }

    @Test
    @Timeout(30)
    void theMemorySectionIsInThePromptWithTheIndexNewestFirst() throws Exception {
        start();
        SessionMemoryStore notes = store();
        notes.save("older-fact", "The older fact", "1");
        notes.save("newer-fact", "The newer fact", "2");
        touch(notes.folder().resolve("older-fact.md"), "2026-10-01T00:00:00Z");
        touch(notes.folder().resolve("newer-fact.md"), "2026-10-02T00:00:00Z");

        String prompt = turn("hello");

        assertTrue(prompt.contains("## Memory"), prompt);
        assertTrue(prompt.contains("- newer-fact: The newer fact"), prompt);
        assertTrue(prompt.contains("- older-fact: The older fact"), prompt);
        assertTrue(prompt.indexOf("newer-fact") < prompt.indexOf("older-fact"), "newest first");
        assertTrue(prompt.indexOf("## Memory") < prompt.indexOf("- newer-fact"));
    }

    @Test
    @Timeout(30)
    void withoutNotesTheSectionStillGuidesTheModel() throws Exception {
        start();

        String prompt = turn("hello");

        assertTrue(prompt.contains("## Memory"), prompt);
        assertTrue(prompt.contains("No notes saved yet."), prompt);
    }

    @Test
    @Timeout(30)
    void thereIsNoMemorySectionWhenTheMemoryToolsAreSwitchedOff() throws Exception {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://localhost:1");
        settings.setModel("model-x");
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.MEMORY, true));
        start(settings);
        store().save("hidden", "Must not be shown", "x");

        String prompt = turn("hello");

        assertFalse(prompt.contains("## Memory"), prompt);
        assertFalse(prompt.contains("hidden: Must not be shown"), prompt);
    }

    @Test
    @Timeout(30)
    void aNoteSavedByTheToolIsInTheNextTurnsPromptAndADeletedOneIsGone() throws Exception {
        start();
        String first = turn("one");
        assertTrue(first.contains("No notes saved yet."), first);

        save("fresh-note", "Saved between turns");
        String second = turn("two");
        assertTrue(second.contains("- fresh-note: Saved between turns"), second);

        String deleted = new MemoryDeleteTool().handle(args("name", "fresh-note"), toolSession());
        assertEquals("Memory note 'fresh-note' deleted.", deleted);
        String third = turn("three");
        assertFalse(third.contains("fresh-note"), third);
    }

    @Test
    @Timeout(30)
    void resetKeepsTheMemory() throws Exception {
        start();
        save("kept-fact", "Survives a reset");
        turn("one");

        assertTrue(manager.resetContext());

        assertTrue(Files.isRegularFile(store().folder().resolve("kept-fact.md")));
        String after = turn("two");
        assertTrue(after.contains("- kept-fact: Survives a reset"), after);
    }

    @Test
    @Timeout(30)
    void savesAndDeletesAreNotedInTheBackendDebugLogByNameOnly() throws Exception {
        start();
        Path logDir = Files.createDirectories(sessions.resolve("log"));
        manager.setSessionConfigDir(logDir);
        manager.setBackendDebugLog(true);
        turn("hello");

        save("noted-fact", "Described");
        new MemoryDeleteTool().handle(args("name", "noted-fact"), toolSession());
        manager.closeBackendDebugLog(false).get();

        String log = Files.readString(logDir.resolve("backend-debug.log"));
        assertTrue(log.contains(" EVENT memory save noted-fact"), log);
        assertTrue(log.contains(" EVENT memory delete noted-fact"), log);
        assertFalse(log.contains("Body of noted-fact"), "the note's content is not logged");
    }
}
