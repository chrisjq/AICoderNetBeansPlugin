package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks in the explicit MCP tool-call timeout handed to Grok: the constant derives from the shared mutation-lock bound,
 * the config.toml patch touches only this plugin's own [mcp_servers.&lt;id&gt;] section, and a missing section is
 * skipped rather than duplicated.
 */
class GrokAiMcpRegistrarToolTimeoutTest {

    private static final String SECTION_HEADER = "[mcp_servers." + StringConst.PLUGIN_ID + "]";
    private static final String QUOTED_SECTION_HEADER
            = "[mcp_servers.\"" + StringConst.PLUGIN_ID + "\"]";
    private static final long EXPECTED_SECONDS = TimeUnit.MILLISECONDS.toSeconds(
            GrokTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis());
    private static final String EXPECTED_LINE = "tool_timeout_sec = " + EXPECTED_SECONDS;

    @Test
    void constantDerivesFromSharedMutationLockBound() {
        // TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS is a static long field.
        assertEquals(TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS,
                GrokTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis());
        assertTrue(EXPECTED_SECONDS > 0);
    }

    @Test
    void toolTimeoutLines_insertsIntoExistingSection() {
        List<String> out = GrokAiMcpRegistrar.toolTimeoutLines(List.of(
                SECTION_HEADER,
                "url = \"http://127.0.0.1:9/mcp/grok\""), EXPECTED_SECONDS);
        assertEquals(List.of(
                SECTION_HEADER,
                "url = \"http://127.0.0.1:9/mcp/grok\"",
                EXPECTED_LINE), out);
    }

    @Test
    void toolTimeoutLines_replacesExistingValue() {
        List<String> out = GrokAiMcpRegistrar.toolTimeoutLines(List.of(
                SECTION_HEADER,
                "url = \"http://127.0.0.1:9/mcp/grok\"",
                "tool_timeout_sec = 6000"), EXPECTED_SECONDS);
        assertEquals(List.of(
                SECTION_HEADER,
                "url = \"http://127.0.0.1:9/mcp/grok\"",
                EXPECTED_LINE), out);
    }

    @Test
    void toolTimeoutLines_leavesOtherServersUntouched() {
        List<String> in = List.of(
                "[mcp_servers.filesystem]",
                "command = \"npx\"",
                "tool_timeout_sec = 123",
                SECTION_HEADER,
                "url = \"http://127.0.0.1:9/mcp/grok\"");
        List<String> out = GrokAiMcpRegistrar.toolTimeoutLines(in, EXPECTED_SECONDS);
        assertEquals(6, out.size());
        assertEquals("tool_timeout_sec = 123", out.get(2));
        assertEquals(EXPECTED_LINE, out.get(5));
    }

    @Test
    void toolTimeoutLines_handlesQuotedHeader() {
        List<String> out = GrokAiMcpRegistrar.toolTimeoutLines(List.of(
                QUOTED_SECTION_HEADER,
                "url = \"http://127.0.0.1:9/mcp/grok\""), EXPECTED_SECONDS);
        assertEquals(List.of(
                QUOTED_SECTION_HEADER,
                "url = \"http://127.0.0.1:9/mcp/grok\"",
                EXPECTED_LINE), out);
    }

    @Test
    void toolTimeoutLines_missingSectionReturnsNull() {
        assertNull(GrokAiMcpRegistrar.toolTimeoutLines(List.of(
                "[mcp_servers.filesystem]",
                "tool_timeout_sec = 6000"), EXPECTED_SECONDS));
        assertNull(GrokAiMcpRegistrar.toolTimeoutLines(List.of(), EXPECTED_SECONDS));
    }

    @Test
    void applyToolTimeout_writesPatchedConfig(@TempDir File tempRoot) throws IOException {
        Path cfg = tempRoot.toPath().resolve("config.toml");
        Files.writeString(cfg, SECTION_HEADER + "\nurl = \"http://x\"\n");
        GrokAiMcpRegistrar.applyToolTimeout(cfg);
        assertTrue(Files.readAllLines(cfg).contains(EXPECTED_LINE));
        assertFalse(Files.exists(cfg.resolveSibling(cfg.getFileName() + ".tmp")));
    }

    @Test
    void applyToolTimeout_isIdempotent(@TempDir File tempRoot) throws IOException {
        Path cfg = tempRoot.toPath().resolve("config.toml");
        Files.writeString(cfg, SECTION_HEADER + "\nurl = \"http://x\"\n");
        GrokAiMcpRegistrar.applyToolTimeout(cfg);
        GrokAiMcpRegistrar.applyToolTimeout(cfg);
        assertEquals(1, Files.readAllLines(cfg).stream()
                .filter(l -> l.startsWith("tool_timeout_sec")).count());
    }

    @Test
    void applyToolTimeout_missingConfigIsNoOp(@TempDir File tempRoot) {
        GrokAiMcpRegistrar.applyToolTimeout(tempRoot.toPath().resolve("absent.toml"));
        assertFalse(Files.exists(tempRoot.toPath().resolve("absent.toml")));
    }
}
