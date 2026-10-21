package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the per-process {@code --mcp-config} argument on the Claude spawn command: one argument in the {@code =} form,
 * carrying the session's own endpoint under the plugin's usual server name, on fresh and resumed launches, and absent
 * (with the global entry as fallback) when no endpoint is available.
 */
class ClaudeAiProcessManagerMcpConfigTest {

    private static final String SERVER_NAME = "aicoder-nb-ki-plugin";
    private static final String SESSION_URL = "http://127.0.0.1:6969/mcp/claude/route-1234";

    @TempDir
    Path tempDir;

    private final List<Object> events = new CopyOnWriteArrayList<>();
    private TestManager manager;
    private File workDir;

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    private static final class TestManager extends ClaudeAiProcessManagerEffortTest.CapturingClaudeAiProcessManager {

        volatile String url = SESSION_URL;

        TestManager(List<Object> events) {
            super(events::add);
        }

        @Override
        protected String sessionMcpEndpointUrl() {
            return url;
        }
    }

    @BeforeEach
    void setup() throws IOException {
        manager = new TestManager(events);
        manager.setupForTest();
        workDir = Files.createTempDirectory("claude-mcp-config-test").toFile();
    }

    @AfterEach
    void teardown() {
        manager.stop();
    }

    private static List<String> mcpConfigArgs(List<String> cmd) {
        return cmd.stream().filter(a -> a.startsWith("--mcp-config")).toList();
    }

    private static JsonObject serverEntry(String mcpConfigArg) {
        JsonObject config = JsonParser.parseString(mcpConfigArg.substring("--mcp-config=".length())).getAsJsonObject();
        return config.getAsJsonObject("mcpServers").getAsJsonObject(SERVER_NAME);
    }

    @Test
    void aFreshLaunchCarriesTheSessionEndpointUnderThePluginServerName() throws InterruptedException {
        manager.sendPrompt("prompt", workDir, List.of());
        List<String> cmd = manager.launchCommand(0);

        List<String> args = mcpConfigArgs(cmd);
        assertEquals(1, args.size(), cmd.toString());
        JsonObject entry = serverEntry(args.get(0));
        assertEquals("http", entry.get("type").getAsString());
        assertEquals(SESSION_URL, entry.get("url").getAsString());
        assertTrue(cmd.contains("--session-id"), "this is the fresh-launch branch");
    }

    @Test
    void theFlagIsASingleEqualsFormArgumentBecauseItIsVariadic() throws InterruptedException {
        manager.sendPrompt("prompt", workDir, List.of());
        List<String> cmd = manager.launchCommand(0);

        assertFalse(cmd.contains("--mcp-config"), "the space form would swallow the next argument as a config path");
        assertTrue(mcpConfigArgs(cmd).get(0).startsWith("--mcp-config={"));
    }

    @Test
    void globalMcpServersAreLeftLoadingByNotUsingStrictMode() throws InterruptedException {
        manager.sendPrompt("prompt", workDir, List.of());

        assertFalse(manager.launchCommand(0).contains("--strict-mcp-config"));
    }

    @Test
    void theServerNameMatchesTheOneAllowedToolsAreBuiltFrom() {
        // --allowedTools names every tool mcp__<server>__<tool>; a per-process entry under any other name would leave
        // all of them unallowed.
        assertTrue(McpToolEnum.allMcpNames().startsWith("mcp__" + SERVER_NAME + "__"));
        JsonObject entry = serverEntry(ClaudeAiProcessManager.mcpConfigArgument(SESSION_URL));
        assertEquals(SESSION_URL, entry.get("url").getAsString());
    }

    @Test
    void theFlagIsOmittedWhenThereIsNoEndpoint() throws InterruptedException {
        manager.url = null;
        manager.sendPrompt("prompt", workDir, List.of());

        assertTrue(mcpConfigArgs(manager.launchCommand(0)).isEmpty(), manager.launchCommand(0).toString());
    }

    @Test
    void aResumedLaunchAlsoCarriesTheFlag() throws InterruptedException {
        manager.configureEffort("low");
        manager.sendPrompt("turn 1", workDir, List.of());
        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 1 complete");

        manager.configureEffort("high");
        manager.recycleForModelChange();
        manager.sendPrompt("turn 2", workDir, List.of());

        List<String> resumed = manager.launchCommand(1);
        assertTrue(resumed.contains("--resume"), "the second launch resumes the conversation: " + resumed);
        List<String> args = mcpConfigArgs(resumed);
        assertEquals(1, args.size(), resumed.toString());
        assertEquals(SESSION_URL, serverEntry(args.get(0)).get("url").getAsString());
    }

    @Test
    void theConfigIsBuiltWithGsonSoAnyUrlSurvivesVerbatim() {
        String url = "http://127.0.0.1:6969/mcp/claude/a&b\"c";

        String arg = ClaudeAiProcessManager.mcpConfigArgument(url);

        assertTrue(arg.startsWith("--mcp-config="));
        assertEquals(url, serverEntry(arg).get("url").getAsString(), "quotes are escaped, '&' is not mangled");
        assertTrue(arg.contains("a&b"), "HTML escaping is off, as for the other JSON handed to the CLI");
    }

    private static JsonObject serverEntryOfFile(Path file) throws IOException {
        JsonObject config = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        return config.getAsJsonObject("mcpServers").getAsJsonObject(SERVER_NAME);
    }

    @Test
    void withASessionConfigDirTheArgumentPointsAtAFileHoldingTheConfig() throws Exception {
        Path configDir = Files.createTempDirectory("claude-mcp-config-dir");
        manager.setSessionConfigDir(configDir);
        manager.sendPrompt("prompt", workDir, List.of());

        List<String> args = mcpConfigArgs(manager.launchCommand(0));
        assertEquals(1, args.size(), args.toString());
        String value = args.get(0).substring("--mcp-config=".length());
        assertFalse(value.startsWith("{"), "the JSON is not inlined: " + value);
        Path file = Path.of(value);
        assertTrue(file.isAbsolute(), value);
        assertEquals(configDir.resolve("mcp-config.json").toAbsolutePath(), file);
        JsonObject entry = serverEntryOfFile(file);
        assertEquals("http", entry.get("type").getAsString());
        assertEquals(SESSION_URL, entry.get("url").getAsString());
    }

    @Test
    void aSessionConfigDirWithSpacesStaysOneEqualsFormArgumentAndCreatesTheFile() throws Exception {
        Path configDir = Files.createTempDirectory("claude mcp config with spaces");
        manager.setSessionConfigDir(configDir);
        manager.sendPrompt("prompt", workDir, List.of());

        List<String> args = mcpConfigArgs(manager.launchCommand(0));
        assertEquals(1, args.size(), args.toString());
        String value = args.get(0).substring("--mcp-config=".length());
        Path file = Path.of(value);
        assertEquals(configDir.resolve("mcp-config.json").toAbsolutePath(), file);
        assertTrue(Files.exists(file), file.toString());
        assertEquals(SESSION_URL, serverEntryOfFile(file).get("url").getAsString());
    }

    /**
     * The Windows case: the session config dir sits under a user profile like {@code C:\Users\John Smith\...}, so the
     * spaces are in a PARENT component of the path, not just the leaf name. The {@code --mcp-config=} value is one list
     * element handed straight to {@link ProcessBuilder} with no shell or {@code .cmd} shim in between, so the JDK
     * quotes it as a single argv entry; the assertion pins that the element carries the raw, UNQUOTED absolute path
     * (the plugin must not pre-quote, or the CLI would receive literal quotes).
     */
    @Test
    void aNestedConfigDirWithSpacesInAParentComponentStaysOneUnquotedArgument() throws Exception {
        Path configDir = tempDir.resolve("John Smith").resolve("claude config");
        manager.setSessionConfigDir(configDir);
        manager.sendPrompt("prompt", workDir, List.of());

        List<String> cmd = manager.launchCommand(0);
        List<String> args = mcpConfigArgs(cmd);
        assertEquals(1, args.size(), cmd.toString());

        Path file = configDir.resolve("mcp-config.json").toAbsolutePath();
        String expected = "--mcp-config=" + file;
        assertEquals(expected, args.get(0), "one list element, unquoted absolute path WITH the spaces: " + cmd);
        assertFalse(args.get(0).startsWith("--mcp-config=\""), "the plugin must not pre-quote; ProcessBuilder's list form does it");
        assertTrue(Files.exists(file), file.toString());
        assertEquals(SESSION_URL, serverEntryOfFile(file).get("url").getAsString());
    }

    @Test
    void theFileIsOverwrittenOnEachLaunchIncludingAResume() throws Exception {
        Path configDir = Files.createTempDirectory("claude-mcp-config-dir");
        Path file = configDir.resolve("mcp-config.json");
        Files.writeString(file, "stale", StandardCharsets.UTF_8);
        manager.setSessionConfigDir(configDir);
        manager.configureEffort("low");
        manager.sendPrompt("turn 1", workDir, List.of());
        manager.launchCommand(0);
        assertEquals(SESSION_URL, serverEntryOfFile(file).get("url").getAsString());

        manager.getSession().sendRawLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        awaitTrue(() -> !manager.isProcessing(), "turn 1 complete");
        manager.url = "http://127.0.0.1:6969/mcp/claude/route-5678";
        manager.configureEffort("high");
        manager.recycleForModelChange();
        manager.sendPrompt("turn 2", workDir, List.of());

        List<String> resumed = manager.launchCommand(1);
        assertTrue(resumed.contains("--resume"), resumed.toString());
        assertEquals(1, mcpConfigArgs(resumed).size(), resumed.toString());
        assertEquals("http://127.0.0.1:6969/mcp/claude/route-5678", serverEntryOfFile(file).get("url").getAsString());
        assertFalse(resumed.contains("--strict-mcp-config"));
    }

    @Test
    void stopRemovesTheGeneratedMcpConfigButKeepsTheSessionConfigDirectory() throws Exception {
        Path configDir = Files.createTempDirectory("claude-mcp-config-stop");
        Path file = configDir.resolve("mcp-config.json");
        manager.setSessionConfigDir(configDir);
        manager.sendPrompt("prompt", workDir, List.of());

        assertTrue(Files.exists(file), file.toString());

        manager.stop();

        assertFalse(Files.exists(file), file.toString());
        assertTrue(Files.isDirectory(configDir), "stop must retain the session configuration directory");
    }

    @Test
    void aConfigDirThatCannotHoldTheFileFallsBackToTheInlineForm() throws Exception {
        Path notADirectory = Files.createTempFile("claude-mcp-config-not-a-dir", ".tmp");
        manager.setSessionConfigDir(notADirectory);
        manager.sendPrompt("prompt", workDir, List.of());

        List<String> args = mcpConfigArgs(manager.launchCommand(0));
        assertEquals(1, args.size(), args.toString());
        assertTrue(args.get(0).startsWith("--mcp-config={"), args.get(0));
        assertEquals(SESSION_URL, serverEntry(args.get(0)).get("url").getAsString());
    }

    @Test
    void noEndpointTellsTheTabThePluginToolsAreUnavailable() throws InterruptedException {
        manager.url = null;
        manager.sendPrompt("prompt", workDir, List.of());
        manager.launchCommand(0);

        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent s
                && s.type() == StatusEventTypeEnum.INFO
                && ClaudeAiProcessManager.MCP_UNAVAILABLE_NOTICE.equals(s.text())), events.toString());
    }

    @Test
    void anAvailableEndpointEmitsNoUnavailableNotice() throws InterruptedException {
        manager.sendPrompt("prompt", workDir, List.of());
        manager.launchCommand(0);

        assertTrue(events.stream().noneMatch(e -> e instanceof StatusEvent s
                && ClaudeAiProcessManager.MCP_UNAVAILABLE_NOTICE.equals(s.text())), events.toString());
    }
}
