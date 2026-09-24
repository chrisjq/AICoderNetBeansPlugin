package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.extension.PiExtensionFiles;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PiAiMcpRegistrarTest {

    private String sessionId;
    private String otherSessionId;

    @BeforeEach
    void setUp() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        sessionId = "pi-mcp-registrar-test-" + UUID.randomUUID();
        otherSessionId = "pi-mcp-registrar-test-other-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
        PiExtensionFiles.delete(sessionId);
        PiExtensionFiles.delete(otherSessionId);
    }

    @Test
    void constructorExposesSessionIdAiTypeAndExecutablePath() {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        assertEquals(sessionId, registrar.getSessionId());
        assertEquals(AiTypeEnum.PI, registrar.getAiType());
        assertEquals("/usr/local/bin/pi", registrar.getExecutablePath());
    }

    @Test
    void registerHooksAlwaysSucceeds() {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        assertTrue(registrar.registerHooks("http://127.0.0.1:1"));
    }

    @Test
    void addMcpEndpointDoesNotThrow() {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        registrar.addMcpEndpoint("http://127.0.0.1:1/mcp/pi"); // must not throw
    }

    @Test
    void getExtensionFilePath_isNullWhenTheSharedMcpServerIsNotRunning() {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        assertNull(registrar.getExtensionFilePath());
    }

    @Test
    void getExtensionFilePath_generatesTheFileOnceTheSharedServerIsUp() throws Exception {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        assertTrue(McpServerRegistry.register(registrar).get(5, TimeUnit.SECONDS));

        String path = registrar.getExtensionFilePath();

        assertNotNull(path, "the shared server is up, so generation must succeed");
        assertTrue(Files.isRegularFile(Path.of(path)));
        String content = Files.readString(Path.of(path));
        assertTrue(content.contains("\"sessionId\":\"" + sessionId + "\""));
    }

    @Test
    void getExtensionFilePath_isMemoizedAcrossCalls() throws Exception {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        assertTrue(McpServerRegistry.register(registrar).get(5, TimeUnit.SECONDS));

        String first = registrar.getExtensionFilePath();
        String second = registrar.getExtensionFilePath();

        assertSame(first, second, "a second call must not regenerate the file");
    }

    @Test
    void unregisterHooks_deletesThisInstancesOwnFile() throws Exception {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        assertTrue(McpServerRegistry.register(registrar).get(5, TimeUnit.SECONDS));
        Path written = Path.of(registrar.getExtensionFilePath());
        assertTrue(Files.exists(written));

        registrar.unregisterHooks();

        assertFalse(Files.exists(written));
    }

    @Test
    void removeMcpEndpoint_alsoDeletesTheExtensionFile() throws Exception {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        assertTrue(McpServerRegistry.register(registrar).get(5, TimeUnit.SECONDS));
        Path written = Path.of(registrar.getExtensionFilePath());
        assertTrue(Files.exists(written));

        registrar.removeMcpEndpoint();

        assertFalse(Files.exists(written));
    }

    /**
     * The scenario {@code PiAiProcessManager} relies on: with several pi sessions open, calling THIS
     * registrar's {@link PiAiMcpRegistrar#deleteExtensionFile()} directly (not via the type-gated
     * {@code registerHooks}/{@code unregisterHooks} framework callbacks) removes only its own session's file,
     * leaving a sibling session's file untouched — see the class javadoc's "closing one removes only its
     * file" requirement.
     */
    @Test
    void deleteExtensionFile_removesOnlyItsOwnSessionsFile() throws Exception {
        PiAiMcpRegistrar mine = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        PiAiMcpRegistrar other = new PiAiMcpRegistrar(otherSessionId, "/usr/local/bin/pi");
        assertTrue(McpServerRegistry.register(mine).get(5, TimeUnit.SECONDS));
        assertTrue(McpServerRegistry.register(other).get(5, TimeUnit.SECONDS));
        Path myFile = Path.of(mine.getExtensionFilePath());
        Path otherFile = Path.of(other.getExtensionFilePath());
        assertTrue(Files.exists(myFile));
        assertTrue(Files.exists(otherFile));

        mine.deleteExtensionFile();

        assertFalse(Files.exists(myFile), "this session's file must be gone");
        assertTrue(Files.exists(otherFile), "the other session's file must be untouched");
    }

    @Test
    void deleteExtensionFile_isIdempotentAndNeverThrows() throws Exception {
        PiAiMcpRegistrar registrar = new PiAiMcpRegistrar(sessionId, "/usr/local/bin/pi");
        assertTrue(McpServerRegistry.register(registrar).get(5, TimeUnit.SECONDS));
        registrar.getExtensionFilePath();

        registrar.deleteExtensionFile();
        registrar.deleteExtensionFile(); // must not throw on a second call
    }
}
