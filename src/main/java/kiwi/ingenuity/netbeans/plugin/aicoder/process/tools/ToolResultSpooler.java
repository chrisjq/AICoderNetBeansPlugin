package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;

/**
 * Spools oversized tool output to disk so a tool can return a short, useful result without anything becoming
 * unavailable.
 * <p>
 * Files land in the calling session's registry-owned temp tree:
 * {@code ~/.ai-coder/{type}/{sessionId}/tmp/tool_results/}. The {@code tmp} parent is minted and owned by
 * {@link TempFileRegistry} — which places it under the session config directory, where the per-session scope exemption
 * ({@link McpHookServer#isOwnSessionConfigFile}) makes the files readable by the AI via
 * GetFileContent/FilterFileContent even under restrict-to-project, no Bash round-trip and no widened scope rule.
 * <p>
 * Lifetime is the registry's, not this class's: an age sweep prunes old logs from long-lived sessions, and session
 * close / IDE shutdown / plugin uninstall remove the whole temp directory wholesale. (An earlier prune-to-20 rule here
 * existed only because these files had no lifecycle of their own; with the registry that rule is gone.)
 * <p>
 * Creation goes through the registry's {@code createTempFile} — the file is born inside the cache with its recorded
 * creation time, and this class only fills in the content. Uniqueness is the registry's atomic guarantee, so a burst of
 * same-instant results can never clobber each other.
 * <p>
 * Like the registry itself, spooling is best-effort and never throws: any failure returns {@code null} and callers fall
 * back to returning the full output inline.
 */
public final class ToolResultSpooler {

    private static final Logger LOG = Logger.getLogger(ToolResultSpooler.class.getName());

    /**
     * Name of the spool subdirectory inside the session's registry-owned temp directory, kept distinct so build/test
     * results stay recognisable next to other temp content such as pasted images.
     */
    public static final String DIR_NAME = "tool_results";

    /**
     * Writes {@code text} into the given session's {@code tool_results} directory.
     *
     * @param sessionId the calling AI session; locates its own config directory
     * @param toolName short label for the filename, e.g. {@code "maven"},
     * {@code "git-diff"}, {@code "search"} — lowercase letters, digits and dashes only
     * @param text the complete unabridged output to preserve
     * @return the written file's path, or null when the file cannot be created or written — callers treat null as
     * "return the full output instead"
     */
    public static Path spool(String sessionId, String toolName, String text) {
        TempFile file = TempFileRegistry.createTempFile(sessionId, DIR_NAME, toolName, ".log");
        if (file == null) {
            return null;
        }
        return writeContent(file, text);
    }

    private static Path writeContent(TempFile file, String text) {
        try {
            Files.writeString(file.path(), text == null ? "" : text, StandardCharsets.UTF_8);
            return file.path();
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not spool tool output into " + file, e);
            // Drop the empty husk from cache and disk instead of leaving it for
            // the next age sweep.
            TempFileRegistry.deleteTempFile(file);
            return null;
        }
    }

    private ToolResultSpooler() {
    }
}
