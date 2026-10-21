package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

/**
 * MCP registrar for Grok. Like OpenCode, Grok receives its MCP server inline in the {@code session/new}/
 * {@code session/load} ACP request (see {@code GrokAiProcessManager.buildSessionNewParams}), not via a CLI command or a
 * config file — so no {@code grok mcp add} call and no PreToolUse hook file are needed any more.
 * {@link #removeMcpEndpoint()} still removes the legacy global entry written by pre-ACP plugin releases. Permission is
 * handled over the ACP wire too ({@code session/request_permission}, bridged by
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpClientHandler}), which is what replaces the old hook
 * file this registrar used to write.
 *
 * <p>
 * Each session's URL comes from {@code McpServerRegistry.endpointUrlFor(AiTypeEnum.GROK, routingId)} at spawn.
 */
public final class GrokAiMcpRegistrar extends AiMcpRegistrar {

    private static final Logger LOG = Logger.getLogger(GrokAiMcpRegistrar.class.getName());

    private final String executablePath;

    public GrokAiMcpRegistrar(String sessionId) {
        this(sessionId, GrokExecutableLocator.locate());
    }

    public GrokAiMcpRegistrar(String sessionId, String executablePath) {
        super(sessionId, AiTypeEnum.GROK);
        this.executablePath = executablePath;
    }

    @Override
    public void removeMcpEndpoint() {
        removeLegacyGlobalEndpoint();
    }

    private void removeLegacyGlobalEndpoint() {
        if (executablePath == null || executablePath.isBlank()) {
            LOG.fine("Grok executable unavailable; skipping legacy global MCP cleanup");
            return;
        }
        try {
            List<String> command = GrokExecutableLocator.buildHostCommand(executablePath,
                                                                          "mcp", "remove", StringConst.PLUGIN_ID);
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(GrokTimeoutEnum.GROK_CLI_CONFIG_REMOVE_MILLIS.millis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.fine("Timed out removing the legacy global Grok MCP entry");
            }
            else if (process.exitValue() != 0) {
                LOG.fine("Grok reported no removable legacy global MCP entry");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.log(Level.FINE, "Interrupted removing the legacy global Grok MCP entry", ex);
        }
        catch (IOException | RuntimeException ex) {
            LOG.log(Level.FINE, "Unable to remove the legacy global Grok MCP entry", ex);
        }
    }

    @Override
    public boolean registerHooks(String serverBaseUrl) {
        return true; // no-op: Grok's permission bridge runs over ACP, not a PreToolUse hook file.
    }

    @Override
    public void unregisterHooks() {
        // no-op
    }
}
