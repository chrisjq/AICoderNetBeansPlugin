package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.extension.PiExtensionFiles;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.extension.PiExtensionGenerator;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;

/**
 * Pi's MCP registration strategy. Unlike Claude/Grok, pi writes no shared per-type CLI config: instead, this registrar
 * has {@link PiExtensionGenerator} write ONE per-session extension file carrying the MCP URL, hook URL and gated-tool
 * list, and deletes it when the session stops.
 *
 * <p>
 * <b>Why generation is NOT done in {@link #addMcpEndpoint}/{@link #registerHooks}.</b> Those two methods (see
 * {@link AiMcpRegistrar}'s own javadoc: "Called only once per AI type") are invoked by {@code McpServerRegistry}'s
 * supervisor only when this session is the FIRST of {@link AiTypeEnum#PI} to register — exactly right for Claude's
 * single shared {@code settings.json} hook, wrong for a file that must exist per-session. Generation instead happens
 * lazily, memoized, on the first call to {@link #getExtensionFilePath()} — which {@code PiAiProcessManager} only calls
 * after {@code McpServerRegistry.register(this)} has already completed successfully, guaranteeing the shared hook
 * server is up and {@code McpServerRegistry.endpointUrlFor(PI)} is non-null (it would often still be null if generation
 * ran from this registrar's constructor, before registration).
 *
 * <p>
 * <b>Deletion is NOT left to {@link #removeMcpEndpoint}/{@link #unregisterHooks} either</b> — symmetrically, those are
 * only invoked by the supervisor when this session is the LAST of {@link AiTypeEnum#PI} still registered, so with
 * several pi sessions open, closing one that is neither first nor last would never run them at all. Per that decision,
 * {@link #deleteExtensionFile()} is public and idempotent specifically so {@code PiAiProcessManager} can call it
 * directly from every one of its own stop paths (normal stop, failed start, unexpected process exit) in a
 * {@code finally}, guaranteeing this instance's file disappears exactly when that session stops, regardless of how many
 * other pi sessions remain open. {@link #removeMcpEndpoint}/{@link #unregisterHooks} still call it too, purely as a
 * harmless safety net for the first/last-of-type cases the framework does reach.
 */
public final class PiAiMcpRegistrar extends AiMcpRegistrar {

    private static final Logger LOG = Logger.getLogger(PiAiMcpRegistrar.class.getName());

    private final String executablePath;
    private final Object generationLock = new Object();
    private volatile String extensionFilePath;

    public PiAiMcpRegistrar(String sessionId, String executablePath) {
        super(sessionId, AiTypeEnum.PI);
        this.executablePath = executablePath;
    }

    public String getExecutablePath() {
        return executablePath;
    }

    /**
     * This session's generated extension file path, generating it on first call if needed. Returns {@code null} before
     * generation has been attempted, or if it failed (e.g. the shared MCP server is not running, or the write itself
     * failed) — {@code PiAiProcessManager.ensureSession()} treats a null path as a FATAL start error (a failure to
     * write it is a FATAL start error").
     */
    public String getExtensionFilePath() {
        String path = extensionFilePath;
        if (path != null) {
            return path;
        }
        synchronized (generationLock) {
            if (extensionFilePath != null) {
                return extensionFilePath;
            }
            McpHookServer server = McpServerRegistry.getServer();
            String mcpUrl = McpServerRegistry.endpointUrlFor(AiTypeEnum.PI);
            if (server == null || mcpUrl == null) {
                LOG.log(Level.WARNING,
                        "Cannot generate pi extension file for session {0}: the shared MCP server is not running",
                        getSessionId());
                return null;
            }
            String hookUrl = server.getBaseUrl() + "/";
            try {
                Path written = PiExtensionGenerator.generate(getSessionId(), mcpUrl, hookUrl);
                extensionFilePath = written.toString();
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to generate pi extension file for session " + getSessionId(), e);
                extensionFilePath = null;
            }
            return extensionFilePath;
        }
    }

    @Override
    public void addMcpEndpoint(String endpointUrl) {
        // No shared per-type CLI config: the MCP URL is baked into each session's own generated
        // extension file instead — see getExtensionFilePath().
    }

    @Override
    public void removeMcpEndpoint() {
        deleteExtensionFile();
    }

    @Override
    public boolean registerHooks(String serverBaseUrl) {
        // No shared hook file: the review-gate URL is baked into each session's own generated
        // extension file instead. Always succeeds so a fresh pi session never blocks MCP server
        // startup for other AI types.
        return true;
    }

    @Override
    public void unregisterHooks() {
        deleteExtensionFile();
    }

    /**
     * Deletes this session's own extension file, if it was generated. Public and idempotent so
     * {@code PiAiProcessManager} can call it directly from every stop path (normal stop, failed start, unexpected
     * process exit) in a {@code finally} — see this class's javadoc. Never throws; a failed delete is logged and left
     * for {@link PiExtensionFiles#deleteAll()}/{@link PiExtensionFiles#sweepAtStartup()} to catch later.
     */
    public void deleteExtensionFile() {
        PiExtensionFiles.delete(getSessionId());
        extensionFilePath = null;
    }
}
