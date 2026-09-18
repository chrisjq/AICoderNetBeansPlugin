package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;

/**
 * Owns the plugin-generated pi extension directory, {@code <plugin config dir>/pi/extensions/}, a plugin-owned
 * directory never placed where pi auto-discovers extensions (spec *Extension file generation and lifetime*). Each
 * session's file is named {@code aicoder-pi-<sessionId>.ts}, exists only while that session runs, and is deleted in
 * every stop path (register/deregister via {@code PiAiMcpRegistrar}), at IDE shutdown ({@link #deleteAll()}), and swept
 * at plugin startup ({@link #sweepAtStartup()}) to remove anything left behind by a crash or kill.
 */
public final class PiExtensionFiles {

    private static final Logger LOG = Logger.getLogger(PiExtensionFiles.class.getName());
    private static final String SUBDIR = "pi";
    private static final String EXTENSIONS_SUBDIR = "extensions";
    private static final String FILE_PREFIX = "aicoder-pi-";
    private static final String FILE_SUFFIX = ".ts";

    /**
     * The extension directory, creating it if absent.
     */
    public static Path directory() throws IOException {
        return Files.createDirectories(PluginUtil.getPluginConfigDir().resolve(SUBDIR).resolve(EXTENSIONS_SUBDIR));
    }

    /**
     * The path this session's extension file belongs at, creating the directory (not the file) if needed. Does not
     * imply the file exists — {@code PiExtensionGenerator} writes it.
     */
    public static Path pathFor(String sessionId) throws IOException {
        return directory().resolve(fileName(sessionId));
    }

    private static String fileName(String sessionId) {
        return FILE_PREFIX + sessionId + FILE_SUFFIX;
    }

    /**
     * Deletes one session's extension file. Best-effort: a missing file or an unreadable directory is not an error —
     * cleanup must never fail the caller.
     */
    public static void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Path dir = PluginUtil.getPluginConfigDir().resolve(SUBDIR).resolve(EXTENSIONS_SUBDIR);
            Files.deleteIfExists(dir.resolve(fileName(sessionId)));
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not delete pi extension file for session " + sessionId, e);
        }
    }

    /**
     * Deletes every one of our own generated files in the extension directory — called at IDE shutdown, after every AI
     * tab has closed, next to the idle-watcher shutdown. Only regular files matching our own naming ({@link
     * #isOurFile}) are touched; directories and anything else are left alone. Best-effort; never throws.
     */
    public static void deleteAll() {
        deleteEveryFileQuietly();
    }

    /**
     * Deletes every one of our own generated files in the extension directory at plugin startup, since no pi session
     * can be running yet — this removes files left behind by a crash or kill. Only regular files matching our own
     * naming ({@link #isOurFile}) are touched; directories and anything else are left alone. Best-effort; never throws.
     */
    public static void sweepAtStartup() {
        deleteEveryFileQuietly();
    }

    /**
     * True for a regular file this class itself could have created: a generated extension
     * ({@code aicoder-pi-<sessionId>.ts}) or the temp file {@code PiExtensionGenerator}'s atomic write briefly leaves
     * behind on failure ({@code aicoder-pi-<sessionId>.ts.tmp}). Directories and anything else a user or another
     * process placed in this directory are left alone.
     */
    private static boolean isOurFile(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        String name = p.getFileName().toString();
        return name.startsWith(FILE_PREFIX) && (name.endsWith(FILE_SUFFIX) || name.endsWith(FILE_SUFFIX + ".tmp"));
    }

    private static void deleteEveryFileQuietly() {
        try {
            Path dir = PluginUtil.getPluginConfigDir().resolve(SUBDIR).resolve(EXTENSIONS_SUBDIR);
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(PiExtensionFiles::isOurFile).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException e) {
                        LOG.log(Level.WARNING, "Could not delete pi extension file " + p, e);
                    }
                });
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not sweep pi extension files", e);
        }
    }

    private PiExtensionFiles() {
    }
}
