package kiwi.ingenuity.netbeans.plugin.aicoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

public class PluginUtil {

    private static final String PLUGIN_DIR_NAME = "ai-coder";

    private PluginUtil() {
    }

    /**
     * Returns the plugin's root config/data directory, creating it if absent.
     *
     * Windows: %APPDATA%\AI Coder Unix/Mac: ~/.ai-coder
     */
    public static Path getPluginConfigDir() throws IOException {
        String appData = System.getenv("APPDATA");
        Path dir = (appData != null && !appData.isBlank())
                ? Path.of(appData, StringConst.PLUGIN_NAME)
                : Path.of(System.getProperty("user.home"), "." + PLUGIN_DIR_NAME);
        return Files.createDirectories(dir);
    }

    /**
     * Returns the config directory for the given AI type, creating it if
     * absent.
     *
     * e.g. ~/.ai-coder/claude/
     */
    public static Path getPluginAiSessionConfigDir(AiTypeEnum t) throws IOException {
        return Files.createDirectories(getPluginConfigDir().resolve(t.key()));
    }

    /**
     * Returns the config directory for the given AI session, creating it if
     * absent.
     *
     * e.g. ~/.ai-coder/claude/{sessionId}/
     */
    public static Path getPluginAiSessionConfigDir(AiTypeEnum t, String sessionId) throws IOException {
        return Files.createDirectories(getPluginAiSessionConfigDir(t).resolve(sessionId));
    }

    /**
     * Recursively deletes a session's per-session config directory
     * ({@code ~/.ai-coder/{type}/{sessionId}/} — where AI implementations keep
     * their logs, memory, and other per-session config). Best-effort: a no-op if
     * the directory is absent, and never throws (cleanup must not fail a delete).
     */
    public static void deleteAiSessionConfigDir(AiTypeEnum t, String sessionId) {
        if (t == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Path dir = getPluginConfigDir().resolve(t.key()).resolve(sessionId);
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException ignore) {
                    }
                });
            }
        }
        catch (IOException e) {
            // Best-effort: leave any residue rather than fail the session delete.
        }
    }
}
